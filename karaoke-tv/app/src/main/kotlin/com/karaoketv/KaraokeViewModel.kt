package com.karaoketv

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.karaoketv.core.library.SmbLocation
import com.karaoketv.core.lyrics.LrcLibClient
import com.karaoketv.core.lyrics.LrcLibTrack
import com.karaoketv.core.lyrics.Lyrics
import com.karaoketv.core.lyrics.LyricsOvhClient
import com.karaoketv.core.lyrics.LyricsRepository
import com.karaoketv.core.lyrics.SidecarLrcProvider
import com.karaoketv.media.Artwork
import com.karaoketv.media.KaraokeDataSourceFactory
import com.karaoketv.media.LocalLibrary
import com.karaoketv.media.NetworkFolder
import com.karaoketv.media.NetworkFolderStore
import com.karaoketv.media.PlaybackClock
import com.karaoketv.media.PlayerClock
import com.karaoketv.media.SmbConnection
import com.karaoketv.media.SmbSidecarLrcProvider
import com.karaoketv.media.Song
import com.karaoketv.media.StopwatchClock
import com.karaoketv.media.VocalRemover
import com.karaoketv.ui.Appearance
import com.karaoketv.ui.AppearanceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    /** [searched] describes the query; [problems] lists errors such as no internet. */
    data class NotFound(val searched: String = "", val problems: List<String> = emptyList()) : LyricsState
}

@OptIn(UnstableApi::class)
class KaraokeViewModel(app: Application) : AndroidViewModel(app) {
    private val folderStore = NetworkFolderStore(app)
    var networkFolder by mutableStateOf(folderStore.load())
        private set

    @Volatile
    private var smb: SmbConnection? = connect(networkFolder)

    @Volatile
    private var lyricsDir: String? = lyricsDirFor(networkFolder)

    private val lrcLib = LrcLibClient()

    // Synced lyrics found online are downloaded into the shared lyrics folder (see saveLyrics).
    private val shareLyrics = SmbSidecarLrcProvider({ smb }, { lyricsDir })
    private val lyricsRepository = LyricsRepository(
        providers = listOf(SidecarLrcProvider(), shareLyrics),
        // Asked at the same time; lyrics.ovh is a plain-text backup for when LRCLIB has nothing.
        online = listOf(lrcLib, LyricsOvhClient()),
    )

    private val appearanceStore = AppearanceStore(app)
    var appearance by mutableStateOf(appearanceStore.load())
        private set

    private val vocalRemover = VocalRemover()
    private val player: ExoPlayer = buildPlayer(app)

    var library by mutableStateOf<List<Song>>(emptyList())
        private set
    var libraryLoading by mutableStateOf(false)
        private set
    var libraryError by mutableStateOf<String?>(null)
        private set

    var searchResults by mutableStateOf<List<LrcLibTrack>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    var nowPlaying by mutableStateOf<Song?>(null)
        private set
    var lyrics by mutableStateOf<LyricsState>(LyricsState.NotFound())
        private set
    var clock by mutableStateOf<PlaybackClock>(PlayerClock(player))
        private set
    var lyricOffsetMs by mutableLongStateOf(0L)
    var vocalsReduced by mutableStateOf(false)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    /** Album art for the current song: embedded in the file if present, else from its folder. */
    var artwork by mutableStateOf<ImageBitmap?>(null)
        private set
    private var artworkIsEmbedded = false
    private var artworkJob: Job? = null

