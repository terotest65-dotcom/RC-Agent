package com.acme.rc.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional per-app unlock credentials, encrypted with an Android Keystore key
 * and keyed by package name.
 *
 * MIUI/POCO App lock uses the device credential, so an app entry is optional:
 * when none is stored for a package, the app-unlock action falls back to the
 * device unlock credential. Third-party app lockers and apps with their own PIN
 * can store a package-specific value.
 */
object AppUnlockStore {
    private const val PREFS = "rc_app_unlock"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rc.app.unlock.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "pkg:"

    fun isValid(secret: String): Boolean = DevicePin.isValid(secret)

    fun packages(context: Context): Set<String> =
        prefs(context).all.keys
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .filter { it.isNotBlank() }
            .toSet()

    fun isConfigured(context: Context, packageName: String): Boolean =
        packageName.isNotBlank() && prefs(context).contains(PREFIX + packageName)

    @Synchronized
    fun save(context: Context, packageName: String, secret: String) {
        require(packageName.isNotBlank()) { "package is required" }
        require(isValid(secret)) { "Credential must be ${DevicePin.MIN_LENGTH}–${DevicePin.MAX_LENGTH} characters" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = "${b64(cipher.iv)}:${b64(ciphertext)}"
        prefs(context).edit().putString(PREFIX + packageName, blob).apply()
    }

    @Synchronized
    fun read(context: Context, packageName: String): String? {
        val key = PREFIX + packageName
        val blob = prefs(context).getString(key, null) ?: return null
        return try {
            val parts = blob.split(':', limit = 2)
            if (parts.size != 2) {
                prefs(context).edit().remove(key).apply()
                return null
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            prefs(context).edit().remove(key).apply()
            null
        }
    }

    @Synchronized
    fun clear(context: Context, packageName: String) {
        prefs(context).edit().remove(PREFIX + packageName).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
