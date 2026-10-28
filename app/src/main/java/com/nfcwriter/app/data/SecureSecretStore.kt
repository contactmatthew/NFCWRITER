package com.nfcwriter.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedSecret(val ciphertext: String, val nonceOrIv: String, val keyVersion: Int = 1)

class SecureSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_nfc_secrets", Context.MODE_PRIVATE)
    private val alias = "nfc_writer_local_secret_key"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun put(id: String, value: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = value.concatToString().encodeToByteArray(); value.fill('\u0000')
        val encrypted = cipher.doFinal(bytes); bytes.fill(0)
        prefs.edit().putString(id, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }
    fun get(id: String): CharArray? = prefs.getString(id, null)?.let { encoded ->
        val all = Base64.decode(encoded, Base64.NO_WRAP); val iv = all.copyOfRange(0, 12); val encrypted = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        cipher.doFinal(encrypted).decodeToString().toCharArray()
    }
    fun remove(id: String) { prefs.edit().remove(id).apply() }

    /** Encrypts binary credential material with a non-exportable Android Keystore key. */
    fun encrypt(value: ByteArray, associatedId: String): EncryptedSecret {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(associatedId.encodeToByteArray())
        val encrypted = try { cipher.doFinal(value) } finally { value.fill(0) }
        return EncryptedSecret(
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            nonceOrIv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        ).also { encrypted.fill(0) }
    }

    fun decrypt(secret: EncryptedSecret, associatedId: String): ByteArray {
        require(secret.keyVersion == 1) { "Unsupported local credential key version." }
        val iv = Base64.decode(secret.nonceOrIv, Base64.NO_WRAP)
        val encrypted = Base64.decode(secret.ciphertext, Base64.NO_WRAP)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                updateAAD(associatedId.encodeToByteArray())
            }
            cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }
}
