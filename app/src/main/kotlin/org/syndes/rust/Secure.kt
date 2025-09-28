package org.syndes.terminal

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Secure {

    private const val SALT_LENGTH_BYTES = 32
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PBKDF2_ITERATIONS = 75_000
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION = "v1"

    // === Удобные перегрузки для String паролей ===
    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(password: String?, plaintext: String): String {
        return encrypt(password?.toCharArray(), plaintext)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun decrypt(password: String?, input: String): String {
        return decrypt(password?.toCharArray(), input)
    }

    // === Основные методы с CharArray ===
    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(password: CharArray?, plaintext: String): String {
        if (password == null || password.isEmpty() || plaintext.isEmpty()) {
            throw IllegalArgumentException("Password and plaintext must be non-null and non-empty")
        }

        val rnd = SecureRandom()

        val salt = ByteArray(SALT_LENGTH_BYTES)
        rnd.nextBytes(salt)

        val iv = ByteArray(IV_LENGTH_BYTES)
        rnd.nextBytes(iv)

        var key: ByteArray? = null
        try {
            key = deriveKey(password, salt)
            val secretKey = SecretKeySpec(key, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            // AAD: format version, salt, iv (as in original)
            cipher.updateAAD(FORMAT_VERSION.toByteArray(StandardCharsets.UTF_8))
            cipher.updateAAD(salt)
            cipher.updateAAD(iv)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            val bSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
            val bIv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val bCt = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

            return "$FORMAT_VERSION:$bSalt:$bIv:$bCt"
        } finally {
            // secure wipe
            if (key != null) {
                Arrays.fill(key, 0.toByte())
            }
            Arrays.fill(password, '\u0000')
        }
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun decrypt(password: CharArray?, input: String): String {
        if (password == null || input.isEmpty()) {
            throw IllegalArgumentException("Password and input must be non-null")
        }

        val parts = input.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != FORMAT_VERSION) {
            throw IllegalArgumentException("Invalid input format or version")
        }

        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)

        if (salt.size != SALT_LENGTH_BYTES || iv.size != IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid salt or IV length")
        }

        var key: ByteArray? = null
        try {
            key = deriveKey(password, salt)
            val secretKey = SecretKeySpec(key, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            cipher.updateAAD(FORMAT_VERSION.toByteArray(StandardCharsets.UTF_8))
            cipher.updateAAD(salt)
            cipher.updateAAD(iv)

            val plainBytes = cipher.doFinal(ciphertext)
            return String(plainBytes, StandardCharsets.UTF_8)
        } finally {
            if (key != null) {
                Arrays.fill(key, 0.toByte())
            }
            Arrays.fill(password, '\u0000')
        }
    }

    @Throws(InvalidKeySpecException::class, GeneralSecurityException::class)
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }
}
