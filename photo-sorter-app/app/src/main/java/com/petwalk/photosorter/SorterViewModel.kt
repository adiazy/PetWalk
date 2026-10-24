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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

enum class Mode(val label: String) { LOCATION("By location"), CONTENT("By content"), FAMILY("Family") }

enum class Engine(val label: String) { ON_DEVICE("On this phone"), CLAUDE("Claude (cloud)") }

data class Group(val name: String, val photos: List<Photo>) {
    /** Shown in the preview, but never copied or moved (e.g. "Not family"). */
    val skipped get() = name in Folders.skipped
}

/** What the screen shows about a family member. */
data class FamilySummary(val name: String, val photos: Int)

data class UiState(
    val folder: Uri? = null,
    val folderName: String? = null,
    val mode: Mode = Mode.LOCATION,
    val granularity: Granularity = Granularity.CITY,
    val engine: Engine = Engine.ON_DEVICE,
    val categories: String = DEFAULT_CATEGORIES,
    val minConfidence: Float = 0.3f,
    val apiKey: String = "",
    val claudeModel: ClaudeModel = ClaudeModel.OPUS,
    val family: List<FamilySummary> = emptyList(),
    val newMemberName: String = "",
    val familyThreshold: Float = 0.40f,
    val familyPerPerson: Boolean = false,
    val limit: String = "", // empty = all photos
    val skipSorted: Boolean = true,
    val oldestFirst: Boolean = true,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String? = null,
    val groups: List<Group> = emptyList(),
)

const val DEFAULT_CATEGORIES = "Pets, People, Food, Nature, City, Documents, Screenshots"

