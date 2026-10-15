package dev.fawaz.whatsapp_assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petit journal local (60 lignes, le plus récent en premier) affiché dans l'application.
 * On n'y écrit jamais le texte des messages, seulement le nom de l'expéditeur et le résultat.
 */
object AppLog {
    private const val PREFS = "assistant_log"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 60
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, message: String) {
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        synchronized(lock) {
            val updated = mutableListOf("$time  $message")
            updated.addAll(read(context).take(MAX_ENTRIES - 1))
            prefs(context).edit().putString(KEY, JSONArray(updated).toString()).apply()
        }
    }

    fun entries(context: Context): List<String> = synchronized(lock) { read(context) }

    fun clear(context: Context) {
        synchronized(lock) { prefs(context).edit().remove(KEY).apply() }
    }

    private fun read(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}