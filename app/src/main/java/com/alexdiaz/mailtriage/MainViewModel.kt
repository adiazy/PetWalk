package com.alexdiaz.mailtriage

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ReaderState(
    val email: TriagedEmail,
    val loading: Boolean = true,
    val full: MailFetcher.FullEmail? = null,
    val error: String? = null,
    val folders: List<String>? = null,     // null = not loaded yet
    val foldersError: String? = null
)

data class UiState(
    val accounts: List<MailAccount> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val emails: List<TriagedEmail> = emptyList(),
    val refreshing: Boolean = false,
    val status: String = "",
    val lastRefresh: Long = 0L,
    val reader: ReaderState? = null,
    val rules: List<LearnedRule> = emptyList()
)

/** Emails the app handled on its own during a refresh (shown with an Undo bar). */
data class AutoEvent(val keys: List<String>, val message: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Emails currently in the inbox, keyed for de-duplication. */
    private var cache: MutableMap<String, TriagedEmail> = mutableMapOf()

    /** Learned rules keyed by sender|subject-pattern. */
    private var rules: MutableMap<String, LearnedRule> = mutableMapOf()

    private val _events = MutableSharedFlow<AutoEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AutoEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cache = store.loadCache().toMutableMap()
            rules = store.loadRules().toMutableMap()
            _state.update {
                it.copy(
                    accounts = store.loadAccounts(),
                    settings = store.loadSettings(),
                    emails = sorted(cache.values),
                    lastRefresh = store.lastRefresh(),
                    rules = sortedRules()
                )
            }
        }
    }

    private fun sorted(items: Collection<TriagedEmail>) =
        items.sortedWith(compareBy<TriagedEmail> { it.priority }.thenByDescending { it.dateMillis })


    // ---------- Shared helpers ----------
    private fun accountFor(key: String): MailAccount? =
        _state.value.accounts.firstOrNull { key.startsWith("${it.id}:") }

    private fun uidFor(key: String): Long = key.substringAfterLast(':').toLong()

    /** App password, or a fresh Microsoft token (saving the refreshed sign-in). */
    private suspend fun secretFor(acct: MailAccount): String {
        if (!acct.provider.oauth) return acct.password
        val (token, newState) = OutlookAuth.freshToken(getApplication(), acct.authStateJson)
        if (newState != acct.authStateJson) {
            saveAccounts(_state.value.accounts.map {
                if (it.id == acct.id) it.copy(authStateJson = newState) else it
            })
        }
        return token
    }

    // ---------- Reader ----------
    fun openEmail(e: TriagedEmail) {
        if (e.unread) {
            cache[e.key] = e.copy(unread = false)
            _state.update { it.copy(emails = sorted(cache.values)) }
            viewModelScope.launch(Dispatchers.IO) { store.saveCache(cache.values) }
        }
        _state.update { it.copy(reader = ReaderState(e.copy(unread = false))) }
        viewModelScope.launch {
            try {
                val acct = accountFor(e.key) ?: throw IllegalStateException("Account was removed")
                val secret = secretFor(acct)
                val full = withContext(Dispatchers.IO) {
                    MailFetcher.fetchFull(acct, secret, uidFor(e.key))
                }
                _state.update { st ->
                    if (st.reader?.email?.key == e.key)
                        st.copy(reader = st.reader!!.copy(loading = false, full = full)) else st
                }
            } catch (ex: Exception) {
                _state.update { st ->
                    if (st.reader?.email?.key == e.key)
                        st.copy(reader = st.reader!!.copy(loading = false, error = ex.message ?: "Couldn't open email"))
                    else st
                }
            }
        }
    }

    fun closeReader() = _state.update { it.copy(reader = null) }

    // ---------- Folders ----------
    private val folderCache = mutableMapOf<String, List<String>>()

    fun loadFolders() {
        val r = _state.value.reader ?: return
        val acct = accountFor(r.email.key) ?: return
        folderCache[acct.id]?.let { f ->
            _state.update { st -> st.copy(reader = st.reader?.copy(folders = f, foldersError = null)) }
            return
        }
        _state.update { st -> st.copy(reader = st.reader?.copy(folders = null, foldersError = null)) }
        viewModelScope.launch {
            try {
                val secret = secretFor(acct)
                val list = withContext(Dispatchers.IO) { MailFetcher.listFolders(acct, secret) }
                folderCache[acct.id] = list
                _state.update { st -> st.copy(reader = st.reader?.copy(folders = list)) }
            } catch (ex: Exception) {
                _state.update { st ->
                    st.copy(reader = st.reader?.copy(foldersError = ex.message ?: "Couldn't load folders"))
                }
            }
        }
    }

    // ---------- Delete / move (with a short undo window before touching the server) ----------
    private val pendingDeletes = mutableMapOf<String, Pair<TriagedEmail, Job>>()

    fun deleteEmail(e: TriagedEmail) = moveOut(e, null)

    fun moveEmail(e: TriagedEmail, folderFullName: String) = moveOut(e, folderFullName)

    /** folder = null means Trash. */
    private fun moveOut(
        e: TriagedEmail, folder: String?,
        auto: Boolean = false, undoMs: Long = UNDO_WINDOW_MS
    ) {
        cache.remove(e.key)
        _state.update { st ->
            st.copy(
                emails = sorted(cache.values),
                reader = if (st.reader?.email?.key == e.key) null else st.reader
            )
        }
        val job = viewModelScope.launch {
            delay(undoMs)
            try {
                val acct = accountFor(e.key) ?: return@launch
                val secret = secretFor(acct)
                withContext(Dispatchers.IO) {
                    if (folder == null) MailFetcher.moveToTrash(acct, secret, uidFor(e.key))
                    else MailFetcher.moveToFolder(acct, secret, uidFor(e.key), folder)
                }
                withContext(Dispatchers.IO) { store.saveCache(cache.values) }
                val action = if (folder == null) RuleAction.DELETE else RuleAction.MOVE
                if (auto) markApplied(e) else learn(e, action, folder = folder.orEmpty())
            } catch (ex: Exception) {
                cache[e.key] = e   // put it back so nothing silently disappears
                _state.update {
                    it.copy(emails = sorted(cache.values),
                        status = (if (folder == null) "Couldn't delete: " else "Couldn't move: ") + ex.message)
                }
            } finally {
                pendingDeletes.remove(e.key)
            }
        }
        pendingDeletes[e.key] = e to job
    }

    fun undoDelete(key: String) {
        val (e, job) = pendingDeletes.remove(key) ?: return
        job.cancel()
        cache[key] = e
        _state.update { it.copy(emails = sorted(cache.values)) }
    }

    companion object {
        const val UNDO_WINDOW_MS = 6000L
        const val AUTO_UNDO_WINDOW_MS = 12000L
    }

    // ---------- Settings ----------
    fun saveSettings(s: AppSettings) {
        store.saveSettings(s)
        _state.update { it.copy(settings = s, status = "Settings saved") }
    }

    // ---------- Accounts ----------
    fun addPasswordAccount(provider: Provider, email: String, password: String, host: String) {
        val acct = MailAccount(
            id = UUID.randomUUID().toString().take(8),
            provider = provider,
            email = email.trim(),
            host = host.trim().ifBlank { provider.host },
            password = password.replace(" ", "")   // app passwords are often shown with spaces
        )
        saveAccounts(_state.value.accounts + acct)
    }

    fun signInIntentFor(email: String): Intent? {
        val clientId = _state.value.settings.azureClientId
        if (clientId.isBlank()) {
            _state.update { it.copy(status = "Add your Azure client ID in Settings first") }
            return null
        }
        return OutlookAuth.signInIntent(getApplication(), clientId, email)
    }

    fun finishOutlookSignIn(email: String, data: Intent?) {
        if (data == null) {
            _state.update { it.copy(status = "Microsoft sign-in cancelled") }
            return
        }
        viewModelScope.launch {
            try {
                val authJson = OutlookAuth.completeSignIn(getApplication(), data)
                val acct = MailAccount(
                    id = UUID.randomUUID().toString().take(8),
                    provider = Provider.OUTLOOK,
                    email = email.trim(),
                    host = Provider.OUTLOOK.host,
                    authStateJson = authJson
                )
                saveAccounts(_state.value.accounts + acct)
                _state.update { it.copy(status = "Added ${acct.email}") }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Microsoft sign-in failed: ${e.message}") }
            }
        }
    }

    fun removeAccount(acct: MailAccount) {
        saveAccounts(_state.value.accounts.filterNot { it.id == acct.id })
        cache.keys.removeAll { it.startsWith("${acct.id}:") }
        store.saveCache(cache.values)
        _state.update { it.copy(emails = sorted(cache.values)) }
    }

    private fun saveAccounts(list: List<MailAccount>) {
        store.saveAccounts(list)
        _state.update { it.copy(accounts = list) }
    }

    // ---------- Refresh ----------
    fun refresh() {
        if (_state.value.refreshing) return
        val accounts = _state.value.accounts
        val settings = _state.value.settings
        if (accounts.isEmpty()) {
            _state.update { it.copy(status = "Add an account first") }
            return
        }
        _state.update { it.copy(refreshing = true, status = "Checking mail…") }

        viewModelScope.launch {
            val errors = mutableListOf<String>()
            val fetched = mutableListOf<RawEmail>()
            val updatedAccounts = accounts.toMutableList()

            for ((i, acct) in accounts.withIndex()) {
                _state.update { it.copy(status = "Checking ${acct.email}…") }
                try {
                    val secret = if (acct.provider.oauth) {
                        val (token, newState) = OutlookAuth.freshToken(getApplication(), acct.authStateJson)
                        updatedAccounts[i] = acct.copy(authStateJson = newState)
                        token
                    } else acct.password

                    fetched += withContext(Dispatchers.IO) {
                        MailFetcher.fetch(acct, secret, settings.daysBack, settings.maxPerAccount)
                    }
                } catch (e: Exception) {
                    errors += "${acct.email}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (updatedAccounts != accounts) saveAccounts(updatedAccounts)

            // Only send emails we haven't seen before to Claude.
            // Keep read/unread in sync for emails we already have.
            fetched.forEach { f -> cache[f.key]?.let { c -> if (c.unread != f.unread) cache[f.key] = c.copy(unread = f.unread) } }
            val newOnes = fetched.filter { it.key !in cache && it.key !in pendingDeletes }

            // Learned rules first: deletes/moves skip Claude entirely.
            val autoOut = mutableListOf<Pair<TriagedEmail, LearnedRule>>()
            val categoryRules = mutableMapOf<String, LearnedRule>()
            val toTriage = newOnes.filter { raw ->
                val r = activeRule(raw)
                when (r?.action) {
                    RuleAction.DELETE, RuleAction.MOVE -> { autoOut += Rules.classify(raw) to r!!; false }
                    RuleAction.CATEGORY -> { categoryRules[raw.key] = r!!; true }
                    null -> true
                }
            }

            if (toTriage.isNotEmpty()) {
                _state.update { it.copy(status = "Summarizing ${toTriage.size} new emails…") }
                val triaged = withContext(Dispatchers.IO) {
                    if (settings.claudeApiKey.isBlank()) toTriage.map(Rules::classify)
                    else try {
                        ClaudeTriage(settings.claudeApiKey, settings.claudeModel).triage(toTriage)
                    } catch (e: Exception) {
                        errors += "Summaries: ${e.message}"
                        toTriage.map(Rules::classify)
                    }
                }
                triaged.forEach { t ->
                    val r = categoryRules[t.key]
                    cache[t.key] = if (r != null) {
                        rules[r.key]?.let { cur -> rules[r.key] = cur.copy(applied = cur.applied + 1) }
                        t.copy(category = r.category, priority = priorityFor(r.category, t.priority))
                    } else t
                }
                if (categoryRules.isNotEmpty()) saveRules()
            }

            // Drop cached emails that are no longer in the fetched window,
            // but only for accounts that fetched successfully.
            val okAccountIds = accounts.map { it.id }.filter { id ->
                errors.none { err -> err.startsWith(accounts.first { it.id == id }.email) }
            }
            val fetchedKeys = fetched.map { it.key }.toSet()
            cache.keys.removeAll { key ->
                okAccountIds.any { key.startsWith("$it:") } && key !in fetchedKeys
            }

            withContext(Dispatchers.IO) { store.saveCache(cache.values) }
            val important = cache.values.count { it.category == Category.IMPORTANT }
            _state.update {
                it.copy(
                    emails = sorted(cache.values),
                    refreshing = false,
                    lastRefresh = System.currentTimeMillis(),
                    status = (if (errors.isEmpty())
                        "${newOnes.size} new · $important important"
                    else "Some problems:\n" + errors.joinToString("\n")) +
                        (if (autoOut.isNotEmpty()) " · ${autoOut.size} handled by your rules" else "")
                )
            }

            if (autoOut.isNotEmpty()) {
                autoOut.forEach { (e, r) ->
                    moveOut(e, if (r.action == RuleAction.DELETE) null else r.folder,
                        auto = true, undoMs = AUTO_UNDO_WINDOW_MS)
                }
                val deleted = autoOut.count { it.second.action == RuleAction.DELETE }
                val moved = autoOut.size - deleted
                val parts = listOfNotNull(
                    if (deleted > 0) "$deleted deleted" else null,
                    if (moved > 0) "$moved moved" else null
                )
                _events.tryEmit(AutoEvent(autoOut.map { it.first.key }, "Your rules: " + parts.joinToString(", ")))
            }
        }
    }

    /** Lets you correct the category by hand (kept until the email ages out). */
    fun recategorize(email: TriagedEmail, category: Category) {
        cache[email.key] = email.copy(category = category, priority = priorityFor(category, email.priority))
        store.saveCache(cache.values)
        _state.update { it.copy(emails = sorted(cache.values)) }
        learn(email, RuleAction.CATEGORY, category = category)
    }

    // ---------- Learning ----------
    private fun sortedRules() = rules.values.sortedWith(
        compareByDescending<LearnedRule> { it.count >= _state.value.settings.learnAfter }.thenByDescending { it.updated }
    )

    private fun saveRules() {
        val snapshot = rules.values.toList()
        viewModelScope.launch(Dispatchers.IO) { store.saveRules(snapshot) }
        _state.update { it.copy(rules = sortedRules()) }
    }

    /** Records something you did by hand. Same action again -> count goes up; different -> starts over. */
    private fun learn(e: TriagedEmail, action: RuleAction, folder: String = "", category: Category = Category.ROUTINE) {
        if (!_state.value.settings.autoLearn) return
        val candidate = LearnedRule(
            sender = Learning.senderOf(e.from),
            subjectPattern = Learning.patternOf(e.subject),
            accountId = e.key.substringBefore(':'),
            action = action, folder = folder, category = category
        )
        val existing = rules[candidate.key]
        rules[candidate.key] = if (existing != null && existing.sameActionAs(candidate))
            existing.copy(count = existing.count + 1, updated = System.currentTimeMillis())
        else candidate
        saveRules()
    }

    private fun markApplied(e: TriagedEmail) {
        val k = Learning.keyFor(e.from, e.subject)
        rules[k]?.let { rules[k] = it.copy(applied = it.applied + 1); saveRules() }
    }

    /** The rule to apply automatically to this email, if one is active. */
    private fun activeRule(raw: RawEmail): LearnedRule? {
        val st = _state.value.settings
        if (!st.autoLearn) return null
        val r = rules[Learning.keyFor(raw.from, raw.subject)] ?: return null
        if (!r.enabled || r.count < st.learnAfter) return null
        if (r.action == RuleAction.MOVE && r.accountId != raw.key.substringBefore(':')) return null
        return r
    }

    fun toggleRule(key: String) {
        rules[key]?.let { rules[key] = it.copy(enabled = !it.enabled); saveRules() }
    }

    fun removeRule(key: String) {
        if (rules.remove(key) != null) saveRules()
    }

    /** Undo for emails the app handled on its own: restores them and resets the rule. */
    fun undoAuto(keys: List<String>) {
        keys.forEach { key ->
            val e = pendingDeletes[key]?.first
            undoDelete(key)
            if (e != null) {
                val rk = Learning.keyFor(e.from, e.subject)
                rules[rk]?.let { rules[rk] = it.copy(count = 0, updated = System.currentTimeMillis()) }
            }
        }
        saveRules()
    }
}
