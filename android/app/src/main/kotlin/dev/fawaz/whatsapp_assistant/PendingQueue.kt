package dev.fawaz.whatsapp_assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class PendingItem(val message: IncomingNotification, val enqueuedAt: Long)

/** Messages reçus mais pas encore acceptés par le serveur. Stockés chiffrés. */
object PendingQueue {
    private const val PREFS = "assistant_queue"
    private const val KEY = "items"
    private const val MAX_ITEMS = 50
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableList<PendingItem> {
        val encrypted = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val json = SecretBox.decrypt(encrypted) ?: return mutableListOf()
        return try {
            val array = JSONArray(json)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                PendingItem(
                    IncomingNotification(
                        senderName = o.getString("senderName"),
                        senderPhone = o.getString("senderPhone"),
                        text = o.getString("text"),
                        notificationId = o.getString("notificationId"),
                        receivedAt = o.getString("receivedAt"),
                    ),
                    o.getLong("enqueuedAt"),
                )
            }
        } catch (e: JSONException) {
            mutableListOf()
        }
    }

    private fun save(context: Context, items: List<PendingItem>) {
        if (items.isEmpty()) {
            prefs(context).edit().remove(KEY).apply()
            return
        }
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("senderName", item.message.senderName)
                    .put("senderPhone", item.message.senderPhone)
                    .put("text", item.message.text)
                    .put("notificationId", item.message.notificationId)
                    .put("receivedAt", item.message.receivedAt)
                    .put("enqueuedAt", item.enqueuedAt)
            )
        }
        prefs(context).edit().putString(KEY, SecretBox.encrypt(array.toString())).apply()
    }

    fun add(context: Context, message: IncomingNotification) = synchronized(lock) {
        val items = load(context)
        if (items.any { it.message.notificationId == message.notificationId }) return@synchronized
        items.add(PendingItem(message, System.currentTimeMillis()))
        while (items.size > MAX_ITEMS) items.removeAt(0) // on garde les plus récents
        save(context, items)
    }

    fun first(context: Context): PendingItem? = synchronized(lock) { load(context).firstOrNull() }

    fun remove(context: Context, notificationId: String) = synchronized(lock) {
        val items = load(context)
        if (items.removeAll { it.message.notificationId == notificationId }) save(context, items)
    }

    fun size(context: Context): Int = synchronized(lock) { load(context).size }
}