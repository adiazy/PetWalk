package com.petwalk.photosorter

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode(val label: String) { LOCATION("By location"), CONTENT("By content") }

data class Group(val name: String, val photos: List<Photo>)

data class UiState(
    val albums: List<Album> = emptyList(),
    val album: Album? = null,
    val mode: Mode = Mode.LOCATION,
    val granularity: Granularity = Granularity.CITY,
    val minConfidence: Float = 0.6f,
    val focusLabels: String = "",
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String? = null,
    val groups: List<Group> = emptyList(),
)

class SorterViewModel(app: Application) : AndroidViewModel(app) {
    val store = PhotoStore(app.contentResolver)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun loadAlbums() {
        viewModelScope.launch {
            val albums = withContext(Dispatchers.IO) { store.albums() }
            _state.update { s ->
                val keep = albums.firstOrNull { it.id == s.album?.id && s.album != null }
                val camera = albums.firstOrNull { it.name.equals("Camera", ignoreCase = true) }
                s.copy(albums = albums, album = keep ?: camera ?: albums.firstOrNull())
            }
        }
    }

    fun setAlbum(album: Album) = reset { it.copy(album = album) }
    fun setMode(mode: Mode) = reset { it.copy(mode = mode) }
    fun setGranularity(granularity: Granularity) = reset { it.copy(granularity = granularity) }
    fun setMinConfidence(value: Float) = reset { it.copy(minConfidence = value) }
    fun setFocusLabels(value: String) = reset { it.copy(focusLabels = value) }

    // Any settings change makes the current preview stale.
    private fun reset(change: (UiState) -> UiState) =
        _state.update { change(it).copy(groups = emptyList(), status = null) }

    fun analyze() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, groups = emptyList(), status = "Reading photos…") }
            val groups = withContext(Dispatchers.IO) {
                val photos = store.photos(s.album?.id)
                val byFolder = LinkedHashMap<String, MutableList<Photo>>()
                suspend fun assign(group: suspend (Photo) -> String) {
                    photos.forEachIndexed { i, photo ->
                        byFolder.getOrPut(group(photo)) { mutableListOf() }.add(photo)
                        _state.update {
                            it.copy(progress = (i + 1f) / photos.size, status = "Looking at photo ${i + 1} of ${photos.size}…")
                        }
                    }
                }
                when (s.mode) {
                    Mode.LOCATION -> {
                        val grouper = LocationGrouper(getApplication())
                        assign { grouper.group(store.location(it), s.granularity) }
                    }
                    Mode.CONTENT -> ContentGrouper(store, s.minConfidence, s.focusLabels.split(",")).use { grouper ->
                        assign { grouper.group(it) }
                    }
                }
                byFolder.map { (name, list) -> Group(name, list) }
                    .sortedWith(compareBy<Group> { it.name in Folders.special }.thenByDescending { it.photos.size })
            }
            val total = groups.sumOf { it.photos.size }
            _state.update {
                it.copy(
                    busy = false,
                    groups = groups,
                    status = if (total == 0) "No photos in this album." else "$total photos → ${groups.size} albums. Nothing has been changed yet.",
                )
            }
        }
    }

    fun copyAll() = transfer("Copied") { photo, folder -> store.copy(photo, folder) }

    fun moveAll() = transfer("Moved") { photo, folder -> store.move(photo, folder) }

    fun writeRequest(): PendingIntent? = try {
        store.writeRequest(_state.value.groups.flatMap { it.photos })
    } catch (e: Exception) {
        _state.update { it.copy(status = "Couldn't ask for permission to move: ${e.message}") }
        null
    }

    private fun transfer(verb: String, action: (Photo, String) -> Boolean) {
        val groups = _state.value.groups
        if (groups.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f) }
            val total = groups.sumOf { it.photos.size }
            var done = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (group in groups) {
                    for (photo in group.photos) {
                        if (!action(photo, group.name)) failed++
                        done++
                        _state.update { it.copy(progress = done.toFloat() / total, status = "$verb $done of $total…") }
                    }
                }
            }
            val failNote = if (failed > 0) " $failed couldn't be processed." else ""
            _state.update {
                it.copy(
                    busy = false,
                    groups = emptyList(),
                    status = "$verb ${total - failed} photos into ${PhotoStore.OUTPUT_DIR}. " +
                        "Open your gallery app to see the new albums.$failNote",
                )
            }
            loadAlbums()
        }
    }
}
