package com.ikverse.signallab.analyst

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets with a key the app cannot export. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM with a key held by Android's Keystore: the key never leaves the phone's secure hardware, so a copy of the app's
 * files (a backup, another device) cannot read what it sealed. The output is the 12-byte nonce followed by the ciphertext.
 */
class KeystoreCipher(private val alias: String = ALIAS) : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, NONCE))
        return c.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    companion object {
        const val ALIAS = "signal_lab_claude_api_key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val NONCE = 12
    }
}

/**
 * The owner's Claude API key, sealed by [cipher] in [file]. The file lives in the app's no-backup folder, so it is never in
 * a backup, a sync file or another device; it is never logged and never sent anywhere but Anthropic's API.
 */
class ApiKeyStore(private val file: File, private val cipher: SecretCipher) {
    @Volatile
    private var cached: String? = null

    val isSet: Boolean get() = cached != null || file.exists()

    /** Saves [key] after checking it looks like a Claude API key; returns why not when it does not. */
    fun save(key: String): String? {
        val k = key.trim()
        problem(k)?.let { return it }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(k.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        cached = k
        return null
    }

    /** The key, or null when none is saved or it can no longer be read (the Keystore key was reset). */
    fun load(): String? {
        cached?.let { return it }
        if (!file.exists()) return null
        return runCatching { String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) }.getOrNull()?.also { cached = it }
    }

    fun clear() {
        cached = null
        file.delete()
    }

    /** The key's last four characters, to show which one is saved without showing it. */
    fun hint(): String? = load()?.takeLast(4)

    companion object {
        const val FILE_NAME = "claude_api_key.bin"

        fun problem(key: String): String? = when {
            key.isEmpty() -> "Paste the key from the Claude Console."
            key.any { it.isWhitespace() } -> "A key has no spaces in it."
            !key.startsWith("sk-ant-") -> "A Claude API key starts with sk-ant-."
            key.length < 30 || key.length > 300 -> "That is not the length of a Claude API key."
            else -> null
        }
    }
}
