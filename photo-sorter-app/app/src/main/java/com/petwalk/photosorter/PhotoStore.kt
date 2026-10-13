package com.petwalk.photosorter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

/** An image inside the chosen folder. [parent] is the folder it currently sits in. */
data class Photo(val uri: Uri, val parent: Uri, val name: String, val mimeType: String)

/**
 * Reads and writes photos inside one folder the user picked with the system folder picker.
 * The app has no access to anything outside that folder.
 */
class PhotoStore(private val resolver: ContentResolver) {

    private val dirCache = HashMap<String, Uri>()

    fun folderName(tree: Uri): String? = try {
        resolver.query(rootOf(tree), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** All images in the folder and its subfolders, oldest first, except our own output folder. */
    fun photos(tree: Uri): List<Photo> {
        val photos = mutableListOf<Pair<Photo, Long>>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), skipOutput = true) { photo, modified ->
            photos += photo to modified
        }
        return photos.sortedBy { it.second }.map { it.first }
    }

    /** Names of the photos already in the Sorted folder, so they can be skipped. */
    fun sortedNames(tree: Uri): Set<String> {
        val outputDir = findChildDir(tree, rootOf(tree), OUTPUT_DIR) ?: return emptySet()
        val names = HashSet<String>()
        walk(tree, DocumentsContract.getDocumentId(outputDir), skipOutput = false) { photo, _ -> names += photo.name }
        return names
    }

    private fun walk(tree: Uri, dirId: String, skipOutput: Boolean, onPhoto: (Photo, Long) -> Unit) {
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val subdirs = mutableListOf<String>()
        resolver.query(
            children,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: continue
                when {
                    mime == Document.MIME_TYPE_DIR ->
                        if (!name.startsWith(".") && !(skipOutput && name == OUTPUT_DIR)) subdirs += id
                    mime.startsWith("image/") -> onPhoto(
                        Photo(DocumentsContract.buildDocumentUriUsingTree(tree, id), dirUri, name, mime),
                        if (c.isNull(3)) 0L else c.getLong(3),
                    )
                }
            }
        }
        // Only the top level holds our output folder; deeper "Sorted" folders are the user's own.
        subdirs.forEach { walk(tree, it, skipOutput = false, onPhoto) }
    }

    /** GPS coordinates from the photo's EXIF data, or null if it has none. */
    fun location(photo: Photo): Pair<Double, Double>? {
        val exif = try {
            resolver.openInputStream(photo.uri)?.use { ExifInterface(it) }
        } catch (e: Exception) {
            null
        }
        val latLong = exif?.latLong ?: return null
        if (latLong[0] == 0.0 && latLong[1] == 0.0) return null
        return latLong[0] to latLong[1]
    }

    /** A downscaled, correctly rotated bitmap whose short side is at least [size] px. */
    fun thumbnail(uri: Uri, size: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val shortest = min(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, shortest / size))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // ML Kit can't read hardware bitmaps
        }
    } catch (e: Exception) {
        null
    }

    /** Call before a copy or move so folders are looked up fresh. */
    fun resetFolders() = dirCache.clear()

    /** Copies the photo into <folder>/Sorted/<group>/. */
    fun copy(tree: Uri, photo: Photo, group: String): Boolean {
        val created = try {
            DocumentsContract.createDocument(resolver, outputDir(tree, group), photo.mimeType, photo.name)
        } catch (e: Exception) {
            null
        }
        val dest = created ?: return false
        return try {
            resolver.openInputStream(photo.uri)!!.use { input ->
                resolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, dest)
            } catch (ignored: Exception) {
            }
            false
        }
    }

    /** Moves the photo into <folder>/Sorted/<group>/. */
    fun move(tree: Uri, photo: Photo, group: String): Boolean = try {
        DocumentsContract.moveDocument(resolver, photo.uri, photo.parent, outputDir(tree, group)) != null
    } catch (e: Exception) {
        false
    }

    private fun rootOf(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Finds or creates <folder>/Sorted/<group>, where [group] may contain "/". */
    private fun outputDir(tree: Uri, group: String): Uri {
        var path = ""
        var dir = rootOf(tree)
        for (name in listOf(OUTPUT_DIR) + group.split("/")) {
            path += "/$name"
            val parent = dir
            dir = dirCache.getOrPut(path) {
                findChildDir(tree, parent, name)
                    ?: DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
                    ?: error("Couldn't create folder $path")
            }
        }
        return dir
    }

    private fun findChildDir(tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    companion object {
        const val OUTPUT_DIR = "Sorted"
    }
}
