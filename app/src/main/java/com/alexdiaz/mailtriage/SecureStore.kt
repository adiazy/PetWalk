package com.alexdiaz.mailtriage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Everything sensitive (app passwords, Microsoft tokens, Claude API key, cached
 * summaries) is stored encrypted with a key held in the Android Keystore.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "mailtriage_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ---- Accounts ----
    fun loadAccounts(): List<MailAccount> {
        val raw = prefs.getString("accounts", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { MailAccount.fromJson(arr.getJSONObject(it)) }
    }

    fun saveAccounts(list: List<MailAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    // ---- Settings ----
    fun loadSettings() = AppSettings(
        claudeApiKey = prefs.getString("claudeKey", "") ?: "",
        claudeModel = prefs.getString("claudeModel", "claude-haiku-5-5") ?: "claude-haiku-5-5",
        azureClientId = prefs.getString("azureClientId", "") ?: "",
        maxPerAccount = prefs.getInt("perAccount", 200),
        autoLearn = prefs.getBoolean("autoLearn", true),
        learnAfter = prefs.getInt("learnAfter", 2)
    )

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString("claudeKey", s.claudeApiKey.trim())
            .putString("claudeModel", s.claudeModel.trim())
            .putString("azureClientId", s.azureClientId.trim())
            .putInt("perAccount", s.maxPerAccount)
            .putBoolean("autoLearn", s.autoLearn)
            .putInt("learnAfter", s.learnAfter)
            .apply()
    }

    // ---- Cache of already-triaged emails (so we never pay to summarize twice) ----
    fun loadCache(): Map<String, TriagedEmail> {
        val arr = JSONArray(prefs.getString("cache", "[]") ?: "[]")
        return (0 until arr.length())
            .map { TriagedEmail.fromJson(arr.getJSONObject(it)) }
            .associateBy { it.key }
    }

    fun saveCache(items: Collection<TriagedEmail>) {
        val arr = JSONArray()
        items.sortedByDescending { it.dateMillis }.take(1000).forEach { arr.put(it.toJson()) }
        prefs.edit().putString("cache", arr.toString())
            .putLong("lastRefresh", System.currentTimeMillis()).apply()
    }

    // ---- Learned rules ----
    fun loadRules(): Map<String, LearnedRule> {
        val arr = JSONArray(prefs.getString("rules", "[]") ?: "[]")
        return (0 until arr.length())
            .mapNotNull { runCatching { LearnedRule.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .associateBy { it.key }
    }

    fun saveRules(rules: Collection<LearnedRule>) {
        val arr = JSONArray()
        rules.sortedByDescending { it.updated }.take(2000).forEach { arr.put(it.toJson()) }
        prefs.edit().putString("rules", arr.toString()).apply()
    }

    fun lastRefresh(): Long = prefs.getLong("lastRefresh", 0L)
}
