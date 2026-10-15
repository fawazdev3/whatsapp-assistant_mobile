package dev.fawaz.whatsapp_assistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "assistant/native")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isListenerEnabled" -> result.success(isListenerEnabled())
                    "openListenerSettings" ->
                        openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, result)
                    "openBatterySettings" ->
                        openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, result)
                    "getSession" -> result.success(
                        mapOf(
                            "loggedIn" to Session.isLoggedIn(this),
                            "baseUrl" to Session.baseUrl(this),
                            "username" to Session.username(this),
                        )
                    )
                    "login" -> login(call, result)
                    "logout" -> {
                        Session.clearTokens(this)
                        AppLog.add(this, "Déconnecté")
                        result.success(null)
                    }
                    "getLog" -> result.success(AppLog.entries(this))
                    "clearLog" -> {
                        AppLog.clear(this)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun login(call: MethodCall, result: MethodChannel.Result) {
        val baseUrl = call.argument<String>("baseUrl").orEmpty()
        val username = call.argument<String>("username").orEmpty()
        val password = call.argument<String>("password").orEmpty()
        val context = applicationContext

        // Appel réseau : jamais sur le fil principal
        Thread {
            val error = try {
                ApiClient.login(context, baseUrl, username, password)
            } catch (e: Exception) {
                "Erreur inattendue (${e.javaClass.simpleName})."
            }
            if (error == null) WhatsAppListenerService.flushPending(context)
            runOnUiThread { result.success(error) }
        }.start()
    }

    private fun openSettings(action: String, result: MethodChannel.Result) {
        try {
            startActivity(Intent(action))
            result.success(null)
        } catch (e: ActivityNotFoundException) {
            result.error("unavailable", "Cet écran de réglages n'existe pas sur ce téléphone.", null)
        }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }
}