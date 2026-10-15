package dev.fawaz.whatsapp_assistant

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data class Ok(val body: JSONObject) : ApiResult()
    data class HttpError(val code: Int, val body: String) : ApiResult()
    data class NetworkError(val message: String) : ApiResult()
}

sealed class RefreshResult {
    data class Refreshed(val access: String) : RefreshResult()
    object Rejected : RefreshResult() // le refresh token est refusé : il faut se reconnecter
    data class Unreachable(val reason: String) : RefreshResult()
}

sealed class SendOutcome {
    data class Delivered(val outcome: String, val draftStatus: String) : SendOutcome()
    object NotLoggedIn : SendOutcome()
    object SessionExpired : SendOutcome()
    data class Failed(val reason: String, val retryable: Boolean) : SendOutcome()
}

object ApiClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    // Le serveur attend l'IA (jusqu'à ~65 s si Gemini est en panne) avant de répondre
    private const val READ_TIMEOUT_MS = 90_000
    private val refreshLock = Any()

    private fun request(url: String, payload: JSONObject, bearer: String?): ApiResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                try {
                    ApiResult.Ok(JSONObject(text))
                } catch (e: JSONException) {
                    ApiResult.Ok(JSONObject())
                }
            } else {
                ApiResult.HttpError(code, text)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** Retourne null en cas de succès, sinon le message d'erreur à afficher. */
    fun login(context: Context, rawBaseUrl: String, username: String, password: String): String? {
        val baseUrl = rawBaseUrl.trim().trimEnd('/')
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            return "L'adresse du serveur doit commencer par http:// ou https://."
        }
        if (username.isBlank() || password.isEmpty()) {
            return "Renseigne l'identifiant et le mot de passe."
        }

        val payload = JSONObject().put("username", username.trim()).put("password", password)
        return when (val result = request("$baseUrl/api/auth/token/", payload, null)) {
            is ApiResult.Ok -> {
                val access = result.body.optString("access")
                val refresh = result.body.optString("refresh")
                if (access.isEmpty() || refresh.isEmpty()) {
                    "Réponse inattendue du serveur."
                } else {
                    Session.save(context, baseUrl, username.trim(), access, refresh)
                    AppLog.add(context, "Connecté au serveur")
                    null
                }
            }
            is ApiResult.HttpError ->
                if (result.code == 401) "Identifiant ou mot de passe incorrect."
                else "Erreur du serveur (${result.code})."
            is ApiResult.NetworkError ->
                "Serveur injoignable (${result.message}). Vérifie « adb reverse » et que runserver tourne."
        }
    }

    private fun exchangeRefreshToken(context: Context, base: String, refresh: String): RefreshResult {
        val result = request(
            "$base/api/auth/token/refresh/",
            JSONObject().put("refresh", refresh),
            null
        )
        return when (result) {
            is ApiResult.Ok -> {
                val newAccess = result.body.optString("access")
                if (newAccess.isEmpty()) {
                    RefreshResult.Rejected
                } else {
                    val newRefresh = result.body.optString("refresh").ifEmpty { null }
                    Session.updateTokens(context, newAccess, newRefresh)
                    RefreshResult.Refreshed(newAccess)
                }
            }
            is ApiResult.HttpError ->
                if (result.code == 400 || result.code == 401) RefreshResult.Rejected
                else RefreshResult.Unreachable("HTTP ${result.code}")
            is ApiResult.NetworkError -> RefreshResult.Unreachable(result.message)
        }
    }

    private fun refreshAccess(context: Context, base: String, staleAccess: String): RefreshResult =
        synchronized(refreshLock) {
            val current = Session.access(context)
            if (current != null && current != staleAccess) {
                // Un autre fil a déjà renouvelé le token
                RefreshResult.Refreshed(current)
            } else {
                val refresh = Session.refresh(context)
                if (refresh == null) RefreshResult.Rejected
                else exchangeRefreshToken(context, base, refresh)
            }
        }

    /** Envoie un message reçu au backend. Renouvelle le token une fois si le serveur répond 401. */
    fun sendIncoming(context: Context, payload: JSONObject): SendOutcome {
        val base = Session.baseUrl(context)
        val access = Session.access(context)
        if (base == null || access == null) return SendOutcome.NotLoggedIn

        val url = "$base/api/channels/android/incoming/"
        var result = request(url, payload, access)

        if (result is ApiResult.HttpError && result.code == 401) {
            when (val refreshed = refreshAccess(context, base, access)) {
                is RefreshResult.Refreshed -> result = request(url, payload, refreshed.access)
                is RefreshResult.Rejected -> {
                    Session.clearTokens(context)
                    return SendOutcome.SessionExpired
                }
                is RefreshResult.Unreachable ->
                    return SendOutcome.Failed(
                        "serveur injoignable au renouvellement (${refreshed.reason})", true
                )
            }
        }

        return when (result) {
            is ApiResult.Ok -> SendOutcome.Delivered(
                result.body.optString("outcome", "?"),
                result.body.optJSONObject("draft")?.optString("status").orEmpty()
            )
            is ApiResult.HttpError -> SendOutcome.Failed(
                "refusé par le serveur (${result.code}) ${result.body.take(120)}",
                // Panne côté serveur : on réessaiera. Refus (400...) : inutile.
                result.code >= 500 || result.code == 408 || result.code == 429
            )
            is ApiResult.NetworkError ->
                SendOutcome.Failed("serveur injoignable (${result.message})", true)
        }
    }
}