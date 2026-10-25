package com.karaoketv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.karaoketv.KaraokeViewModel
import com.karaoketv.media.NetworkFolder
import com.karaoketv.media.Song

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
    // Network music doesn't need the storage permission, so load either way.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.loadLibraryIfNeeded()
    }
    LaunchedEffect(Unit) {
        val needsPermission = vm.networkFolder.includeDeviceMusic &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) launcher.launch(permission) else vm.loadLibraryIfNeeded()
    }
    var editing by remember { mutableStateOf(false) }

    Column {
        Text("My music", style = MaterialTheme.typography.titleLarge)
        Text(
            "📁 ${vm.networkFolder.path}" + if (vm.networkFolder.includeDeviceMusic) "  + this TV" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { editing = !editing }) { Text(if (editing) "Cancel" else "Change folder") }
            Button(onClick = vm::loadLibrary) { Text("Refresh") }
            Button(onClick = vm::downloadAllLyrics) {
                Text(if (vm.lyricsDownload?.startsWith("Downloading") == true) "Stop" else "Download lyrics for all songs")
            }
        }
        vm.lyricsDownload?.let { Text(it, color = Gold, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(12.dp))
        if (editing) {
            FolderEditor(vm) { editing = false }
            return@Column
        }
        vm.libraryError?.let {
            Text(it, color = Pink, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        when {
            vm.libraryLoading -> Text("Scanning your music…")
            vm.library.isEmpty() -> Text(
                "No music found. Put MP3/FLAC/M4A files (and optional .lrc lyrics with the same name) " +
                    "in the network folder, then press Refresh.",
            )
            else -> ArtistList(vm)
        }
    }
}

/**
 * Artists as compact rows, collapsed. Selecting one opens its songs right below it (one artist
 * at a time); selecting it again or pressing Back closes it.
 */
@Composable
private fun ArtistList(vm: KaraokeViewModel) {
    val groups = remember(vm.library) { groupByArtist(vm.library) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler(enabled = expanded != null) { expanded = null }
    LaunchedEffect(expanded) {
        val index = groups.indexOfFirst { it.first == expanded }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        for ((artist, songs) in groups) {
            val open = artist == expanded
            item(key = "artist:$artist") {
                CompactRow(onClick = { expanded = if (open) null else artist }) {
                    Text(
                        (if (open) "▾  " else "▸  ") + artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (open) Pink else Color.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (songs.size == 1) "1 song" else "${songs.size} songs",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
            if (open) {
                items(songs, key = { it.id }) { song ->
                    CompactRow(onClick = { vm.play(song) }, indent = 28.dp) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (song.hasLyricsFile) Text("♪  ", color = Gold, style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatTime(song.durationMs).takeUnless { song.durationMs == null } ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

/** A slim, focusable list row (about 40dp tall) for the TV remote. */
@Composable
private fun CompactRow(
    onClick: () -> Unit,
    indent: Dp = 0.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = Color.White,
            focusedContainerColor = Pink.copy(alpha = 0.35f),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private const val UNKNOWN_ARTIST = "Unknown artist"

/** Artists A–Z (unknown last), each with its songs A–Z. */
private fun groupByArtist(songs: List<Song>): List<Pair<String, List<Song>>> =
    songs
        .groupBy { song ->
            song.artist?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) } ?: UNKNOWN_ARTIST
        }
        // Merge "queen" and "Queen" under one heading.
        .entries
        .groupBy({ it.key.lowercase() }, { it })
        .map { (_, entries) -> entries.first().key to entries.flatMap { it.value }.sortedBy { it.title.lowercase() } }
        .sortedWith(compareBy<Pair<String, List<Song>>> { it.first == UNKNOWN_ARTIST }.thenBy { it.first.lowercase() })

@Composable
private fun FolderEditor(vm: KaraokeViewModel, onDone: () -> Unit) {
    val current = vm.networkFolder
    var path by remember { mutableStateOf(current.path) }
    var user by remember { mutableStateOf(current.username) }
    var password by remember { mutableStateOf(current.password) }
    var includeDevice by remember { mutableStateOf(current.includeDeviceMusic) }
    var lyricsFolder by remember { mutableStateOf(current.lyricsFolder) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network folder (Windows share or NAS)", style = MaterialTheme.typography.titleMedium)
        TvTextField(path, { path = it }, """\\192.168.1.99\Public\Shared Music""", Modifier.fillMaxWidth())
        Text(
            "Leave the login empty for public/guest shares. Use DOMAIN\\user if needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text("Lyrics folder (downloads are saved here; must be on the same share)", style = MaterialTheme.typography.titleMedium)
        TvTextField(lyricsFolder, { lyricsFolder = it }, NetworkFolder.DEFAULT_LYRICS_PATH, Modifier.fillMaxWidth())
        TvTextField(user, { user = it }, "Username (optional)", Modifier.fillMaxWidth())
        TvTextField(password, { password = it }, "Password (optional)", Modifier.fillMaxWidth(), password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { includeDevice = !includeDevice }) {
                Text(if (includeDevice) "✓ Also show music on this TV" else "Also show music on this TV")
            }
            Button(onClick = {
                path = NetworkFolder.DEFAULT_PATH
                lyricsFolder = NetworkFolder.DEFAULT_LYRICS_PATH
            }) { Text("Use default") }
            Button(onClick = {
                vm.updateNetworkFolder(NetworkFolder(path.trim(), user.trim(), password, includeDevice, lyricsFolder.trim()))
                onDone()
            }) { Text("Save & scan") }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvTextField(query, { query = it }, "Song or artist", Modifier.weight(1f), onSubmit = { vm.searchOnline(query) })
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
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: () -> Unit = {},
    password: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(Pink),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = ImeAction.Done,
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
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
