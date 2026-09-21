package ir.inod.smsguard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the AI API key with a hardware-backed Android Keystore key.
 *
 * The reference is explicit that the key must not live in SharedPreferences or
 * a settings file, and it was right: a plaintext key is readable by anything
 * with root or an ADB backup. Here the key material never leaves the Keystore —
 * only ciphertext is persisted, and the Keystore entry cannot be exported.
 *
 * No third-party dependency: this is the platform API directly, which is more
 * predictable than pulling in a deprecated alpha library.
 */
object SecureKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sms_guard_ai_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    /** Marker so a legacy plaintext value can be recognised and migrated. */
    const val PREFIX = "enc:"

    private fun secretKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: generateKey()
    } catch (e: Exception) {
        null
    }

    private fun generateKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    } catch (e: Exception) {
        null
    }

    /** Returns "enc:" + base64(iv || ciphertext), or null when unavailable. */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val key = secretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(body, 0, packed, iv.size, body.size)
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return null
        val raw = if (stored.startsWith(PREFIX)) stored.substring(PREFIX.length) else stored
        return try {
            val key = secretKey() ?: return null
            val packed = Base64.decode(raw, Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH) return null
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val body = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // A key that cannot be decrypted (Keystore reset, restore to a new
            // device) is treated as absent rather than crashing the settings.
            null
        }
    }

    fun clear() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // ignore
        }
    }
}
