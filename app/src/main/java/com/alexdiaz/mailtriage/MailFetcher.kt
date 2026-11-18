package com.alexdiaz.mailtriage

import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Flags
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Reads recent INBOX messages over IMAP (read-only: it never moves, deletes or
 * marks anything on the server).
 */
object MailFetcher {

    /**
     * @param secret app password, or an OAuth access token for Outlook (XOAUTH2).
     */
    fun fetch(
        account: MailAccount,
        secret: String,
        daysBack: Int,
        maxMessages: Int
    ): List<RawEmail> {
        val store = connect(account, secret)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            try {
                val since = Date(System.currentTimeMillis() - daysBack * 24L * 3600_000L)
                val messages = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, since))
                    .sortedByDescending { it.receivedDate ?: it.sentDate ?: Date(0) }
                    .take(maxMessages)

                return messages.mapNotNull { msg ->
                    runCatching {
                        RawEmail(
                            key = "${account.id}:${inbox.getUID(msg)}",
                            accountEmail = account.email,
                            from = formatFrom(msg.from),
                            subject = msg.subject ?: "(no subject)",
                            dateMillis = (msg.receivedDate ?: msg.sentDate ?: Date()).time,
                            body = extractText(msg).take(2000),
                            unread = !msg.isSet(Flags.Flag.SEEN)
                        )
                    }.getOrNull()
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
    }


    /** Full content of one message, for the reader screen. */
    data class FullEmail(
        val from: String,
        val to: String,
        val subject: String,
        val dateMillis: Long,
        val html: String?,
        val text: String
    )

    /** Reads the full message and marks it as read on the server. */
    fun fetchFull(account: MailAccount, secret: String, uid: Long): FullEmail {
        val store = connect(account, secret)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_WRITE)
            try {
                val msg = inbox.getMessageByUID(uid)
                    ?: throw IllegalStateException("This email is no longer in the inbox")
                runCatching { msg.setFlag(Flags.Flag.SEEN, true) }
                return FullEmail(
                    from = formatFrom(msg.from),
                    to = msg.getRecipients(Message.RecipientType.TO)
                        ?.joinToString(", ") { formatFrom(arrayOf(it)) } ?: "",
                    subject = msg.subject ?: "(no subject)",
                    dateMillis = (msg.receivedDate ?: msg.sentDate ?: Date()).time,
                    html = findPart(msg, "text/html"),
                    text = findPart(msg, "text/plain") ?: ""
                )
            } finally {
                inbox.close(false)
            }
        } finally {
            store.close()
        }
    }

    /**
     * Moves one message from INBOX to the account's Trash folder (recoverable there
     * for the provider's usual period, e.g. 30 days in Gmail).
     */
    fun moveToTrash(account: MailAccount, secret: String, uid: Long) =
        move(account, secret, uid) { store ->
            findTrash(store) ?: throw IllegalStateException("Couldn't find the Trash folder")
        }

    /** Moves one message from INBOX to the folder with this full name. */
    fun moveToFolder(account: MailAccount, secret: String, uid: Long, folderFullName: String) =
        move(account, secret, uid) { store ->
            store.getFolder(folderFullName).takeIf { it.exists() }
                ?: throw IllegalStateException("Folder \"$folderFullName\" no longer exists")
        }

    private fun move(account: MailAccount, secret: String, uid: Long, target: (Store) -> Folder) {
        val store = connect(account, secret)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_WRITE)
            try {
                val msg = inbox.getMessageByUID(uid) ?: return   // already gone
                val dest = target(store)
                try {
                    inbox.moveMessages(arrayOf(msg), dest)        // IMAP MOVE
                } catch (e: Exception) {
                    inbox.copyMessages(arrayOf(msg), dest)        // older servers
                    msg.setFlag(Flags.Flag.DELETED, true)
                    inbox.expunge(arrayOf(msg))
                }
            } finally {
                if (inbox.isOpen) inbox.close(false)
            }
        } finally {
            store.close()
        }
    }

    /** Folders (Gmail: labels) a message can be moved into, excluding the inbox itself. */
    fun listFolders(account: MailAccount, secret: String): List<String> {
        val store = connect(account, secret)
        try {
            return store.defaultFolder.list("*")
                .filter { f ->
                    !f.fullName.equals("INBOX", ignoreCase = true) &&
                        runCatching { (f.type and Folder.HOLDS_MESSAGES) != 0 }.getOrDefault(false)
                }
                .map { it.fullName }
                .sortedWith(compareBy<String> { it.startsWith("[") }.thenBy { it.lowercase() })
        } finally {
            store.close()
        }
    }

    private fun findTrash(store: Store): Folder? {
        val all = store.defaultFolder.list("*")
        // Preferred: the folder the server marks as Trash (RFC 6154)
        all.firstOrNull { f ->
            (f as? IMAPFolder)?.attributes?.any { it.equals("\\Trash", ignoreCase = true) } == true
        }?.let { return it }
        val names = listOf("[Gmail]/Trash", "[Google Mail]/Trash", "Deleted", "Deleted Items",
            "Deleted Messages", "Trash", "INBOX.Trash", "Papelera", "[Gmail]/Papelera")
        for (n in names) all.firstOrNull { it.fullName.equals(n, ignoreCase = true) }?.let { return it }
        return all.firstOrNull { it.name.contains("trash", true) || it.name.contains("deleted", true) }
    }

    private fun connect(account: MailAccount, secret: String): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
            if (account.provider.oauth) {
                put("mail.imaps.auth.mechanisms", "XOAUTH2")
                put("mail.imaps.sasl.enable", "false")
                put("mail.imaps.auth.login.disable", "true")
                put("mail.imaps.auth.plain.disable", "true")
            }
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(account.host, 993, account.email, secret)
        return store
    }

    private fun formatFrom(addresses: Array<Address>?): String {
        val a = addresses?.firstOrNull() ?: return "(unknown)"
        return if (a is InternetAddress) {
            val name = a.personal
            if (name.isNullOrBlank()) a.address else "$name <${a.address}>"
        } else a.toString()
    }

    /** Prefers text/plain; falls back to stripped HTML. */
    private fun extractText(part: Part): String {
        findPart(part, "text/plain")?.let { return clean(it) }
        findPart(part, "text/html")?.let { return clean(stripHtml(it)) }
        return ""
    }

    private fun findPart(part: Part, mime: String): String? {
        if (part.isMimeType(mime)) return part.content as? String
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as? Multipart ?: return null
            for (i in 0 until mp.count) {
                findPart(mp.getBodyPart(i), mime)?.let { return it }
            }
        }
        if (part.isMimeType("message/rfc822")) {
            (part.content as? Part)?.let { return findPart(it, mime) }
        }
        return null
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")

    private fun clean(s: String): String = s
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}
