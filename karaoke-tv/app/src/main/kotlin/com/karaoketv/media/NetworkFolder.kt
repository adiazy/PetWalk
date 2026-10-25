package com.karaoketv.media

import android.content.Context

/** Where "My music" looks on the network, plus optional login (blank = guest access). */
data class NetworkFolder(
    val path: String = DEFAULT_PATH,
    val username: String = "",
    val password: String = "",
    val includeDeviceMusic: Boolean = true,
    /** Where downloaded lyrics are saved and looked up; must be on the same share as [path]. */
    val lyricsFolder: String = DEFAULT_LYRICS_PATH,
) {
    companion object {
        const val DEFAULT_PATH = """\\192.168.1.99\Public\Shared Music"""
        const val DEFAULT_LYRICS_PATH = """\\192.168.1.99\Public\Shared Music\.lrc"""
    }
}

class NetworkFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load() = NetworkFolder(
        path = prefs.getString(KEY_PATH, null) ?: NetworkFolder.DEFAULT_PATH,
        username = prefs.getString(KEY_USER, null).orEmpty(),
        password = prefs.getString(KEY_PASSWORD, null).orEmpty(),
        includeDeviceMusic = prefs.getBoolean(KEY_DEVICE, true),
        lyricsFolder = prefs.getString(KEY_LYRICS_FOLDER, null) ?: NetworkFolder.DEFAULT_LYRICS_PATH,
    )

    fun save(folder: NetworkFolder) {
        prefs.edit()
            .putString(KEY_PATH, folder.path)
            .putString(KEY_USER, folder.username)
            .putString(KEY_PASSWORD, folder.password)
            .putBoolean(KEY_DEVICE, folder.includeDeviceMusic)
            .putString(KEY_LYRICS_FOLDER, folder.lyricsFolder)
            .apply()
    }

    private companion object {
        const val KEY_PATH = "smb_path"
        const val KEY_USER = "smb_user"
        const val KEY_PASSWORD = "smb_password"
        const val KEY_DEVICE = "include_device_music"
        const val KEY_LYRICS_FOLDER = "lyrics_folder"
    }
}
