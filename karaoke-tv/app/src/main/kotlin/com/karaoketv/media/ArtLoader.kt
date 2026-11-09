package com.karaoketv.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.karaoketv.core.library.AlbumNode
import com.karaoketv.core.library.ArtistNode
import com.karaoketv.core.library.LibraryTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * Pictures for the artist and album menus.
 *
 * Album: `cover.jpg`/`folder.jpg`… in the album's folder, else art embedded in its songs.
 * Artist: `artist.jpg`/`folder.jpg`… in the artist's folder, else one of the artist's album covers.
 * Each picture found is saved once as a small thumbnail in the app's NAS folder (`art\`), so the
 * menus load quickly on every TV afterwards.
 */
class ArtLoader(
    private val context: Context,
    private val connection: () -> SmbConnection?,
    /** Share-relative app data folder on the NAS, or null. */
    private val appDataDir: () -> String?,
) {
    // Sized by bytes (a 400 px thumbnail is about 0.6 MB), so the menus can't use too much memory.
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }
    private val missing = Collections.synchronizedSet(HashSet<String>())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    fun clear() {
        memory.evictAll()
        missing.clear()
    }

    suspend fun album(artist: String, album: AlbumNode<Song>): ImageBitmap? =
        load(albumKey(artist, album)) { findAlbumArt(album.songs) }

    suspend fun artist(artist: ArtistNode<Song>): ImageBitmap? =
        load(artistKey(artist)) {
            findArtistArt(artist) ?: artist.albums.firstNotNullOfOrNull { album(artist.name, it) }
        }

    /**
     * Background download: makes sure the album has a thumbnail on the NAS (or a "no picture"
     * marker), without keeping it in memory. Returns true if it has a picture.
     */
    suspend fun prefetchAlbum(artist: String, album: AlbumNode<Song>): Boolean =
        prefetch(albumKey(artist, album)) { findAlbumArt(album.songs) }

    /** Like [prefetchAlbum], for the artist picture (falling back to an album's thumbnail). */
    suspend fun prefetchArtist(artist: ArtistNode<Song>): Boolean =
        prefetch(artistKey(artist)) {
            findArtistArt(artist) ?: artist.albums.firstNotNullOfOrNull { storedThumb(albumKey(artist.name, it)) }
        }

    private fun albumKey(artist: String, album: AlbumNode<Song>) = "album:${LibraryTree.key(artist)}/${LibraryTree.key(album.name)}"
    private fun artistKey(artist: ArtistNode<Song>) = "artist:${LibraryTree.key(artist.name)}"

    private fun thumbPath(key: String) = appDataDir()?.let { "$it\\art\\${hash(key)}.jpg" }

    /** "Looked, found no picture": a tiny file next to where the thumbnail would be. */
    private fun noneMarker(thumb: String) = thumb.removeSuffix(".jpg") + ".none"

    private fun storedThumb(key: String): ImageBitmap? {
        val smb = connection() ?: return null
        val thumb = thumbPath(key) ?: return null
        return runCatching { smb.readBytes(thumb) }.getOrNull()?.let { Artwork.decode(it, THUMB_PX) }
    }

    private suspend fun prefetch(key: String, find: suspend () -> ImageBitmap?): Boolean = withContext(io) {
        if (memory.get(key) != null) return@withContext true
        val smb = connection() ?: return@withContext false
        val thumb = thumbPath(key) ?: return@withContext false
        if (runCatching { smb.exists(thumb) }.getOrDefault(false)) return@withContext true
        if (runCatching { smb.exists(noneMarker(thumb)) }.getOrDefault(false)) return@withContext false
        val found = runCatching { find() }.getOrNull()
        runCatching {
            smb.ensureFolder(thumb.substringBeforeLast('\\'))
            if (found != null) smb.writeBytes(thumb, jpeg(found)) else smb.writeBytes(noneMarker(thumb), ByteArray(0))
        }
        found != null
    }

    private suspend fun load(key: String, find: suspend () -> ImageBitmap?): ImageBitmap? {
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val image = withContext(io) {
            val smb = connection()
            val thumb = thumbPath(key)
            if (smb != null && thumb != null) {
                runCatching { smb.readBytes(thumb) }.getOrNull()?.let { bytes -> Artwork.decode(bytes, THUMB_PX) }
                    ?.let { return@withContext it }
            }
            val found = runCatching { find() }.getOrNull() ?: return@withContext null
            if (smb != null && thumb != null) {
                runCatching {
                    smb.ensureFolder(thumb.substringBeforeLast('\\'))
                    smb.writeBytes(thumb, jpeg(found))
                    smb.delete(noneMarker(thumb)) // a picture was added since the last look
                }
            }
            found
        }
        if (image == null) missing += key else memory.put(key, image)
        return image
    }

    private fun findAlbumArt(songs: List<Song>): ImageBitmap? {
        val first = songs.firstOrNull() ?: return null
        if (first.id.startsWith("local:")) return mediaStoreThumb(first)
        val smb = connection() ?: return null
        val path = first.id.removePrefix("smb:")
        imageIn(smb, path.substringBeforeLast('\\', ""), Artwork.FOLDER_IMAGES)?.let { return it }
        for (song in songs.take(EMBEDDED_TRIES)) {
            if (!song.id.startsWith("smb:")) continue
            val bytes = runCatching { smb.embeddedPicture(song.id.removePrefix("smb:")) }.getOrNull() ?: continue
            Artwork.decode(bytes, THUMB_PX)?.let { return it }
        }
        return null
    }

    private fun findArtistArt(artist: ArtistNode<Song>): ImageBitmap? {
        val smb = connection() ?: return null
        val root = smb.location.path
        val song = artist.albums.flatMap { it.songs }.firstOrNull { it.id.startsWith("smb:") } ?: return null
        val songDir = song.id.removePrefix("smb:").substringBeforeLast('\\', "")
        val belowRoot = songDir.removePrefix(root).split('\\').filter { it.isNotEmpty() }
        return when {
            // Artist\Album\song.mp3: the artist's folder is one up, and its folder.jpg is theirs.
            belowRoot.size >= 2 -> imageIn(smb, songDir.substringBeforeLast('\\'), ARTIST_IMAGES + Artwork.FOLDER_IMAGES)
            // Artist\song.mp3: the song's folder is the artist's folder.
            belowRoot.size == 1 -> imageIn(smb, songDir, ARTIST_IMAGES)
            else -> null
        }
    }

    private fun imageIn(smb: SmbConnection, dir: String, names: List<String>): ImageBitmap? {
        for (name in names) {
            val bytes = runCatching { smb.readBytes(if (dir.isEmpty()) name else "$dir\\$name") }.getOrNull() ?: continue
            Artwork.decode(bytes, THUMB_PX)?.let { return it }
        }
        return null
    }

    private fun mediaStoreThumb(song: Song): ImageBitmap? {
        val uri = song.uri ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null) }
            .getOrNull()?.asImageBitmap()
    }

    private fun jpeg(image: ImageBitmap): ByteArray =
        ByteArrayOutputStream().also { image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    private fun hash(key: String) = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    private companion object {
        const val THUMB_PX = 400
        val MEMORY_BYTES = minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()
        const val EMBEDDED_TRIES = 3
        val ARTIST_IMAGES = listOf("artist.jpg", "artist.png", "photo.jpg", "band.jpg")
    }
}
