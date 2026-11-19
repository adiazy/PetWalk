package com.alexdiaz.mailtriage

import org.json.JSONObject

enum class RuleAction { DELETE, MOVE, CATEGORY }

/**
 * Something the app learned from what you did by hand, keyed by sender address +
 * subject pattern (numbers ignored, so "Order #123 shipped" matches "Order #456 shipped").
 */
data class LearnedRule(
    val sender: String,
    val subjectPattern: String,
    val accountId: String,
    val action: RuleAction,
    val folder: String = "",
    val category: Category = Category.ROUTINE,
    val count: Int = 1,          // times you did this by hand in a row
    val applied: Int = 0,        // times the app did it for you
    val enabled: Boolean = true,
    val updated: Long = System.currentTimeMillis()
) {
    val key: String get() = "$sender|$subjectPattern"

    fun sameActionAs(other: LearnedRule) =
        action == other.action && folder == other.folder && category == other.category &&
            (action != RuleAction.MOVE || accountId == other.accountId)

    fun describe(): String = when (action) {
        RuleAction.DELETE -> "Delete"
        RuleAction.MOVE -> "Move to ${folderLabel(folder)}"
        RuleAction.CATEGORY -> "Mark as ${category.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sender", sender).put("pattern", subjectPattern).put("account", accountId)
        .put("action", action.name).put("folder", folder).put("category", category.name)
        .put("count", count).put("applied", applied).put("enabled", enabled).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject) = LearnedRule(
            sender = o.getString("sender"),
            subjectPattern = o.getString("pattern"),
            accountId = o.optString("account"),
            action = RuleAction.valueOf(o.getString("action")),
            folder = o.optString("folder"),
            category = runCatching { Category.valueOf(o.getString("category")) }.getOrDefault(Category.ROUTINE),
            count = o.optInt("count", 1),
            applied = o.optInt("applied", 0),
            enabled = o.optBoolean("enabled", true),
            updated = o.optLong("updated")
        )
    }
}

object Learning {
    private val angle = Regex("<([^>]+)>")
    private val replyPrefix = Regex("^(re|fw|fwd|rv|tr)\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun senderOf(from: String): String =
        (angle.find(from)?.groupValues?.get(1) ?: from).trim().lowercase()

    fun patternOf(subject: String): String {
        var s = subject.trim()
        repeat(4) { s = s.replace(replyPrefix, "") }
        return s.lowercase()
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun keyFor(from: String, subject: String) = "${senderOf(from)}|${patternOf(subject)}"
}

fun folderLabel(full: String) = when {
    full.equals("[Gmail]/All Mail", true) || full.equals("[Google Mail]/All Mail", true) -> "All Mail (archive)"
    else -> full.removePrefix("[Gmail]/").removePrefix("[Google Mail]/").removePrefix("INBOX.")
}

fun priorityFor(category: Category, current: Int) = when (category) {
    Category.IMPORTANT -> minOf(current, 2)
    Category.ROUTINE -> 4
    Category.NOISE -> 5
}
