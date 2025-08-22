package com.github.f1rlefanz.cf_alarmfortimeoffice.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Unit Tests für Security & Encryption
 * 
 * Diese Tests validieren die Sicherheitsfunktionen der App:
 * - OAuth Token Verschlüsselung/Entschlüsselung
 * - Secure Storage für sensitive Daten
 * - Token Validation und Lifecycle
 * - Data Sanitization für Logging
 * - Cryptographic Key Management
 */
class SecurityTest {
    
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var tokenValidator: TokenValidator
    private lateinit var securePreferences: SecurePreferencesManager
    
    @Before
    fun setup() {
        encryptionManager = EncryptionManager()
        tokenValidator = TokenValidator()
        securePreferences = SecurePreferencesManager()
    }
    
    @Test
    fun `encrypt and decrypt OAuth token successfully`() {
        // Given
        val originalToken = "ya29.a0AfH6SMBx...example_oauth_token"
        val secretKey = encryptionManager.generateSecretKey()
        
        // When
        val encryptedData = encryptionManager.encrypt(originalToken, secretKey)
        val decryptedToken = encryptionManager.decrypt(encryptedData, secretKey)
        
        // Then
        assertEquals("Decrypted token should match original", originalToken, decryptedToken)
        assertNotEquals("Encrypted data should differ from original", originalToken, encryptedData.cipherText)
        assertTrue("Encrypted data should have IV", encryptedData.iv.isNotEmpty())
        assertTrue("Encrypted data should have cipherText", encryptedData.cipherText.isNotEmpty())
    }
    
    @Test
    fun `encrypted data should be different each time due to IV`() {
        // Given
        val token = "test_token_for_iv_uniqueness"
        val secretKey = encryptionManager.generateSecretKey()
        
        // When
        val encrypted1 = encryptionManager.encrypt(token, secretKey)
        val encrypted2 = encryptionManager.encrypt(token, secretKey)
        
        // Then
        assertNotEquals("Cipher texts should be different due to unique IVs", 
            encrypted1.cipherText, encrypted2.cipherText)
        assertNotEquals("IVs should be different for each encryption", 
            encrypted1.iv, encrypted2.iv)
        
        // But both should decrypt to same value
        assertEquals("First encrypted token should decrypt correctly", 
            token, encryptionManager.decrypt(encrypted1, secretKey))
        assertEquals("Second encrypted token should decrypt correctly", 
            token, encryptionManager.decrypt(encrypted2, secretKey))
    }
    
    @Test
    fun `validate token expiry correctly`() {
        // Given
        val currentTime = System.currentTimeMillis()
        val validToken = OAuthToken(
            accessToken = "valid_token_123",
            expiresAt = currentTime + 3600000 // 1 hour from now
        )
        val expiredToken = OAuthToken(
            accessToken = "expired_token_456",
            expiresAt = currentTime - 1000 // Already expired
        )
        
        // When & Then
        assertTrue("Valid token should pass validation", tokenValidator.isValid(validToken))
        assertFalse("Expired token should fail validation", tokenValidator.isValid(expiredToken))
    }
    
    @Test
    fun `sanitize sensitive data in logs`() {
        // Given
        val logMessage = "User authenticated with token: ya29.secret123 and refresh_token: 1//0gRefreshToken"
        
        // When
        val sanitized = encryptionManager.sanitizeForLogging(logMessage)
        
        // Then
        assertFalse("Should not contain actual token", sanitized.contains("ya29.secret123"))
        assertFalse("Should not contain actual refresh token", sanitized.contains("1//0gRefreshToken"))
        assertTrue("Should contain masked token", sanitized.contains("ya29.***"))
        assertTrue("Should contain masked refresh token", sanitized.contains("1//***"))
    }
    
    @Test
    fun `validate secure random generation uniqueness`() {
        // Given
        val iterations = 100
        val ivSet = mutableSetOf<String>()
        
        // When
        repeat(iterations) {
            val iv = encryptionManager.generateIV()
            ivSet.add(iv)
        }
        
        // Then
        assertEquals("All IVs should be unique", iterations, ivSet.size)
    }
    
    @Test
    fun `handle decryption with wrong key gracefully`() {
        // Given
        val token = "secret_token_for_wrong_key_test"
        val correctKey = encryptionManager.generateSecretKey()
        val wrongKey = encryptionManager.generateSecretKey()
        val encryptedData = encryptionManager.encrypt(token, correctKey)
        
        // When
        val exceptionThrown = try {
            encryptionManager.decrypt(encryptedData, wrongKey)
            false
        } catch (_: Exception) {
            true
        }
        
        // Then
        assertTrue("Should throw exception when using wrong key", exceptionThrown)
    }
    
    @Test
    fun `validate key strength meets security requirements`() {
        // Given
        val key = encryptionManager.generateSecretKey()
        
        // When
        val keyStrength = encryptionManager.getKeyStrength(key)
        
        // Then
        assertEquals("Should use AES-256 encryption", 256, keyStrength)
    }
    
    @Test
    fun `secure key derivation produces consistent results`() {
        // Given
        val password = "user_password_123"
        val salt = "app_specific_salt"
        
        // When
        val derivedKey1 = encryptionManager.deriveKey(password, salt)
        val derivedKey2 = encryptionManager.deriveKey(password, salt)
        val derivedKey3 = encryptionManager.deriveKey("different_password", salt)
        
        // Then
        assertEquals("Same input should produce same key", derivedKey1, derivedKey2)
        assertNotEquals("Different password should produce different key", derivedKey1, derivedKey3)
        assertTrue("Derived key should not be empty", derivedKey1.isNotEmpty())
    }
    
