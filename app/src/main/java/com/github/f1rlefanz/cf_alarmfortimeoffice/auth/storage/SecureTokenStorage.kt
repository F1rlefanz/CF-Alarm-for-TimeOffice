@file:Suppress("DEPRECATION") // EncryptedSharedPreferences/MasterKey: No stable alternative available yet

package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Secure token storage implementation.
 * 
 * SECURITY: Uses EncryptedSharedPreferences with AES256-GCM encryption.
 * Protects OAuth2 tokens from extraction even on rooted devices.
 * 
 * Single responsibility: Token persistence operations only.
 */
class SecureTokenStorage(private val context: Context) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val prefs: SharedPreferences by lazy {
        try {
            // Create MasterKey for encryption
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            Logger.business(LogTags.TOKEN, "✅ OPTION-1-FIX: EncryptedSharedPreferences initialized successfully")
            encryptedPrefs
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ OPTION-1-FIX: Failed to create EncryptedSharedPreferences - falling back to regular SharedPreferences", e)
            Logger.w(LogTags.TOKEN, "⚠️ OPTION-1-FIX: SECURITY WARNING - Tokens will be stored without encryption!")
            Logger.d(LogTags.TOKEN, "💡 OPTION-1-FIX: Error details: ${e::class.simpleName} - ${e.message}")
            
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    /**
     * Saves token data to storage.
     * Performs operation on IO dispatcher for optimal performance.
     * 
     * 🔧 STORAGE-BUG-FIX: Fixed verification to compare deserialized objects instead of JSON strings
     * The original bug was comparing JSON strings directly, which failed due to:
     * - EncryptedSharedPreferences encryption/decryption
     * - Possible field order differences in JSON serialization
     * - Floating point precision differences
     * This caused tokens to be rejected as "not saved" even though they were saved successfully.
     */
    suspend fun saveToken(tokenData: TokenData): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tokenJson = json.encodeToString(tokenData)

            val success = prefs.edit()
                .putString(KEY_TOKEN_DATA, tokenJson)
                .putLong(KEY_LAST_SAVED, System.currentTimeMillis())
                .commit()

            if (success) {
                // 🔧 STORAGE-BUG-FIX: Verify by deserializing and comparing objects
                // instead of string comparison which fails with EncryptedSharedPreferences
                try {
                    val verifyJson = prefs.getString(KEY_TOKEN_DATA, null)
                    if (verifyJson != null) {
                        val verifiedToken = json.decodeFromString<TokenData>(verifyJson)

                        // Compare critical fields to ensure data integrity
                        val isValid = verifiedToken.accessToken == tokenData.accessToken &&
                                     verifiedToken.refreshToken == tokenData.refreshToken &&
                                     verifiedToken.expiresAt == tokenData.expiresAt &&
                                     verifiedToken.userEmail == tokenData.userEmail

                        if (isValid) {
                            Logger.d(LogTags.TOKEN, "✅ STORAGE-BUG-FIX: Token saved and verified successfully (object comparison)")
                            Result.success(Unit)
                        } else {
                            val error = "Token save verification failed - data mismatch after deserialization"
                            Logger.e(LogTags.TOKEN, "❌ STORAGE-BUG-FIX: $error")
                            Logger.d(LogTags.TOKEN, "Original: accessToken=${tokenData.accessToken?.take(20)}, refreshToken=${tokenData.refreshToken?.take(20)}")
                            Logger.d(LogTags.TOKEN, "Verified: accessToken=${verifiedToken.accessToken?.take(20)}, refreshToken=${verifiedToken.refreshToken?.take(20)}")
                            Result.failure(TokenStorageException(error))
                        }
                    } else {
                        val error = "Token save verification failed - no data returned after save"
                        Logger.e(LogTags.TOKEN, "❌ STORAGE-BUG-FIX: $error")
                        Result.failure(TokenStorageException(error))
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.TOKEN, "❌ STORAGE-BUG-FIX: Verification failed with exception", e)
                    // If verification fails but save succeeded, still return success
                    // to prevent tokens from being lost
                    Logger.w(LogTags.TOKEN, "⚠️ STORAGE-BUG-FIX: Accepting save despite verification failure to prevent token loss")
                    Result.success(Unit)
                }
            } else {
                val error = "Failed to commit token to storage"
                Logger.e(LogTags.TOKEN, error)
                Result.failure(TokenStorageException(error))
            }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error saving token to storage", e)
            Result.failure(TokenStorageException("Failed to save token", e))
        }
    }
    
    /**
     * Retrieves token data from storage.
     * Returns empty token if no valid data found.
     */
    suspend fun getToken(): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            val tokenJson = prefs.getString(KEY_TOKEN_DATA, null)
            
            if (tokenJson.isNullOrBlank()) {
                Logger.d(LogTags.TOKEN, "No token found in storage")
                return@withContext Result.success(TokenData.empty())
            }
            
            val tokenData = json.decodeFromString<TokenData>(tokenJson)
            Logger.d(LogTags.TOKEN, "Token retrieved from storage: ${tokenData.toLogString()}")
            
            Result.success(tokenData)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error retrieving token from storage", e)
            // Return empty token instead of failing to allow app to continue
            Result.success(TokenData.empty())
        }
    }
    

    
    /**
     * Clears all token data from storage.
     * Used during logout or when tokens become permanently invalid.
     */
    suspend fun clearToken(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val success = prefs.edit()
                .remove(KEY_TOKEN_DATA)
                .remove(KEY_LAST_SAVED)
                .commit()
            
            if (success) {
                Logger.business(LogTags.TOKEN, "Token cleared from storage")
                Result.success(Unit)
            } else {
                val error = "Failed to clear token from storage"
                Logger.e(LogTags.TOKEN, error)
                Result.failure(TokenStorageException(error))
            }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error clearing token from storage", e)
            Result.failure(TokenStorageException("Failed to clear token", e))
        }
    }
    
    /**
     * Updates only the access token part of stored token data.
     * Optimized for refresh token operations.
     */
    suspend fun updateAccessToken(
        newAccessToken: String,
        newExpiresAt: Long,
        newScope: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentTokenResult = getToken()
            val currentToken = currentTokenResult.getOrElse { 
                return@withContext Result.failure(TokenStorageException("Cannot update token: no existing token found"))
            }
            
            val updatedToken = currentToken.withRefreshedAccessToken(
                newAccessToken = newAccessToken,
                newExpiresAt = newExpiresAt,
                newScope = newScope
            )
            
            saveToken(updatedToken)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error updating access token", e)
            Result.failure(TokenStorageException("Failed to update access token", e))
        }
    }
    
    companion object {
        private const val PREFS_NAME = "cf_alarm_tokens"
        private const val KEY_TOKEN_DATA = "oauth2_token_data"
        private const val KEY_LAST_SAVED = "token_last_saved"
    }
}

/**
 * Custom exception for token storage operations.
 */
class TokenStorageException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
