package app.screeaner

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * This tablet's long-term pairing key and the Macs it already trusts.
 * Kept in app-private storage; uninstalling the app forgets everything.
 */
class TrustStore(context: Context) {
    private val prefs = context.getSharedPreferences("screeaner_trust", Context.MODE_PRIVATE)

    val identity: KeyPair by lazy { loadOrCreate() }

    private fun loadOrCreate(): KeyPair {
        val privateText = prefs.getString(KEY_PRIVATE, null)
        val publicText = prefs.getString(KEY_PUBLIC, null)
        if (privateText != null && publicText != null) {
            try {
                val factory = KeyFactory.getInstance("EC")
                val private = factory.generatePrivate(PKCS8EncodedKeySpec(decode(privateText)))
                val public = factory.generatePublic(X509EncodedKeySpec(decode(publicText)))
                return KeyPair(public, private)
            } catch (_: Exception) {
                // Corrupt or from an incompatible version: start over.
            }
        }
        val pair = SecureChannel.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, encode(pair.private.encoded))
            .putString(KEY_PUBLIC, encode(pair.public.encoded))
            .apply()
        return pair
    }

    fun isTrusted(macKey: ByteArray): Boolean = prefs.contains(MAC_PREFIX + encode(macKey))

    fun trust(macKey: ByteArray, name: String) {
        prefs.edit().putString(MAC_PREFIX + encode(macKey), name).apply()
    }

    fun trustedMacs(): List<String> =
        prefs.all.filterKeys { it.startsWith(MAC_PREFIX) }.values.mapNotNull { it as? String }.sorted()

    fun forgetMacs() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(MAC_PREFIX) }.forEach { editor.remove(it) }
        editor.remove(KEY_FAVORITE_HOST)
        editor.apply()
    }

    /**
     * The Wi-Fi address of the Mac that marked this tablet as favorite: tried
     * right after USB, so the stream starts without waiting for Bonjour.
     */
    fun favoriteHost(): Discovery.Endpoint? {
        val text = prefs.getString(KEY_FAVORITE_HOST, null) ?: return null
        val port = text.substringAfterLast(':', "").toIntOrNull() ?: return null
        return Discovery.Endpoint(text.substringBeforeLast(':'), port)
    }

    /** Called with each stream's CONFIG over Wi-Fi: remembers or forgets this Mac's address. */
    fun updateFavoriteHost(endpoint: Discovery.Endpoint, favorite: Boolean) {
        val text = "${endpoint.host}:${endpoint.port}"
        val current = prefs.getString(KEY_FAVORITE_HOST, null)
        when {
            favorite && current != text -> prefs.edit().putString(KEY_FAVORITE_HOST, text).apply()
            !favorite && current == text -> prefs.edit().remove(KEY_FAVORITE_HOST).apply()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEY_PRIVATE = "identityPrivate"
        const val KEY_PUBLIC = "identityPublic"
        const val MAC_PREFIX = "mac:"
        const val KEY_FAVORITE_HOST = "favoriteHost"
    }
}
