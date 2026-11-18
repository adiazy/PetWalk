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
                            body = extractText(msg).take(2000)
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
