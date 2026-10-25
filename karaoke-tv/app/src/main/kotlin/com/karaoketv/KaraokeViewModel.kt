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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val lrcLib = LrcLibClient()

    // Lyrics files are only ever read; online results are kept in memory and never saved.
    private val lyricsRepository = LyricsRepository(
        listOf(
            SidecarLrcProvider(),
            SmbSidecarLrcProvider { smb },
            lrcLib,
            // Backup when LRCLIB is busy or has nothing (plain text only).
            LyricsOvhClient(),
        ),
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

                // Streams and network files often only reveal their real title/artist tags once
                // playing. Retry with them if the file-name guess found nothing or had no artist.
                val song = nowPlaying ?: return
                val title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return
                val artist = mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                val worthRetrying = lyrics is LyricsState.NotFound || (song.artist == null && artist != null)
                if (worthRetrying && (song.title != title || song.artist != artist)) {
                    val updated = song.copy(title = title, artist = artist ?: song.artist)
                    nowPlaying = updated
                    loadLyrics(updated)
                }
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
                val network = connection?.let { runCatching { it.scan() } }
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
        viewModelScope.launch(Dispatchers.IO) { runCatching { old?.close() } }
        loadLibrary()
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

    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        if (song.title.isBlank()) {
            lyrics = LyricsState.NotFound("(no song title)")
            return
        }
        lyrics = LyricsState.Loading
        lyricsJob = viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { lyricsRepository.search(song.toQuery()) }
            val searched = listOfNotNull(report.query.title, report.query.artist?.let { "by $it" }).joinToString(" ")
            Log.i(TAG, "Lyrics for \"$searched\": ${report.lyrics?.source ?: "not found"} ${report.problems}")
            lyrics = report.lyrics?.let { LyricsState.Found(it) } ?: LyricsState.NotFound(searched, report.problems)
        }
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
        lrcLib.toLyrics(track)?.let { lyrics = LyricsState.Found(it) }
    }

    private companion object {
        const val TAG = "KaraokeTV"
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
