package id.floatmind.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenRouter credential encrypted with a non-exportable Android Keystore key. */
object SecurePrefs {
    private const val ALIAS = "floatmind_openrouter_key_v1"
    private const val PREFS = "floatmind_secure"
    private const val KEY_VALUE = "openrouter_key_enc"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun saveApiKey(context: Context, value: String): Boolean {
        return try {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (value.isBlank()) {
                preferences.edit().remove(KEY_VALUE).apply()
                true
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val packed = cipher.iv + encrypted
                preferences.edit()
                    .putString(KEY_VALUE, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .apply()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun readApiKey(context: Context): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VALUE, null) ?: return ""

        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH_BYTES + GCM_TAG_BITS / 8)

            val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
