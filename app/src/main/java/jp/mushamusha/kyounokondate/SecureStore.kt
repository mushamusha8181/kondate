package jp.mushamusha.kyounokondate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)
    private val alias = "kyounokondate_aes_v1"

    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) return
        val payload = try {
            encrypt(value, key())
        } catch (_: GeneralSecurityException) {
            // A permanently invalidated/missing Android Keystore key cannot decrypt
            // the old payloads. Reset only for cryptographic failures, not arbitrary bugs.
            resetKeystoreEntry()
            encrypt(value, generateKey())
        }
        prefs.edit().putString(name, payload).apply()
    }

    private fun encrypt(value: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun get(name: String): String? {
        val payload = prefs.getString(name, null) ?: return null
        return try {
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) {
                prefs.edit().remove(name).apply()
                return null
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            prefs.edit().remove(name).apply()
            null
        } catch (_: GeneralSecurityException) {
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun has(name: String): Boolean = get(name)?.isNotBlank() == true

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun resetKeystoreEntry() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
        prefs.edit().clear().commit()
    }
}
