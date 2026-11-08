package com.edgestream.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption for logins and keys at rest (provider username/password, TorBox and TMDB keys,
 * addon URLs that carry keys). AES-256-GCM with a key held in the Android Keystore: it is
 * generated on the TV, never leaves it, and can't be exported with the app's data.
 *
 * Sealed values look like "enc1:<base64 iv+ciphertext>". [open] passes anything else through
 * unchanged, so values saved before encryption existed keep working until they're re-saved.
 */
object Secrets {
    private const val ALIAS = "edgestream.secrets.v1"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    fun isSealed(value: String) = value.startsWith(PREFIX)

    /** Encrypts [plain]; blank stays blank, and an already-sealed value is left as it is. */
    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Decrypts a sealed value; plain values (from before encryption) pass straight through. */
    fun open(value: String): String {
        if (!isSealed(value)) return value
        val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(c.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    /** Decrypts a sealed value; a corrupted or invalidated Keystore entry becomes blank. */
    fun openOrBlank(value: String): String = runCatching { open(value) }.getOrDefault("")

    /** For display: never the secret itself, only enough to recognise it. */
    fun mask(plain: String): String = when {
        plain.isBlank() -> ""
        plain.length <= 4 -> "••••"
        else -> "••••" + plain.takeLast(4)
    }

    /** A URL shown without any login, key or path: "http://user:pass@host:8080/get.php?..." → "host:8080". */
    fun maskUrl(url: String): String = url.substringAfter("://").substringAfter('@').substringBefore('/').substringBefore('?')
}
