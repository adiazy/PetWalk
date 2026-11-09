package com.karaoketv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.karaoketv.KaraokeViewModel
import com.karaoketv.core.library.AlbumNode
import com.karaoketv.core.library.ArtistNode
import com.karaoketv.core.library.LibraryTree
import com.karaoketv.media.Song
import kotlinx.coroutines.launch

/**
 * My music as image menus: artist pictures → album covers → the album's songs.
 * Back goes up one level and puts focus on the picture you came from. Artists with a single
 * album go straight to its songs.
 */
@Composable
fun LibraryBrowser(vm: KaraokeViewModel) {
    val tree = remember(vm.library) { LibraryTree.build(vm.library) { it.treeInfo() } }
    val artist = vm.browseArtist?.let { name -> tree.firstOrNull { it.name == name } }
    val album = artist?.let { a -> vm.browseAlbum?.let { name -> a.albums.firstOrNull { it.name == name } } }

    // Names can change while tags are being read; if the picked artist/album is gone, go up.
    LaunchedEffect(artist == null && vm.browseArtist != null) {
        if (artist == null && vm.browseArtist != null) {
            vm.browseArtist = null
            vm.browseAlbum = null
        }
    }

    BackHandler(enabled = artist != null) {
        if (album != null && artist!!.albums.size > 1) {
            vm.browseAlbum = null
        } else {
            vm.browseAlbum = null
            vm.browseArtist = null
        }
        vm.libraryFocusRequest = true
    }

    Column {
        Text(
            listOfNotNull("Artists", artist?.name, album?.name.takeIf { artist != null && artist.albums.size > 1 })
                .joinToString("  ›  "),
            style = MaterialTheme.typography.titleMedium,
            color = Pink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when {
            artist == null -> ArtistGrid(vm, tree)
            album == null -> AlbumGrid(vm, artist)
            else -> SongList(vm, artist, album)
        }
    }
}

@Composable
private fun ArtistGrid(vm: KaraokeViewModel, tree: List<ArtistNode<Song>>) {
    val scope = rememberCoroutineScope()
    val keys = remember(tree) { tree.map { artistKey(it.name) } }
    val focus = levelFocus(vm, keys, vm.focusedArtist) { index -> vm.artistGridState.reveal(index) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_WIDTH),
        state = vm.artistGridState,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(tree, key = { artistKey(it.name) }) { node ->
            val key = artistKey(node.name)
            val albums = node.albums.size
            ArtTile(
                title = node.name,
                subtitle = (if (albums > 1) "$albums albums · " else "") + songs(node.songCount),
                imageKey = key,
                load = { vm.art.artist(node) },
                modifier = Modifier
                    .focusRequester(focus(key))
                    .onFocusChanged { if (it.isFocused) vm.focusedArtist = key },
                onClick = {
                    vm.browseArtist = node.name
                    vm.browseAlbum = node.albums.singleOrNull()?.name
                    vm.focusedAlbum = null
                    vm.focusedSong = null
                    vm.libraryFocusRequest = true
                    scope.launch {
                        vm.albumGridState.scrollToItem(0)
                        vm.songListState.scrollToItem(0)
                    }
                },
            )
        }
    }
}

@Composable
private fun AlbumGrid(vm: KaraokeViewModel, artist: ArtistNode<Song>) {
    val scope = rememberCoroutineScope()
    val keys = remember(artist) { artist.albums.map { albumKey(it.name) } }
    val focus = levelFocus(vm, keys, vm.focusedAlbum) { index -> vm.albumGridState.reveal(index) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_WIDTH),
        state = vm.albumGridState,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(artist.albums, key = { albumKey(it.name) }) { node ->
            val key = albumKey(node.name)
            ArtTile(
                title = node.name,
                subtitle = listOfNotNull(node.year?.toString(), songs(node.songs.size)).joinToString(" · "),
                imageKey = "album:${artist.name}/${node.name}",
                load = { vm.art.album(artist.name, node) },
                modifier = Modifier
                    .focusRequester(focus(key))
                    .onFocusChanged { if (it.isFocused) vm.focusedAlbum = key },
                onClick = {
                    vm.browseAlbum = node.name
                    vm.focusedSong = null
                    vm.libraryFocusRequest = true
                    scope.launch { vm.songListState.scrollToItem(0) }
                },
            )
        }
    }
}

