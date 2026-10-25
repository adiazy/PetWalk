package com.karaoketv.media

import android.content.Context

/** Where "My music" looks on the network, plus optional login (blank = guest access). */
data class NetworkFolder(
    val path: String = DEFAULT_PATH,
    val username: String = "",
    val password: String = "",
    val includeDeviceMusic: Boolean = true,
) {
    companion object {
        const val DEFAULT_PATH = """\\192.168.1.99\Public\Shared Music"""
    }
}

class NetworkFolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load() = NetworkFolder(
        path = prefs.getString(KEY_PATH, null) ?: NetworkFolder.DEFAULT_PATH,
        username = prefs.getString(KEY_USER, null).orEmpty(),
        password = prefs.getString(KEY_PASSWORD, null).orEmpty(),
        includeDeviceMusic = prefs.getBoolean(KEY_DEVICE, true),
    )

    fun save(folder: NetworkFolder) {
        prefs.edit()
            .putString(KEY_PATH, folder.path)
            .putString(KEY_USER, folder.username)
            .putString(KEY_PASSWORD, folder.password)
            .putBoolean(KEY_DEVICE, folder.includeDeviceMusic)
            .apply()
    }

    private companion object {
        const val KEY_PATH = "smb_path"
        const val KEY_USER = "smb_user"
        const val KEY_PASSWORD = "smb_password"
        const val KEY_DEVICE = "include_device_music"
    }
}
