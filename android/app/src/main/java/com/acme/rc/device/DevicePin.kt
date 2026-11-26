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
 * The device unlock credential (numeric PIN or alphanumeric password),
 * encrypted with an Android Keystore key.
 *
 * The credential never leaves the device after provisioning and is never
 * written to logs, snapshots, run reports, or the relay's JSON files. The
 * server only forwards the one-time `setDevicePin` command.
 */
object DevicePin {
    private const val PREFS = "rc_device_pin"
    private const val KEY_BLOB = "pin_blob"
    private const val KEY_CACHED_KEYPAD = "allow_cached_keypad"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rc.device.pin.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 64

    fun isValid(secret: String): Boolean =
        secret.length in MIN_LENGTH..MAX_LENGTH &&
            secret.none { it < ' ' || it == '\u007F' }

    /** Presence check that does not decrypt the PIN. */
    fun isConfigured(context: Context): Boolean =
        prefs(context).getString(KEY_BLOB, null) != null

    fun allowCachedKeypad(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CACHED_KEYPAD, false)

    fun setAllowCachedKeypad(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_CACHED_KEYPAD, allow).apply()
    }

    @Synchronized
    fun save(context: Context, secret: String) {
        require(isValid(secret)) { "Credential must be $MIN_LENGTH–$MAX_LENGTH characters" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = "${b64(cipher.iv)}:${b64(ciphertext)}"
        prefs(context).edit().putString(KEY_BLOB, blob).apply()
    }

    @Synchronized
    fun read(context: Context): String? {
        val blob = prefs(context).getString(KEY_BLOB, null) ?: return null
        return try {
            val parts = blob.split(':', limit = 2)
            if (parts.size != 2) {
                clear(context)
                return null
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            // A reset/rotated Keystore key invalidates the blob. Drop it so the
            // panel shows "no PIN" instead of repeatedly failing to unlock.
            clear(context)
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        } catch (_: Throwable) {
            // A missing or unavailable Keystore key is already the desired state.
        }
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

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
