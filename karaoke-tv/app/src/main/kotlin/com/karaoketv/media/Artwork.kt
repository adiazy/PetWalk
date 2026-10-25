package com.karaoketv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Finds and decodes album art: folder images on the share, or the system's art for local songs. */
object Artwork {
    /** Common names for album art saved next to the songs (Windows Media Player, iTunes, etc.). */
    private val FOLDER_IMAGES = listOf(
        "cover.jpg", "folder.jpg", "front.jpg", "album.jpg", "albumart.jpg",
        "cover.png", "folder.png", "front.png", "AlbumArtLarge.jpg",
    )
    private const val MAX_SIZE_PX = 1280

    fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    /** Blocking; call on a background thread. */
    fun forSong(context: Context, song: Song, smb: SmbConnection?): ImageBitmap? {
        val uri = song.uri ?: return null
        return when (uri.scheme) {
            "smb" -> fromShareFolder(uri, smb)
            "content" -> fromMediaStore(context, uri)
            else -> null
        }
    }

    private fun fromShareFolder(uri: Uri, smb: SmbConnection?): ImageBitmap? {
        val connection = smb ?: return null
        val path = connection.pathOf(uri) ?: return null
        val dir = path.substringBeforeLast('\\', "")
        for (name in FOLDER_IMAGES) {
            val image = if (dir.isEmpty()) name else "$dir\\$name"
            val bytes = runCatching { connection.readBytes(image) }.getOrNull() ?: continue
            decode(bytes)?.let { return it }
        }
        return null
    }

    private fun fromMediaStore(context: Context, uri: Uri): ImageBitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val bitmap: Bitmap = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(MAX_SIZE_PX, MAX_SIZE_PX), null)
        }.getOrNull() ?: return null
        return bitmap.asImageBitmap()
    }
}
