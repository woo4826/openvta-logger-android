package dev.openvta.logger.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.openvta.logger.domain.AppSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SecureSettingsRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun load(): AppSettings {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return AppSettings()
        return runCatching {
            SettingsCodec.decode(decrypt(encoded))
        }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_PAYLOAD, encrypt(SettingsCodec.encode(settings)))
            .commit()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encode(cipher.iv) + ":" + Base64.encode(cipherText)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted settings payload" }
        val iv = Base64.decode(parts[0])
        val cipherText = Base64.decode(parts[1])
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_ALIAS = "vta_logger_settings_v1"
        private const val KEY_PAYLOAD = "settings_payload"
    }
}
