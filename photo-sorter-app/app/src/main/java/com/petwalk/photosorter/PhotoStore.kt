package com.petwalk.photosorter

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/** A gallery album (MediaStore bucket). [id] is null for "All photos". */
data class Album(val id: String?, val name: String, val count: Int)

data class Photo(val uri: Uri, val name: String, val mimeType: String, val dateTaken: Long)

/** Reads photos from, and writes sorted albums to, the device's shared photo library. */
class PhotoStore(private val resolver: ContentResolver) {

    private val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // Skip anything we already sorted, so re-running doesn't pick up our own copies.
    private val notSorted =
        "(${MediaStore.Images.Media.RELATIVE_PATH} IS NULL OR ${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ?)"
    private val notSortedArgs = arrayOf("$OUTPUT_DIR/%")

    fun albums(): List<Album> {
        val buckets = LinkedHashMap<String, Pair<String, Int>>()
        var total = 0
        resolver.query(
            images,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            notSorted, notSortedArgs, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                total++
                val id = c.getString(idCol) ?: continue
                val (name, count) = buckets[id] ?: ((c.getString(nameCol) ?: "Unnamed") to 0)
                buckets[id] = name to count + 1
            }
        }
        val albums = buckets.map { (id, v) -> Album(id, v.first, v.second) }.sortedByDescending { it.count }
        return listOf(Album(null, "All photos", total)) + albums
    }

    fun photos(albumId: String?): List<Photo> {
        val selection = if (albumId == null) notSorted else "$notSorted AND ${MediaStore.Images.Media.BUCKET_ID} = ?"
        val args = if (albumId == null) notSortedArgs else notSortedArgs + albumId
        val photos = mutableListOf<Photo>()
        resolver.query(
            images,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_TAKEN,
            ),
            selection, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                photos += Photo(
                    uri = ContentUris.withAppendedId(images, id),
                    name = c.getString(nameCol) ?: "photo_$id.jpg",
                    mimeType = c.getString(mimeCol) ?: "image/jpeg",
                    dateTaken = c.getLong(dateCol),
                )
            }
        }
        return photos
    }

    /** GPS coordinates from the photo's EXIF data, or null if it has none. */
    fun location(photo: Photo): Pair<Double, Double>? {
        val exif = try {
            openOriginal(photo.uri)?.use { ExifInterface(it) }
        } catch (e: Exception) {
            null
        }
        val latLong = exif?.latLong ?: return null
        if (latLong[0] == 0.0 && latLong[1] == 0.0) return null
        return latLong[0] to latLong[1]
    }

    fun thumbnail(uri: Uri, size: Int): Bitmap? = try {
        resolver.loadThumbnail(uri, Size(size, size), null)
    } catch (e: Exception) {
        null
    }

    /** Copies the photo (with its original metadata) into Pictures/Sorted/<folder>/. */
    fun copy(photo: Photo, folder: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$OUTPUT_DIR/$folder/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (photo.dateTaken > 0) put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTaken)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dest = resolver.insert(collection, values) ?: return false
        return try {
            openOriginal(photo.uri)!!.use { input ->
                resolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
            }
            resolver.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(dest, null, null)
            false
        }
    }

    /** Asks the user to let us modify these photos (Android 11+), which moving needs. */
    fun writeRequest(photos: List<Photo>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(resolver, photos.map { it.uri })
        } else {
            null
        }

    /** Moves the photo into Pictures/Sorted/<folder>/. Needs a granted [writeRequest]. */
    fun move(photo: Photo, folder: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, "$OUTPUT_DIR/$folder/")
        }
        resolver.update(photo.uri, values, null, null) > 0
    } catch (e: Exception) {
        false
    }

    // The original file keeps its GPS tags; the default stream has them redacted.
    private fun openOriginal(uri: Uri): InputStream? = try {
        resolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (e: Exception) {
        resolver.openInputStream(uri)
    }

    companion object {
        val OUTPUT_DIR = "${Environment.DIRECTORY_PICTURES}/Sorted"
    }
}
