package com.karaoketv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.karaoketv.KaraokeViewModel
import com.karaoketv.LyricsState
import com.karaoketv.media.Song

@Composable
fun PlayerScreen(vm: KaraokeViewModel) {
    val song = vm.nowPlaying ?: return
    val clock = vm.clock
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(clock) {
        while (true) {
            withFrameMillis {
                position = clock.positionMs
                playing = clock.isPlaying
                duration = clock.durationMs ?: song.durationMs
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var searchingByHand by remember(song.id) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalAppearance.current.background.brush)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { vm.togglePlay(); true }
                    Key.MediaFastForward -> { vm.seekBy(10_000); true }
                    Key.MediaRewind -> { vm.seekBy(-10_000); true }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(song.artist ?: "", style = MaterialTheme.typography.titleMedium, color = Pink)
            }
            val lyricsSource = (vm.lyrics as? LyricsState.Found)?.lyrics?.source
            val mode = if (song.uri == null) "Lyrics-only mode · " else ""
            Text("$mode${lyricsSource?.let { "Lyrics: $it" } ?: ""}", color = Color.White.copy(alpha = 0.5f))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val state = vm.lyrics) {
                LyricsState.Loading -> Text("Looking for lyrics…", style = MaterialTheme.typography.headlineMedium)
                is LyricsState.NotFound -> LyricsSearchPanel(vm, song, state)
                is LyricsState.Found ->
                    if (searchingByHand) {
                        LyricsSearchPanel(vm, song, null) { searchingByHand = false }
                    } else {
                        LyricsDisplay(state.lyrics, position + vm.lyricOffsetMs, duration)
                    }
            }
        }

        vm.playbackError?.let { Text("Playback error: $it", color = Pink) }
        ProgressBar(position, duration)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::togglePlay, modifier = Modifier.focusRequester(playFocus)) {
                Text(if (playing) "❚❚ Pause" else "▶ Play")
            }
            Button(onClick = { vm.seekBy(-10_000) }) { Text("⟲ 10s") }
            Button(onClick = { vm.seekBy(10_000) }) { Text("10s ⟳") }
            // The offset is added to the playback position, so a positive value shows lyrics sooner.
            Button(onClick = { vm.lyricOffsetMs += 250 }) { Text("Lyrics earlier") }
            Button(onClick = { vm.lyricOffsetMs -= 250 }) { Text("Lyrics later") }
            if (vm.lyrics is LyricsState.Found) {
                Button(onClick = { searchingByHand = !searchingByHand }) {
                    Text(if (searchingByHand) "Back to lyrics" else "Wrong lyrics?")
                }
            }
            if (song.uri != null) {
                Button(onClick = vm::toggleVocals) { Text(if (vm.vocalsReduced) "Vocals: reduced" else "Vocals: normal") }
            }
            Spacer(Modifier.width(8.dp))
            if (vm.lyricOffsetMs != 0L) {
                Text("Lyrics %+.2fs".format(-vm.lyricOffsetMs / 1000f), color = Gold)
            }
        }
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp)),
        ) {
            val fraction = if (duration != null && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(Brush.horizontalGradient(listOf(Pink, Gold)), RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * Shown when no lyrics were found (with what was searched and why), or on request when the match
 * is wrong. Lets the singer search LRCLIB by hand and pick the right song.
 */
@Composable
private fun LyricsSearchPanel(
    vm: KaraokeViewModel,
    song: Song,
    notFound: LyricsState.NotFound?,
    onPicked: () -> Unit = {},
) {
    var text by remember(song.id) {
        mutableStateOf(listOfNotNull(song.artist, song.title).joinToString(" "))
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (notFound != null) {
            Text("No lyrics found", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            if (notFound.searched.isNotEmpty()) {
                Text("Searched for: ${notFound.searched}", color = Color.White.copy(alpha = 0.7f))
            }
            notFound.problems.forEach { Text("⚠ $it", color = Pink) }
        } else {
            Text("Find the right lyrics", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                text,
                { text = it },
                "Artist and song title",
                Modifier.weight(1f),
                onSubmit = { vm.searchLyricsManually(text) },
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.searchLyricsManually(text) }) {
                Text(if (vm.manualSearching) "Searching…" else "Search lyrics")
            }
        }
        vm.manualError?.let { Text(it, color = Pink) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.manualResults.take(MAX_RESULTS), key = { it.id }) { track ->
                val kind = if (track.syncedLyrics != null) "✓ synced" else "text only"
                Button(
                    onClick = {
                        vm.useLyrics(track)
                        onPicked()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        listOfNotNull(track.trackName, track.artistName, formatTime(track.duration?.let { (it * 1000).toLong() }), kind)
                            .joinToString("  ·  "),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val MAX_RESULTS = 8
