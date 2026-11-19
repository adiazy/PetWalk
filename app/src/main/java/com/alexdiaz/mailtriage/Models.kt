package com.alexdiaz.mailtriage

import org.json.JSONObject

enum class Provider(val label: String, val host: String, val oauth: Boolean) {
    GMAIL("Gmail", "imap.gmail.com", false),
    OUTLOOK("Hotmail / Outlook", "outlook.office365.com", true),
    AOL("AOL", "imap.aol.com", false),
    YAHOO("Yahoo", "imap.mail.yahoo.com", false),
    OTHER("Other (IMAP)", "", false)
}

data class MailAccount(
    val id: String,
    val provider: Provider,
    val email: String,
    val host: String,
    val password: String = "",          // app password (non-OAuth providers)
    val authStateJson: String = ""      // AppAuth state (Outlook)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("provider", provider.name).put("email", email)
        .put("host", host).put("password", password).put("authState", authStateJson)

    companion object {
        fun fromJson(o: JSONObject) = MailAccount(
            id = o.getString("id"),
            provider = Provider.valueOf(o.getString("provider")),
            email = o.getString("email"),
            host = o.optString("host"),
            password = o.optString("password"),
            authStateJson = o.optString("authState")
        )
    }
}

enum class Category { IMPORTANT, ROUTINE, NOISE }

/** A raw email pulled over IMAP. */
data class RawEmail(
    val key: String,          // unique: accountId + UID
    val accountEmail: String,
    val from: String,
    val subject: String,
    val dateMillis: Long,
    val body: String,         // plain text, trimmed
    val unread: Boolean = false
)

/** An email after triage. */
data class TriagedEmail(
    val key: String,
    val accountEmail: String,
    val from: String,
    val subject: String,
    val dateMillis: Long,
    val category: Category,
    val priority: Int,        // 1 (highest) .. 5 (lowest)
    val summary: String,
    val aiSummarized: Boolean,
    val unread: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("account", accountEmail).put("from", from)
        .put("subject", subject).put("date", dateMillis).put("category", category.name)
        .put("priority", priority).put("summary", summary).put("ai", aiSummarized)
        .put("unread", unread)

    companion object {
        fun fromJson(o: JSONObject) = TriagedEmail(
            key = o.getString("key"),
            accountEmail = o.optString("account"),
            from = o.optString("from"),
            subject = o.optString("subject"),
            dateMillis = o.optLong("date"),
            category = runCatching { Category.valueOf(o.getString("category")) }
                .getOrDefault(Category.ROUTINE),
            priority = o.optInt("priority", 3),
            summary = o.optString("summary"),
            aiSummarized = o.optBoolean("ai"),
            unread = o.optBoolean("unread")
        )
    }
}

data class AppSettings(
    val claudeApiKey: String = "",
    val claudeModel: String = "claude-haiku-5-5",
    val azureClientId: String = "",
    val maxPerAccount: Int = 200,   // newest N emails in each inbox
    val autoLearn: Boolean = true,
    val learnAfter: Int = 2        // same manual action this many times -> rule kicks in
)
