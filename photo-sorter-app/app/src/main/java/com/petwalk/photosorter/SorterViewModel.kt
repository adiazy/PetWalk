package com.petwalk.photosorter

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    val folder: Uri? = null,
    val folderName: String? = null,
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

    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Reopen the last folder, as long as Android still lets us access it.
        val saved = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        if (saved != null && resolver.persistedUriPermissions.any { it.uri == saved }) {
            showFolder(saved)
        }
    }

    /** Called with the folder the user picked in the system folder picker. */
    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Give up access to every other folder, so the app only ever holds the current one.
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            try {
                resolver.releasePersistableUriPermission(it.uri, flags)
            } catch (ignored: SecurityException) {
            }
        }
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            _state.update { it.copy(status = "Android didn't allow access to that folder. Try another one.") }
            return
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        showFolder(uri)
    }

    private fun showFolder(uri: Uri) {
        reset { it.copy(folder = uri, folderName = null) }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { store.folderName(uri) }
            _state.update { if (it.folder == uri) it.copy(folderName = name ?: "Selected folder") else it }
        }
    }

    fun setMode(mode: Mode) = reset { it.copy(mode = mode) }
    fun setGranularity(granularity: Granularity) = reset { it.copy(granularity = granularity) }
    fun setMinConfidence(value: Float) = reset { it.copy(minConfidence = value) }
    fun setFocusLabels(value: String) = reset { it.copy(focusLabels = value) }

    // Any settings change makes the current preview stale.
    private fun reset(change: (UiState) -> UiState) =
        _state.update { change(it).copy(groups = emptyList(), status = null) }

    fun analyze() {
        val s = _state.value
        val folder = s.folder ?: return
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, groups = emptyList(), status = "Reading folder…") }
            val groups = withContext(Dispatchers.IO) {
                val photos = store.photos(folder)
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
                    status = if (total == 0) "No photos in this folder." else "$total photos → ${groups.size} albums. Nothing has been changed yet.",
                )
            }
        }
    }

    fun copyAll() = transfer("Copied") { folder, photo, group -> store.copy(folder, photo, group) }

    fun moveAll() = transfer("Moved") { folder, photo, group -> store.move(folder, photo, group) }

    private fun transfer(verb: String, action: (Uri, Photo, String) -> Boolean) {
        val s = _state.value
        val folder = s.folder ?: return
        if (s.groups.isEmpty() || s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f) }
            val total = s.groups.sumOf { it.photos.size }
            var done = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                store.resetFolders()
                for (group in s.groups) {
                    for (photo in group.photos) {
                        if (!action(folder, photo, group.name)) failed++
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
                    status = "$verb ${total - failed} photos into ${it.folderName}/${PhotoStore.OUTPUT_DIR}.$failNote",
                )
            }
        }
    }

    private companion object {
        const val KEY_FOLDER = "folder"
    }
}
