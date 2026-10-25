package com.karaoketv.media

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.karaoketv.core.library.SmbLocation
import com.karaoketv.core.library.TrackName
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/**
 * Talks to a Windows/NAS share (SMB 2/3). Songs are exposed as `smb://host/share/path` URIs
 * that [SmbDataSource] can stream, so nothing is copied to the TV.
 */
class SmbConnection(
    val location: SmbLocation,
    private val username: String,
    private val password: String,
) : Closeable {
    private val client = SMBClient(
        SmbConfig.builder()
            // Android's JCE lacks MD4, which NTLM login needs; BouncyCastle provides it.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
    private var connection: Connection? = null
    private var share: DiskShare? = null

    @Synchronized
    fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        closeConnection()
        val conn = client.connect(location.host)
        connection = conn
        var lastError: Exception? = null
        for (auth in authContexts()) {
            try {
                val session = conn.authenticate(auth)
                val disk = session.connectShare(location.share) as? DiskShare
                    ?: error("${location.share} is not a file share")
                share = disk
                return disk
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Could not log in to ${location.host}")
    }

    private fun authContexts(): List<AuthenticationContext> {
        if (username.isBlank()) return listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        val (domain, user) = if ('\\' in username) {
            username.substringBefore('\\') to username.substringAfter('\\')
        } else {
            null to username
        }
        return listOf(AuthenticationContext(user, password.toCharArray(), domain))
    }

    fun openRead(path: String): SmbFile = share().openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    fun writeText(path: String, text: String) {
        share().openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        ).use { file -> file.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    /** Creates [path] and any missing parent folders. */
    fun ensureFolder(path: String) {
        val disk = share()
        var current = ""
        for (part in path.split('\\').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!disk.folderExists(current)) disk.mkdir(current)
        }
    }

    /** Lower-cased names of the files in [dir], or empty if it doesn't exist. */
    fun listNames(dir: String): Set<String> {
        val disk = share()
        if (!disk.folderExists(dir)) return emptySet()
        return disk.list(dir).mapTo(HashSet()) { it.fileName.lowercase() }
    }

    /** Reads a small file (e.g. cover.jpg), or null if it's missing or larger than [maxBytes]. */
    fun readBytes(path: String, maxBytes: Long = 8L * 1024 * 1024): ByteArray? {
        if (!share().fileExists(path)) return null
        return openRead(path).use { file ->
            if (file.fileInformation.standardInformation.endOfFile > maxBytes) null
            else file.inputStream.use { it.readBytes() }
        }
    }

    fun readText(path: String): String? {
        if (!share().fileExists(path)) return null
        return openRead(path).use { it.inputStream.bufferedReader().readText() }
    }

    /** Recursively lists audio files under the configured folder. */
    fun scan(): List<Song> {
        val disk = share()
        val songs = mutableListOf<Song>()
        fun walk(dir: String, depth: Int) {
            val folders = dir.removePrefix(location.path).split('\\').filter { it.isNotEmpty() }
            val entries = disk.list(dir)
            val names = entries.mapTo(HashSet()) { it.fileName.lowercase() }
            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == ".." || name.startsWith(".")) continue
                val path = if (dir.isEmpty()) name else "$dir\\$name"
                val isDir = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                if (isDir) {
                    if (depth < MAX_DEPTH) walk(path, depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    val parsed = TrackName.parse(name, folders)
                    val base = name.substringBeforeLast('.').lowercase()
                    val uri = uriFor(path)
                    songs += Song(
                        id = "smb:$path",
                        title = parsed.title,
                        artist = parsed.artist,
                        uri = uri,
                        path = uri.toString(),
                        hasLyricsFile = LYRICS_EXTENSIONS.any { "$base.$it" in names },
                    )
                }
            }
        }
        walk(location.path, 0)
        return songs
    }

    fun uriFor(path: String): Uri = Uri.Builder()
        .scheme("smb")
        .authority(location.host)
        .appendPath(location.share)
        .apply { path.split('\\').filter { it.isNotEmpty() }.forEach { appendPath(it) } }
        .build()

    /** Share-relative path for a `smb://` URI on this server, or null if it's elsewhere. */
    fun pathOf(uri: Uri): String? {
        if (uri.scheme != "smb" || !uri.host.equals(location.host, ignoreCase = true)) return null
        val segments = uri.pathSegments
        if (segments.isEmpty() || !segments[0].equals(location.share, ignoreCase = true)) return null
        return segments.drop(1).joinToString("\\")
    }

    @Synchronized
    private fun closeConnection() {
        runCatching { share?.close() }
        runCatching { connection?.close() }
        share = null
        connection = null
    }

    override fun close() {
        closeConnection()
        runCatching { client.close() }
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
        val LYRICS_EXTENSIONS = listOf("lrc", "txt")
        private const val MAX_DEPTH = 8
    }
}