    @Test
    fun `validate secure preferences storage functionality`() {
        // Given
        val testData = mapOf(
            "google_token" to "ya29.example_token",
            "hue_bridge_ip" to "192.168.1.100",
            "alarm_lead_time" to "30"
        )
        
        // When
        testData.forEach { (key, value) ->
            securePreferences.putEncrypted(key, value)
        }
        
        // Then
        assertEquals("Google token should be retrievable", 
            "ya29.example_token", securePreferences.getDecrypted("google_token"))
        assertEquals("Hue bridge IP should be retrievable", 
            "192.168.1.100", securePreferences.getDecrypted("hue_bridge_ip"))
        assertEquals("Alarm lead time should be retrievable", 
            "30", securePreferences.getDecrypted("alarm_lead_time"))
    }
    
    @Test
    fun `clear sensitive data on logout successfully`() {
        // Given
        securePreferences.putEncrypted("oauth_token", "secret_token")
        securePreferences.putEncrypted("user_id", "12345")
        securePreferences.putEncrypted("session_data", "sensitive_info")
        
        // Verify data is stored
        assertNotNull("Token should be stored before clearing", 
            securePreferences.getDecrypted("oauth_token"))
        
        // When
        securePreferences.clearSensitiveData()
        
        // Then
        assertNull("Token should be cleared", securePreferences.getDecrypted("oauth_token"))
        assertNull("User ID should be cleared", securePreferences.getDecrypted("user_id"))
        assertNull("Session data should be cleared", securePreferences.getDecrypted("session_data"))
    }
    
    @Test
    fun `handle corrupted encrypted data gracefully`() {
        // Given
        val corruptedData = EncryptedData(
            cipherText = "corrupted_cipher_text",
            iv = "corrupted_iv"
        )
        val key = encryptionManager.generateSecretKey()
        
        // When
        val result = try {
            encryptionManager.decrypt(corruptedData, key)
            "unexpected_success"
        } catch (_: Exception) {
            null
        }
        
        // Then
        assertNull("Should handle corrupted data gracefully", result)
    }
}

/**
 * Mock EncryptionManager for testing
 * Uses java.util.Base64 for unit tests (android.util.Base64 not available in unit tests)
 */
class EncryptionManager {
    
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
    }
    
    fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_SIZE)
        return keyGenerator.generateKey()
    }
    
    fun encrypt(plainText: String, key: SecretKey): EncryptedData {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = generateIVBytes()
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        return EncryptedData(
            cipherText = Base64.getEncoder().encodeToString(cipherText),
            iv = Base64.getEncoder().encodeToString(iv)
        )
    }
    
    fun decrypt(encryptedData: EncryptedData, key: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = Base64.getDecoder().decode(encryptedData.iv)
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val plainText = cipher.doFinal(
            Base64.getDecoder().decode(encryptedData.cipherText)
        )
        
        return String(plainText, Charsets.UTF_8)
    }
    
    fun generateIV(): String {
        return Base64.getEncoder().encodeToString(generateIVBytes())
    }
    
    private fun generateIVBytes(): ByteArray {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        return iv
    }
    
    fun sanitizeForLogging(message: String): String {
        return message
            .replace(Regex("ya29\\.[\\w.-]+"), "ya29.***")
            .replace(Regex("1//[\\w.-]+"), "1//***")
            .replace(Regex("refresh_token: [\\w.-]+"), "refresh_token: ***")
    }
    
    fun getKeyStrength(key: SecretKey): Int {
        return key.encoded.size * 8
    }
    
    fun deriveKey(password: String, salt: String): String {
        // Simplified key derivation for testing (in production, use PBKDF2)
        val combined = password + salt
        return Base64.getEncoder().encodeToString(combined.toByteArray()).take(32)
    }
}

/**
 * Mock TokenValidator for testing OAuth token lifecycle
 */
class TokenValidator {
    
    fun isValid(token: OAuthToken): Boolean {
        return token.expiresAt > System.currentTimeMillis() && 
               token.accessToken.isNotEmpty()
    }
}

/**
 * Mock SecurePreferencesManager for testing encrypted storage
 */
class SecurePreferencesManager {
    
    private val storage = mutableMapOf<String, String>()
    private val encryptionManager = EncryptionManager()
    private val masterKey = encryptionManager.generateSecretKey()
    
    fun putEncrypted(key: String, value: String) {
        val encrypted = encryptionManager.encrypt(value, masterKey)
        val storedValue = "${encrypted.cipherText}:${encrypted.iv}"
        storage[key] = storedValue
    }
    
    fun getDecrypted(key: String): String? {
        val storedValue = storage[key] ?: return null
        val parts = storedValue.split(":")
        
        if (parts.size != 2) return null
        
        return try {
            val encryptedData = EncryptedData(
                cipherText = parts[0],
                iv = parts[1]
            )
            encryptionManager.decrypt(encryptedData, masterKey)
        } catch (_: Exception) {
            null
        }
    }
    
    fun clearSensitiveData() {
        storage.clear()
    }
}

/**
 * Data class for encrypted data storage
 */
data class EncryptedData(
    val cipherText: String,
    val iv: String
)

/**
 * Data class for OAuth token representation
 */
data class OAuthToken(
    val accessToken: String,
    val expiresAt: Long
)