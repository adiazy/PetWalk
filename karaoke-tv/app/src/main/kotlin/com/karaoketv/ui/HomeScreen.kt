package com.karaoketv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.karaoketv.KaraokeViewModel

private enum class Tab(val label: String) {
    Library("My music"),
    Online("Find lyrics online"),
    Stream("Play from URL"),
    Settings("Look & feel"),
}

@Composable
fun HomeScreen(vm: KaraokeViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    Row(
        Modifier
            .fillMaxSize()
            .background(LocalAppearance.current.background.brush)
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎤 Karaoke TV", style = MaterialTheme.typography.headlineMedium, color = Pink)
            Spacer(Modifier.height(16.dp))
            Tab.entries.forEach { t ->
                Button(
                    onClick = { tab = t },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) tab = t },
                ) { Text(t.label) }
            }
        }
        Spacer(Modifier.width(32.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Library -> LibraryPane(vm)
                Tab.Online -> OnlinePane(vm)
                Tab.Stream -> StreamPane(vm)
                Tab.Settings -> SettingsPane(vm)
            }
        }
    }
}

@Composable
private fun LibraryPane(vm: KaraokeViewModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadLibrary() else denied = true
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            vm.loadLibrary()
        } else {
            launcher.launch(permission)
        }
    }

    Column {
        Text("Songs on this TV and USB drives", style = MaterialTheme.typography.titleLarge)
        Text(
            "Lyrics come from a .lrc file next to the song, or are fetched from LRCLIB.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        when {
            denied -> Text("Storage permission is needed to list your music.")
            vm.libraryLoading -> Text("Scanning…")
            vm.library.isEmpty() -> Text("No music found. Copy MP3/FLAC/M4A files (and optional .lrc files) to the TV or a USB drive.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(vm.library, key = { it.id }) { song ->
                    SongCard(song.title, song.artist ?: "Unknown artist", formatTime(song.durationMs)) {
                        vm.play(song)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlinePane(vm: KaraokeViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Column {
        Text("Search LRCLIB's open lyrics database", style = MaterialTheme.typography.titleLarge)
        Text(
            "Lyrics-only mode: play the backing track on your phone or speaker and sing along to the timed lyrics here.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TvTextField(query, { query = it }, "Song or artist", Modifier.weight(1f)) { vm.searchOnline(query) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.searchOnline(query) }) { Text(if (vm.searching) "Searching…" else "Search") }
        }
        Spacer(Modifier.height(16.dp))
        vm.searchError?.let { Text(it, color = Pink) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.searchResults, key = { it.id }) { track ->
                val kind = when {
                    track.syncedLyrics != null -> "✓ synced"
                    track.instrumental -> "instrumental"
                    else -> "text only"
                }
                SongCard(
                    track.trackName ?: "Unknown",
                    listOfNotNull(track.artistName, track.albumName).joinToString(" · "),
                    "${formatTime(track.duration?.let { (it * 1000).toLong() })}  $kind",
                ) { vm.playLyricsOnly(track) }
            }
        }
    }
}

@Composable
private fun StreamPane(vm: KaraokeViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Play an audio link", style = MaterialTheme.typography.titleLarge)
        Text(
            "Any direct MP3/AAC/FLAC/HLS link: your NAS, Jellyfin/Plex direct links, internet radio…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        TvTextField(url, { url = it }, "https://…/song.mp3", Modifier.fillMaxWidth(), KeyboardType.Uri)
        TvTextField(name, { name = it }, "Artist - Title (to find lyrics)", Modifier.fillMaxWidth())
        Button(onClick = { if (url.isNotBlank()) vm.playUrl(url, name) }) { Text("Play") }
    }
}

@Composable
private fun SongCard(title: String, subtitle: String, meta: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelMedium, color = Gold)
        }
    }
}

/** A text field that opens the on-screen keyboard when the remote's center button is pressed. */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(Pink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onSubmit() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                    keyboard?.show()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .border(2.dp, if (focused) Pink else Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp)
                inner()
            }
        },
    )
}