    private var lyricsJob: Job? = null
    private var playerReady: CompletableDeferred<Unit>? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Cover art embedded in the file beats a folder image.
                mediaMetadata.artworkData?.let { bytes ->
                    val songId = nowPlaying?.id
                    viewModelScope.launch {
                        val image = withContext(Dispatchers.Default) { runCatching { Artwork.decode(bytes) }.getOrNull() }
                        if (image != null && nowPlaying?.id == songId) {
                            artworkJob?.cancel()
                            artworkIsEmbedded = true
                            artwork = image
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Ready means the file's tags and length are known: the best moment to search.
                if (playbackState == Player.STATE_READY) playerReady?.complete(Unit)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = error.localizedMessage ?: error.errorCodeName
            }
        })
    }

    fun updateAppearance(change: (Appearance) -> Appearance) {
        appearance = change(appearance)
        appearanceStore.save(appearance)
    }

    private var libraryRequested = false

    /** First visit to "My music" scans; later visits reuse the list until Refresh. */
    fun loadLibraryIfNeeded() {
        if (libraryRequested) return
        libraryRequested = true
        loadLibrary()
    }

    fun loadLibrary() {
        if (libraryLoading) return
        libraryLoading = true
        libraryError = null
        val folder = networkFolder
        val connection = smb
        viewModelScope.launch {
            val (songs, error) = withContext(Dispatchers.IO) {
                val dir = lyricsDir
                val network = connection?.let { smbConnection ->
                    runCatching {
                        val lrcFiles = dir?.let { runCatching { smbConnection.listNames(it) }.getOrNull() }.orEmpty()
                        smbConnection.scan().map { song ->
                            if (song.hasLyricsFile || !shareLyrics.hasFile(song.toQuery().cleaned(), lrcFiles)) song
                            else song.copy(hasLyricsFile = true)
                        }
                    }
                }
                val device = if (folder.includeDeviceMusic) {
                    runCatching { LocalLibrary.query(getApplication()) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val all = (network?.getOrNull().orEmpty() + device).sortedBy { it.title.lowercase() }
                val problem = when {
                    connection == null -> "\"${folder.path}\" isn't a valid network folder (use \\\\server\\share\\folder)"
                    network?.isFailure == true ->
                        "Couldn't open ${folder.path}: ${network.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
                    else -> null
                }
                all to problem
            }
            library = songs
            libraryError = error
            libraryLoading = false
        }
    }

    fun updateNetworkFolder(folder: NetworkFolder) {
        networkFolder = folder
        folderStore.save(folder)
        val old = smb
        smb = connect(folder)
        lyricsDir = lyricsDirFor(folder)
        viewModelScope.launch(Dispatchers.IO) { runCatching { old?.close() } }
        loadLibrary()
    }

    /** Share-relative lyrics folder path, if it's on the same server and share as the music. */
    private fun lyricsDirFor(folder: NetworkFolder): String? {
        val music = SmbLocation.parse(folder.path) ?: return null
        val lyrics = SmbLocation.parse(folder.lyricsFolder) ?: return null
        val sameShare = lyrics.host.equals(music.host, ignoreCase = true) && lyrics.share.equals(music.share, ignoreCase = true)
        return lyrics.path.takeIf { sameShare && it.isNotEmpty() }
    }

    private fun connect(folder: NetworkFolder): SmbConnection? =
        SmbLocation.parse(folder.path)?.let { SmbConnection(it, folder.username, folder.password) }

    fun searchOnline(text: String) {
        if (text.isBlank()) return
        searching = true
        searchError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { lrcLib.search(text.trim()) } }
            searchResults = result.getOrDefault(emptyList())
            searchError = result.exceptionOrNull()?.let { "Search failed: ${it.localizedMessage}" }
            searching = false
        }
    }

    /** Lyrics-only mode: the lyrics run on a timer while music plays from another device. */
    fun playLyricsOnly(track: LrcLibTrack) {
        play(
            Song(
                id = "lrclib:${track.id}",
                title = track.trackName ?: "Unknown",
                artist = track.artistName,
                album = track.albumName,
                durationMs = track.duration?.let { (it * 1000).toLong() },
                preloadedLyrics = lrcLib.toLyrics(track),
            ),
        )
    }

    fun playUrl(url: String, artistAndTitle: String) {
        val parts = artistAndTitle.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        val (artist, title) = if (parts.size == 2) parts[0] to parts[1] else null to artistAndTitle
        play(Song(id = "url:$url", title = title.trim(), artist = artist?.trim(), uri = Uri.parse(url.trim())))
    }

    fun play(song: Song) {
        nowPlaying = song
        lyricOffsetMs = 0
        manualResults = emptyList()
        manualError = null
        playbackError = null
        loadArtwork(song)
        if (song.uri != null) {
            player.setMediaItem(MediaItem.fromUri(song.uri))
            player.prepare()
            player.play()
            clock = PlayerClock(player)
        } else {
            player.stop()
            clock = StopwatchClock(song.durationMs).also { it.play() }
        }
        val preloaded = song.preloadedLyrics
        if (preloaded != null) {
            lyricsJob?.cancel()
            lyrics = LyricsState.Found(preloaded)
        } else {
            loadLyrics(song)
        }
    }

    /**
     * 1. Lyrics files (lyrics folder, next to the song) right away; no network needed.
     * 2. Otherwise wait until the player has read the file's tags and length (at most
     *    [TAGS_WAIT_MS]) and search online with those, which match far better than file names.
     */
    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        if (song.title.isBlank()) {
            lyrics = LyricsState.NotFound("(no song title)")
            return
        }
        lyrics = LyricsState.Loading
        val ready = CompletableDeferred<Unit>().also { playerReady = it }
        if (song.uri == null || player.playbackState == Player.STATE_READY) ready.complete(Unit)

        lyricsJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val local = withContext(Dispatchers.IO) { lyricsRepository.search(song.toQuery(), includeOnline = false) }
            val fromFile = local.lyrics
            if (fromFile != null) {
                Log.i(TAG, "Lyrics for ${song.title} from ${fromFile.source} in ${System.currentTimeMillis() - started} ms")
                lyrics = LyricsState.Found(fromFile)
                return@launch
            }

            withTimeoutOrNull(TAGS_WAIT_MS) { ready.await() }
            val tagged = withTags(song)
            if (tagged != song && nowPlaying?.id == song.id) nowPlaying = tagged

            val report = withContext(Dispatchers.IO) {
                lyricsRepository.search(tagged.toQuery()).also { r ->
                    // Save under the library's name for the song so the next lookup finds it instantly.
                    r.lyrics?.let { saveLyrics(song, it) }
                }
            }
            val searched = listOfNotNull(report.query.title, report.query.artist?.let { "by $it" }).joinToString(" ")
            Log.i(
                TAG,
                "Lyrics for \"$searched\": ${report.lyrics?.source ?: "not found"} in " +
                    "${System.currentTimeMillis() - started} ms ${report.problems}",
            )
            lyrics = report.lyrics?.let { LyricsState.Found(it) } ?: LyricsState.NotFound(searched, report.problems)
        }
    }

    /** The song with title/artist/album/length from the file's tags where the player found them. */
    private fun withTags(song: Song): Song {
        if (song.uri == null) return song
        val tags = player.mediaMetadata
        fun clean(c: CharSequence?) = c?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        return song.copy(
            title = clean(tags.title) ?: song.title,
            artist = clean(tags.artist) ?: clean(tags.albumArtist) ?: song.artist,
            album = clean(tags.albumTitle) ?: song.album,
            durationMs = duration ?: song.durationMs,
        )
    }

    private fun loadArtwork(song: Song) {
        artworkJob?.cancel()
        artwork = null
        artworkIsEmbedded = false
        val connection = smb
        artworkJob = viewModelScope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching { Artwork.forSong(getApplication(), song, connection) }.getOrNull()
            }
            if (!artworkIsEmbedded && nowPlaying?.id == song.id) artwork = image
        }
    }

    var manualResults by mutableStateOf<List<LrcLibTrack>>(emptyList())
        private set
    var manualSearching by mutableStateOf(false)
        private set
    var manualError by mutableStateOf<String?>(null)
        private set

    /** Lets the singer search LRCLIB by hand from the karaoke screen when the automatic match fails. */
    fun searchLyricsManually(text: String) {
        if (text.isBlank()) return
        manualSearching = true
        manualError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { lrcLib.search(text.trim()) } }
            manualResults = result.getOrDefault(emptyList()).filter { it.syncedLyrics != null || it.plainLyrics != null }
            manualError = result.exceptionOrNull()?.let { e ->
                Log.w(TAG, "Manual lyrics search failed", e)
                "Search failed: ${e.message ?: e::class.simpleName}"
            } ?: if (manualResults.isEmpty()) "Nothing found for \"$text\"." else null
            manualSearching = false
        }
    }

    /** Shows the chosen result for the current song (kept in memory only). */
    fun useLyrics(track: LrcLibTrack) {
        lyricsJob?.cancel()
        val chosen = lrcLib.toLyrics(track) ?: return
        lyrics = LyricsState.Found(chosen)
        // The singer picked these by hand, so they replace whatever file was there.
        val song = nowPlaying ?: return
        viewModelScope.launch(Dispatchers.IO) { saveLyrics(song, chosen, overwrite = true) }
    }

    /**
     * Downloads synced lyrics into the shared lyrics folder as `Artist - Title.lrc`, unless they
     * came from a file on the share already. Blocking; call on IO. Returns true if a file was written.
     */
    private fun saveLyrics(song: Song, found: com.karaoketv.core.lyrics.Lyrics, overwrite: Boolean = false): Boolean {
        if (!found.synced) return false
        if (!overwrite && SmbSidecarLrcProvider.isFromShare(found)) return false
        val saved = shareLyrics.save(song.toQuery().cleaned(), found)
        if (saved) {
            Log.i(TAG, "Saved lyrics for ${song.title} to $lyricsDir")
            viewModelScope.launch {
                library = library.map { if (it.id == song.id) it.copy(hasLyricsFile = true) else it }
            }
        }
        return saved
    }

    var lyricsDownload by mutableStateOf<String?>(null)
        private set
    private var lyricsDownloadJob: Job? = null
    val downloadingLyrics get() = lyricsDownloadJob?.isActive == true

    /** Downloads lyrics for every song that doesn't have a lyrics file yet; call again to stop. */
    fun downloadAllLyrics() {
        if (downloadingLyrics) {
            lyricsDownloadJob?.cancel()
            lyricsDownload = "Stopped."
            return
        }
        if (lyricsDir == null) {
            lyricsDownload = "Set a lyrics folder on the same share as the music (Change folder)."
            return
        }
        val todo = library.filter { !it.hasLyricsFile }
        if (todo.isEmpty()) {
            lyricsDownload = "Every song already has a lyrics file."
            return
        }
        lyricsDownloadJob = viewModelScope.launch {
            var saved = 0
            var notFound = 0
            for ((i, song) in todo.withIndex()) {
                lyricsDownload = "Downloading lyrics ${i + 1}/${todo.size}: ${song.title}  (saved $saved)"
                val ok = withContext(Dispatchers.IO) {
                    // Tags inside the file (title, artist, length) match far better than file names.
                    val tagged = tagsFromShare(song)
                    val found = runCatching { lyricsRepository.find(tagged.toQuery()) }.getOrNull()
                    if (found == null || !found.synced) notFound++
                    found != null && saveLyrics(song, found)
                }
                if (ok) saved++
                delay(BULK_PAUSE_MS) // be gentle with the free lyrics service
            }
            lyricsDownload = "Done: saved $saved lyrics files" +
                (if (notFound > 0) "; no synced lyrics found for $notFound songs" else "") + "."
        }
    }

    /** Blocking. The song with tags read from its file on the share, if it has any. */
    private fun tagsFromShare(song: Song): Song {
        val uri = song.uri?.takeIf { it.scheme == "smb" } ?: return song
        val connection = smb ?: return song
        val path = connection.pathOf(uri) ?: return song
        val tags = runCatching { connection.readTags(path) }.getOrNull() ?: return song
        return song.copy(
            title = tags.title,
            artist = tags.artist ?: song.artist,
            album = tags.album ?: song.album,
            durationMs = tags.durationMs ?: song.durationMs,
        )
    }

    private companion object {
        const val TAG = "KaraokeTV"
        const val TAGS_WAIT_MS = 2_500L
        const val BULK_PAUSE_MS = 300L
    }

    fun togglePlay() {
        if (clock.isPlaying) clock.pause() else clock.play()
    }

    fun seekBy(deltaMs: Long) = clock.seekTo(clock.positionMs + deltaMs)

    fun toggleVocals() {
        vocalsReduced = !vocalsReduced
        vocalRemover.enabled = vocalsReduced
    }

    fun stop() {
        lyricsJob?.cancel()
        player.stop()
        clock.pause()
        nowPlaying = null
    }

    override fun onCleared() {
        player.release()
        val connection = smb
        Thread { runCatching { connection?.close() } }.start()
    }

    private fun buildPlayer(app: Application): ExoPlayer {
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            val sink = DefaultAudioSink.Builder(app)
                .setAudioProcessors(arrayOf(vocalRemover))
                .build()
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener, sink),
            )
        }
        return ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(KaraokeDataSourceFactory(app) { smb }))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
            }
    }
}
