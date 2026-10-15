package dev.fawaz.whatsapp_assistant

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WhatsAppListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        AppLog.add(applicationContext, "Service d'écoute connecté")
        // Messages restés en attente avant un arrêt du service
        flushPending(applicationContext)
    }

    override fun onListenerDisconnected() {
        AppLog.add(applicationContext, "Service d'écoute déconnecté")
        // Demande à Android de relancer le service (certains constructeurs le coupent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, WhatsAppListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        when (val parsed = IncomingParser.parse(sbn)) {
            is Parsed.Skip ->
                parsed.reason?.let { AppLog.add(applicationContext, "Ignoré : $it") }
            is Parsed.Accept -> {
                val context = applicationContext
                val message = parsed.message
                // Écriture sur le disque d'abord : rien n'est perdu si le serveur est en panne
                worker.execute {
                    PendingQueue.add(context, message)
                    flush(context)
                }
            }
        }
    }

    companion object {
        // Un seul fil : les messages partent dans l'ordre où ils sont arrivés
        private val worker = Executors.newSingleThreadScheduledExecutor()
        private val retryScheduled = AtomicBoolean(false)
        private val failures = AtomicInteger(0)

        private const val BASE_RETRY_SECONDS = 30L
        private const val MAX_RETRY_SECONDS = 600L

        /** À appeler après une reconnexion au serveur : vide la file. */
        fun flushPending(context: Context) {
            val appContext = context.applicationContext
            worker.execute { flush(appContext) }
        }

        private fun scheduleRetry(context: Context) {
            if (!retryScheduled.compareAndSet(false, true)) return
            val step = failures.getAndIncrement().coerceAtMost(5)
            val delay = (BASE_RETRY_SECONDS shl step).coerceAtMost(MAX_RETRY_SECONDS)
            worker.schedule({
                retryScheduled.set(false)
                flush(context)
            }, delay, TimeUnit.SECONDS)
        }

        private fun describeDraft(draftStatus: String): String = when (draftStatus) {
            "ai_failed" -> "IA indisponible (brouillon vide)"
            "approved" -> "réponse prête"
            "pending" -> "brouillon à valider"
            else -> "brouillon créé"
        }

        private fun describe(outcome: String, draftStatus: String): String = when (outcome) {
            "processed" -> describeDraft(draftStatus)
            "queued" -> "en file d'attente"
            "queue_failed" -> "file d'attente en panne (message enregistré)"
            "duplicate" -> "déjà traité"
            "ignored_unknown_contact" -> "contact inconnu : crée-le avec ce nom exact"
            "ignored_ambiguous_contact" -> "plusieurs contacts portent ce nom"
            "ignored_disabled" -> "contact désactivé"
            "ignored_group" -> "groupe ignoré"
            else -> outcome
        }

        private fun payload(message: IncomingNotification): JSONObject = JSONObject()
            .put("sender_name", message.senderName)
            .put("sender_phone", message.senderPhone)
            .put("text", message.text)
            .put("notification_id", message.notificationId)
            .put("received_at", message.receivedAt)
            .put("is_group", false)

        /** Envoie la file dans l'ordre. S'arrête au premier échec temporaire. */
        private fun flush(context: Context) {
            while (true) {
                val item = PendingQueue.first(context)
                if (item == null) {
                    failures.set(0)
                    return
                }
                val message = item.message
                val who = message.senderName.ifEmpty { message.senderPhone }

                if (System.currentTimeMillis() - item.enqueuedAt > PendingQueue.MAX_AGE_MS) {
                    PendingQueue.remove(context, message.notificationId)
                    AppLog.add(context, "$who : abandonné (message trop ancien)")
                    continue
                }

                val outcome = try {
                    ApiClient.sendIncoming(context, payload(message))
                } catch (e: Exception) {
                    SendOutcome.Failed("erreur inattendue (${e.javaClass.simpleName})", true)
                }

                when (outcome) {
                    is SendOutcome.Delivered -> {
                        PendingQueue.remove(context, message.notificationId)
                        failures.set(0)
                        AppLog.add(context, "$who → ${describe(outcome.outcome, outcome.draftStatus)}")
                    }
                    is SendOutcome.NotLoggedIn -> {
                        AppLog.add(context, "$who : en attente (pas connecté au serveur)")
                        return // repartira après la connexion
                    }
                    is SendOutcome.SessionExpired -> {
                        AppLog.add(context, "$who : en attente (session expirée, reconnecte-toi)")
                        return
                    }
                    is SendOutcome.Failed -> {
                        if (outcome.retryable) {
                            // On ne note que le premier échec, pas chaque nouvel essai
                            if (failures.get() == 0) {
                                AppLog.add(
                                    context,
                                    "$who : mis en attente (${PendingQueue.size(context)} message(s)), ${outcome.reason}"
                                )
                            }
                            scheduleRetry(context)
                            return
                        }
                        PendingQueue.remove(context, message.notificationId)
                        AppLog.add(context, "$who : abandonné, ${outcome.reason}")
                    }
                }
            }
        }
    }
}