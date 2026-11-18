package com.alexdiaz.mailtriage

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class UiState(
    val accounts: List<MailAccount> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val emails: List<TriagedEmail> = emptyList(),
    val refreshing: Boolean = false,
    val status: String = "",
    val lastRefresh: Long = 0L
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Emails currently in the inbox, keyed for de-duplication. */
    private var cache: MutableMap<String, TriagedEmail> = mutableMapOf()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cache = store.loadCache().toMutableMap()
            _state.update {
                it.copy(
                    accounts = store.loadAccounts(),
                    settings = store.loadSettings(),
                    emails = sorted(cache.values),
                    lastRefresh = store.lastRefresh()
                )
            }
        }
    }

    private fun sorted(items: Collection<TriagedEmail>) =
        items.sortedWith(compareBy<TriagedEmail> { it.priority }.thenByDescending { it.dateMillis })

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
            val newOnes = fetched.filter { it.key !in cache }
            if (newOnes.isNotEmpty()) {
                _state.update { it.copy(status = "Summarizing ${newOnes.size} new emails…") }
                val triaged = withContext(Dispatchers.IO) {
                    if (settings.claudeApiKey.isBlank()) newOnes.map(Rules::classify)
                    else try {
                        ClaudeTriage(settings.claudeApiKey, settings.claudeModel).triage(newOnes)
                    } catch (e: Exception) {
                        errors += "Summaries: ${e.message}"
                        newOnes.map(Rules::classify)
                    }
                }
                triaged.forEach { cache[it.key] = it }
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
                    status = if (errors.isEmpty())
                        "${newOnes.size} new · $important important"
                    else "Some problems:\n" + errors.joinToString("\n")
                )
            }
        }
    }

    /** Lets you correct the category by hand (kept until the email ages out). */
    fun recategorize(email: TriagedEmail, category: Category) {
        val prio = when (category) {
            Category.IMPORTANT -> minOf(email.priority, 2)
            Category.ROUTINE -> 4
            Category.NOISE -> 5
        }
        cache[email.key] = email.copy(category = category, priority = prio)
        store.saveCache(cache.values)
        _state.update { it.copy(emails = sorted(cache.values)) }
    }
}
