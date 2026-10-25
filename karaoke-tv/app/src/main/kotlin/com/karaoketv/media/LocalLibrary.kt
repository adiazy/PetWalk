package com.karaoketv.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Reads music from device storage, including USB drives (all external volumes on Android 10+). */
object LocalLibrary {
    fun query(context: Context): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        )
        val songs = mutableListOf<Song>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                songs += Song(
                    id = "local:$id",
                    title = c.getString(titleCol) ?: "Untitled",
                    artist = c.getString(artistCol),
                    album = c.getString(albumCol),
                    durationMs = c.getLong(durationCol).takeIf { it > 0 },
                    uri = ContentUris.withAppendedId(collection, id),
                    path = c.getString(dataCol),
                )
            }
        }
        return songs
    }
}
