package dev.fawaz.whatsapp_assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chiffre les jetons avec une clé AES stockée dans l'Android Keystore (elle ne quitte jamais le téléphone). */
internal object SecretBox {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "assistant_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** Retourne null si le texte est illisible (clé perdue, données corrompues). */
    fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/** Session de connexion au serveur Django : adresse en clair, jetons chiffrés. */
object Session {
    private const val PREFS = "assistant_session"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, baseUrl: String, username: String, access: String, refresh: String) {
        prefs(context).edit()
            .putString("base_url", baseUrl)
            .putString("username", username)
            .putString("access", SecretBox.encrypt(access))
            .putString("refresh", SecretBox.encrypt(refresh))
            .apply()
    }

    /** Après un renouvellement. `refresh` n'est fourni que si le serveur en a émis un nouveau. */
    fun updateTokens(context: Context, access: String, refresh: String? = null) {
        val editor = prefs(context).edit().putString("access", SecretBox.encrypt(access))
        if (refresh != null) editor.putString("refresh", SecretBox.encrypt(refresh))
        editor.apply()
    }

    fun baseUrl(context: Context): String? = prefs(context).getString("base_url", null)

    fun username(context: Context): String? = prefs(context).getString("username", null)

    fun access(context: Context): String? =
        prefs(context).getString("access", null)?.let { SecretBox.decrypt(it) }

    fun refresh(context: Context): String? =
        prefs(context).getString("refresh", null)?.let { SecretBox.decrypt(it) }

    /** Déconnexion : on garde l'adresse et l'identifiant pour préremplir l'écran. */
    fun clearTokens(context: Context) {
        prefs(context).edit().remove("access").remove("refresh").apply()
    }

    fun isLoggedIn(context: Context): Boolean =
        access(context) != null && refresh(context) != null
}