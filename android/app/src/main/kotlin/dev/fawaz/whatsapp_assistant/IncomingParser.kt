package dev.fawaz.whatsapp_assistant

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class IncomingNotification(
    val senderName: String,
    val senderPhone: String,
    val text: String,
    val notificationId: String,
    val receivedAt: String,
)

sealed class Parsed {
    data class Accept(val message: IncomingNotification) : Parsed()

    /** `reason` = null : on ignore sans rien écrire dans le journal (bruit de WhatsApp). */
    data class Skip(val reason: String?) : Parsed()
}

object IncomingParser {
    // Pour écouter aussi WhatsApp normal : ajoute "com.whatsapp"
    private val ALLOWED_PACKAGES = setOf("com.whatsapp")

    // WhatsApp affiche les médias sous forme de texte : « 📷 A envoyé une photo »
    private val MEDIA_PREFIXES = listOf("📷", "🎤", "🎥", "🎬", "🎵", "🎧", "📄", "📍", "👤", "🖼")

    private val GROUPED_TITLE_SUFFIX = Regex("\\s*\\(\\d+\\s+messages?\\)$")
    private val GROUPED_TEXT =
        Regex("^\\d+\\s+(nouveaux\\s+)?messages?\\b.*", RegexOption.IGNORE_CASE)

    private val PHONE_NOISE = Regex("[\\s\\u00A0\\u202F\\u200E\\u200F().\\-]")
    private val PHONE = Regex("^\\+\\d{7,15}$")

    fun parse(sbn: StatusBarNotification): Parsed {
        if (sbn.packageName !in ALLOWED_PACKAGES) return Parsed.Skip(null)

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return Parsed.Skip(null)

        // Une vraie conversation a toujours une action de réponse : sauvegardes, appels, états... n'en ont pas
        val hasReplyAction = notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
        if (!hasReplyAction) return Parsed.Skip(null)

        val extras = notification.extras
        if (extras.getBoolean("android.isGroupConversation", false)) return Parsed.Skip("groupe")

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val title = rawTitle.replace(GROUPED_TITLE_SUFFIX, "").trim()
        if (title.isBlank() || text.isBlank()) return Parsed.Skip(null)

        if (MEDIA_PREFIXES.any { text.startsWith(it) }) return Parsed.Skip("média")
        if (GROUPED_TEXT.matches(text)) return Parsed.Skip("messages groupés")

        // Personne absente du répertoire : WhatsApp affiche le numéro à la place du nom
        val phone = asPhoneNumber(title)

        return Parsed.Accept(
            IncomingNotification(
                senderName = if (phone == null) title.take(150) else "",
                senderPhone = phone.orEmpty(),
                text = text.take(4000),
                notificationId = stableId(sbn, text),
                receivedAt = isoUtc(sbn.postTime),
            )
        )
    }

    private fun asPhoneNumber(title: String): String? {
        val compact = title.replace(PHONE_NOISE, "")
        return if (PHONE.matches(compact)) compact else null
    }

    /** Identifiant stable de cette notification : le backend s'en sert pour ne jamais traiter deux fois. */
    private fun stableId(sbn: StatusBarNotification, text: String): String {
        val raw = "${sbn.key}|${sbn.postTime}|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun isoUtc(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}