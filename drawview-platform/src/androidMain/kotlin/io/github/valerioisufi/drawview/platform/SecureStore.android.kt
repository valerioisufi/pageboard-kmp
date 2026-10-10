package io.github.valerioisufi.drawview.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The secrets encrypted by a key of the Android Keystore (it never leaves it), in private preferences. */
actual object SecureStore {
    private const val ALIAS = "drawview-secure-store"
    private const val PREFERENCES = "drawview_secure_store"

    private val preferences get() = DrawViewAndroid.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    actual fun read(name: String): String? = try {
        preferences.getString(name, null)?.let { stored ->
            val (iv, encrypted) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).decodeToString()
        }
    } catch (_: Exception) {
        null
    }

    actual fun write(name: String, value: String?): Boolean = try {
        if (value == null) {
            preferences.edit().remove(name).commit()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.encodeToByteArray())
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            preferences.edit().putString(name, stored).commit()
        }
    } catch (_: Exception) {
        false
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