@Composable
private fun SongList(vm: KaraokeViewModel, artist: ArtistNode<Song>, album: AlbumNode<Song>) {
    val keys = remember(album) { album.songs.map { it.id } }
    val focus = levelFocus(vm, keys, vm.focusedSong) { index ->
        if (vm.songListState.layoutInfo.visibleItemsInfo.none { it.index == index }) vm.songListState.scrollToItem(index)
    }
    val cover by produceState<ImageBitmap?>(null, artist.name, album.name) { value = vm.art.album(artist.name, album) }

    Row {
        Column(Modifier.width(220.dp)) {
            CoverBox(cover, album.name, Modifier.size(220.dp), initialsSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist.name, style = MaterialTheme.typography.bodyMedium, color = Pink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.year?.toString(), songs(album.songs.size)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(24.dp))
        LazyColumn(
            state = vm.songListState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(album.songs, key = { it.id }) { song ->
                CompactRow(
                    onClick = { vm.play(song) },
                    modifier = Modifier
                        .focusRequester(focus(song.id))
                        .onFocusChanged { if (it.isFocused) vm.focusedSong = song.id },
                ) {
                    Text(
                        song.track?.toString() ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (song.hasLyricsFile) Text("♪  ", color = Gold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (song.durationMs != null) formatTime(song.durationMs) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** A picture tile with a title and subtitle underneath. The picture loads in the background. */
@Composable
private fun ArtTile(
    title: String,
    subtitle: String,
    imageKey: String,
    load: suspend () -> ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(null, imageKey) { value = load() }
    Card(onClick = onClick, modifier = modifier) {
        CoverBox(image, title, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The picture, or the name's initials on a colored background while there is none. */
@Composable
private fun CoverBox(image: ImageBitmap?, name: String, modifier: Modifier, initialsSize: TextUnit = 26.sp) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(placeholderBrush(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initials(name), fontSize = initialsSize, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/**
 * Focus for one menu level: when the menu asked for focus (after navigating or returning from a
 * song), focus the item used last on this level, or the first one.
 */
@Composable
private fun levelFocus(
    vm: KaraokeViewModel,
    keys: List<String>,
    saved: String?,
    reveal: suspend (Int) -> Unit,
): (String) -> FocusRequester {
    val requesters = remember { HashMap<String, FocusRequester>() }
    LaunchedEffect(Unit) {
        if (!vm.libraryFocusRequest) return@LaunchedEffect
        vm.libraryFocusRequest = false
        val key = saved?.takeIf { it in keys } ?: keys.firstOrNull() ?: return@LaunchedEffect
        reveal(keys.indexOf(key))
        withFrameNanos { } // let the item be laid out before focusing it
        runCatching { requesters.getOrPut(key) { FocusRequester() }.requestFocus() }
    }
    return { key -> requesters.getOrPut(key) { FocusRequester() } }
}

private suspend fun LazyGridState.reveal(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}

private fun artistKey(name: String) = "artist:$name"
private fun albumKey(name: String) = "album:$name"
private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun initials(name: String) =
    name.split(' ', '-', '&').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }

private val PLACEHOLDER_COLORS = listOf(
    Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF2E7D32),
    Color(0xFFEF6C00), Color(0xFF283593), Color(0xFF4E342E), Color(0xFF00695C),
)

private fun placeholderBrush(name: String): Brush {
    val color = PLACEHOLDER_COLORS[Math.floorMod(name.hashCode(), PLACEHOLDER_COLORS.size)]
    return Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))
}

private val TILE_WIDTH = 110.dp // 65% of the original 170dp
private val TILE_GAP = 13.dp
