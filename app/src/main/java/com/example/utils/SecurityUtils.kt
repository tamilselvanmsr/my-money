package com.example.utils

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val ITERATION_COUNT = 1000
    private const val KEY_LENGTH = 256 // AES-256
    private const val SALT_LENGTH = 16 // 16 bytes
    private const val IV_LENGTH = 16 // 16 bytes (AES block size)

    data class EncryptedData(
        val saltB64: String,
        val ivB64: String,
        val ciphertextB64: String
    )

    // Derived AES encryption key from password and salt
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    // Encrypts plaintext string using AES-256-CBC
    fun encrypt(plaintext: String, password: String): String {
        // 1. Generate random Salt and IV
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)
        
        // 2. Derive Key
        val secretKey = deriveKey(password, salt)
        
        // 3. Encrypt
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // 4. Encode as a single token: saltB64 + ":" + ivB64 + ":" + ciphertextB64
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        
        return "$saltB64:$ivB64:$ciphertextB64"
    }

    // Decrypts ciphertext string using AES-256-CBC
    fun decrypt(encryptedPayload: String, password: String): String {
        val parts = encryptedPayload.split(":")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid encrypted payload format")
        }
        
        val saltB64 = parts[0]
        val ivB64 = parts[1]
        val ciphertextB64 = parts[2]
        
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
        
        // Derive key using same salt and password
        val secretKey = deriveKey(password, salt)
        
        // Decrypt
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        
        return String(plaintextBytes, Charsets.UTF_8)
    }
}