class SorterViewModel(app: Application) : AndroidViewModel(app) {
    val store = PhotoStore(app.contentResolver)

    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val familyStore = FamilyStore(app)
    private var familyMembers: List<FamilyMember> = emptyList()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                engine = enumOrDefault(prefs.getString(KEY_ENGINE, null), Engine.ON_DEVICE),
                categories = prefs.getString(KEY_CATEGORIES, null) ?: DEFAULT_CATEGORIES,
                apiKey = prefs.getString(KEY_API_KEY, null) ?: "",
                claudeModel = enumOrDefault(prefs.getString(KEY_MODEL, null), ClaudeModel.OPUS),
                familyThreshold = prefs.getFloat(KEY_FAMILY_THRESHOLD, 0.40f),
                familyPerPerson = prefs.getBoolean(KEY_FAMILY_PER_PERSON, false),
            )
        }
        viewModelScope.launch {
            familyMembers = withContext(Dispatchers.IO) { familyStore.load() }
            publishFamily()
        }
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
    fun setEngine(value: Engine) {
        prefs.edit().putString(KEY_ENGINE, value.name).apply()
        reset { it.copy(engine = value) }
    }
    fun setCategories(value: String) {
        prefs.edit().putString(KEY_CATEGORIES, value).apply()
        reset { it.copy(categories = value) }
    }
    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        reset { it.copy(apiKey = value.trim()) }
    }
    fun setNewMemberName(value: String) = _state.update { it.copy(newMemberName = value) }
    fun setFamilyThreshold(value: Float) {
        prefs.edit().putFloat(KEY_FAMILY_THRESHOLD, value).apply()
        reset { it.copy(familyThreshold = value) }
    }
    fun setFamilyPerPerson(value: Boolean) {
        prefs.edit().putBoolean(KEY_FAMILY_PER_PERSON, value).apply()
        reset { it.copy(familyPerPerson = value) }
    }

    /** Learns the face of the person named in the name field from the photos they picked. */
    fun addFamilyMember(photos: List<Uri>) {
        val name = _state.value.newMemberName.trim()
        if (photos.isEmpty() || name.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, status = "Learning $name's face…") }
            val faces = try {
                withContext(Dispatchers.IO) {
                    FaceEngine(getApplication()).use { engine ->
                        photos.mapIndexedNotNull { i, uri ->
                            val face = store.bitmap(uri, FamilyClassifier.DETECT_SIDE)?.let { engine.faces(it).firstOrNull() }
                            _state.update { it.copy(progress = (i + 1f) / photos.size) }
                            face
                        }
                    }
                }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, status = "The face recognition model isn't included in this build.") }
                return@launch
            }
            if (faces.isEmpty()) {
                _state.update {
                    it.copy(busy = false, status = "No clear face found in those photos. Pick close-ups where $name's face is clearly visible.")
                }
                return@launch
            }
            val existing = familyMembers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val updated = FamilyMember(existing?.name ?: name, existing?.faces.orEmpty() + faces)
            familyMembers = familyMembers.filter { it !== existing } + updated
            withContext(Dispatchers.IO) { familyStore.save(familyMembers) }
            publishFamily()
            val missed = photos.size - faces.size
            val missedNote = if (missed > 0) " ($missed had no clear face and were skipped)" else ""
            reset { it.copy(busy = false, newMemberName = "") }
            _state.update { it.copy(status = "Learned ${updated.name}'s face from ${faces.size} photos$missedNote.") }
        }
    }

    fun removeFamilyMember(name: String) {
        familyMembers = familyMembers.filter { it.name != name }
        val snapshot = familyMembers
        viewModelScope.launch(Dispatchers.IO) { familyStore.save(snapshot) }
        publishFamily()
        reset { it }
    }

    private fun publishFamily() =
        _state.update { s -> s.copy(family = familyMembers.map { FamilySummary(it.name, it.faces.size) }) }

    fun setClaudeModel(value: ClaudeModel) {
        prefs.edit().putString(KEY_MODEL, value.name).apply()
        reset { it.copy(claudeModel = value) }
    }
    fun setLimit(value: String) = reset { it.copy(limit = value.filter(Char::isDigit).take(6)) }
    fun setSkipSorted(value: Boolean) = reset { it.copy(skipSorted = value) }
    fun setOldestFirst(value: Boolean) = reset { it.copy(oldestFirst = value) }

    // Any settings change makes the current preview stale.
    private fun reset(change: (UiState) -> UiState) =
        _state.update { change(it).copy(groups = emptyList(), status = null) }

    fun analyze() {
        val s = _state.value
        val folder = s.folder ?: return
        if (s.busy) return
        val labels = s.categories.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (s.mode == Mode.FAMILY && familyMembers.isEmpty()) {
            _state.update { it.copy(status = "Add at least one family member first.") }
            return
        }
        if (s.mode == Mode.CONTENT) {
            val problem = when {
                labels.isEmpty() -> "Add at least one category."
                s.engine == Engine.CLAUDE && s.apiKey.isBlank() -> "Enter your Claude API key first."
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(status = problem) }
                return
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, groups = emptyList(), status = "Reading folder…") }
            var summary = ""
            val groups = try {
                withContext(Dispatchers.IO) { group(s, folder, labels) { summary = it } }
            } catch (e: ClaudeAccountException) {
                _state.update { it.copy(busy = false, status = e.message) }
                return@launch
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, status = "Couldn't prepare the recognition model: ${e.message}") }
                return@launch
            }
            val total = groups.sumOf { it.photos.size }
            _state.update {
                it.copy(
                    busy = false,
                    groups = groups,
                    status = when {
                        total == 0 -> "$summary Nothing to sort."
                        groups.all { g -> g.skipped } -> "$summary No family members found in these photos."
                        else -> "$summary ${groups.count { g -> !g.skipped }} albums. Nothing has been changed yet."
                    },
                )
            }
        }
    }

    private suspend fun group(s: UiState, folder: Uri, labels: List<String>, onSummary: (String) -> Unit): List<Group> {
        var candidates = store.photos(folder).let { if (s.oldestFirst) it else it.asReversed() }
        val inFolder = candidates.size
        if (s.skipSorted) {
            val done = store.sortedNames(folder)
            candidates = candidates.filter { it.name !in done }
        }
        val limit = s.limit.toIntOrNull()?.takeIf { it > 0 }
        val photos = if (limit != null) candidates.take(limit) else candidates
        val summary = buildString {
            append("Reviewed ${photos.size} of $inFolder photos in the folder")
            if (limit != null) append(if (s.oldestFirst) " (oldest first)" else " (newest first)")
            val skipped = inFolder - candidates.size
            if (skipped > 0) append(", skipping $skipped already sorted")
            val left = candidates.size - photos.size
            if (left > 0) append(". $left still to go")
            append(".")
        }
        onSummary(summary)
        val results = arrayOfNulls<String>(photos.size)
        // Runs [classify] on every photo, [parallelism] at a time, updating progress as it goes.
        suspend fun assign(parallelism: Int, classify: (Photo) -> String) = coroutineScope {
            val gate = Semaphore(parallelism)
            val done = AtomicInteger()
            photos.forEachIndexed { i, photo ->
                launch {
                    gate.withPermit { results[i] = classify(photo) }
                    val n = done.incrementAndGet()
                    _state.update {
                        it.copy(progress = n.toFloat() / photos.size, status = "Looking at photo $n of ${photos.size}…")
                    }
                }
            }
        }
        when (s.mode) {
            Mode.LOCATION -> {
                val grouper = LocationGrouper(getApplication())
                assign(1) { grouper.group(store.location(it), s.granularity) }
            }
            Mode.CONTENT -> when (s.engine) {
                Engine.ON_DEVICE -> {
                    val app = getApplication<Application>()
                    if (!ClipFiles.isReady(app)) {
                        val status = if (ClipFiles.isBundled(app)) {
                            "Unpacking the recognition model (one time only)…"
                        } else {
                            "Downloading the recognition model (one time, about 150 MB)…"
                        }
                        _state.update { it.copy(status = status) }
                        ClipFiles.prepare(app) { p -> _state.update { it.copy(progress = p) } }
                    }
                    _state.update { it.copy(progress = 0f, status = "Preparing categories…") }
                    ClipClassifier(app, store, labels, s.minConfidence).use { clip -> assign(1) { clip.classify(it) } }
                }
                Engine.CLAUDE -> ClaudeClassifier(s.apiKey, s.claudeModel, store, labels).use { claude ->
                    assign(CLAUDE_PARALLELISM) { claude.classify(it) }
                }
            }
            Mode.FAMILY -> {
                _state.update { it.copy(status = "Loading face recognition…") }
                FamilyClassifier(getApplication(), store, familyMembers, s.familyThreshold, s.familyPerPerson).use { family ->
                    assign(1) { family.classify(it) }
                }
            }
        }
        val byFolder = LinkedHashMap<String, MutableList<Photo>>()
        photos.forEachIndexed { i, photo -> byFolder.getOrPut(results[i] ?: Folders.NOT_CLASSIFIED) { mutableListOf() }.add(photo) }
        return byFolder.map { (name, list) -> Group(name, list) }
            .sortedWith(compareBy<Group> { it.name in Folders.special }.thenByDescending { it.photos.size })
    }

    fun copyAll() = transfer("Copied") { folder, photo, group -> store.copy(folder, photo, group) }

    fun moveAll() = transfer("Moved") { folder, photo, group -> store.move(folder, photo, group) }

    private fun transfer(verb: String, action: (Uri, Photo, String) -> Boolean) {
        val s = _state.value
        val folder = s.folder ?: return
        val groups = s.groups.filterNot { it.skipped }
        if (groups.isEmpty() || s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f) }
            val total = groups.sumOf { it.photos.size }
            var done = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                store.resetFolders()
                for (group in groups) {
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
        const val KEY_ENGINE = "engine"
        const val KEY_CATEGORIES = "categories"
        const val KEY_API_KEY = "claude_api_key"
        const val KEY_MODEL = "claude_model"
        const val KEY_FAMILY_THRESHOLD = "family_threshold"
        const val KEY_FAMILY_PER_PERSON = "family_per_person"
        const val CLAUDE_PARALLELISM = 4

        inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
