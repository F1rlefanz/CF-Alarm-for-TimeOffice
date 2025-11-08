package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * One-Time Migration von EncryptedSharedPreferences zu DataStore
 * 
 * Wird beim App-Start einmalig ausgeführt
 * 
 * Features:
 * - ✅ Migriert alte Token-Daten
 * - ✅ Fixt "REFRESH_VIA_REAUTH" Workarounds
 * - ✅ Validation nach Migration
 * - ✅ Cleanup alter Daten
 */
class TokenDataMigration(
    private val context: Context,
    private val newRepository: TokenRepository
) {
    
    companion object {
        private const val PREFS_NAME = "cf_alarm_tokens"
        private const val KEY_TOKEN_DATA = "oauth2_token_data"
        private const val MIGRATION_COMPLETED_KEY = "migration_to_datastore_completed"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Führt Migration aus wenn nötig
     */
    suspend fun migrateIfNeeded(): MigrationResult = withContext(Dispatchers.IO) {
        try {
            // Check if migration already completed
            val migrationPrefs = context.getSharedPreferences("migration_status", Context.MODE_PRIVATE)
            if (migrationPrefs.getBoolean(MIGRATION_COMPLETED_KEY, false)) {
                Logger.d(LogTags.TOKEN, "Migration already completed, skipping")
                return@withContext MigrationResult.AlreadyMigrated
            }
            
            // Try to load old token
            val oldToken = loadFromEncryptedSharedPreferences()
            
            if (oldToken == null) {
                Logger.d(LogTags.TOKEN, "No old token found, marking migration as complete")
                markMigrationComplete()
                return@withContext MigrationResult.NoDataToMigrate
            }
            
            Logger.i(LogTags.TOKEN, "🔄 Starting token migration to DataStore...")
            
            // Migrate token data
            val migratedToken = migrateTokenData(oldToken)
            
            // Save to new repository
            val saveResult = newRepository.save(migratedToken)
            
            if (saveResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ Migration failed: could not save to DataStore", saveResult.exceptionOrNull())
                return@withContext MigrationResult.Failed("Failed to save to DataStore")
            }
            
            // Verify migration
            val verifyToken = newRepository.get()
            if (verifyToken?.accessToken != migratedToken.accessToken) {
                Logger.e(LogTags.TOKEN, "❌ Migration verification failed")
                return@withContext MigrationResult.Failed("Verification failed")
            }
            
            Logger.i(LogTags.TOKEN, "✅ Token successfully migrated and verified")
            
            // Clear old data
            clearOldData()
            
            // Mark migration complete
            markMigrationComplete()
            
            MigrationResult.Success(migratedToken)
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Migration failed with exception", e)
            MigrationResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Lädt Token aus altem EncryptedSharedPreferences
     */
    private fun loadFromEncryptedSharedPreferences(): TokenData? {
        return try {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            val tokenJson = prefs.getString(KEY_TOKEN_DATA, null)
            
            if (tokenJson.isNullOrBlank()) {
                Logger.d(LogTags.TOKEN, "No token data in old storage")
                return null
            }
            
            val oldToken = json.decodeFromString<TokenData>(tokenJson)
            Logger.i(LogTags.TOKEN, "Old token loaded: ${oldToken.toLogString()}")
            
            oldToken
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error loading old token", e)
            null
        }
    }
    
    /**
     * Migriert Token-Daten zum neuen Format
     */
    private fun migrateTokenData(oldToken: TokenData): TokenData {
        // Fix für alte "REFRESH_VIA_REAUTH" Workarounds
        val (actualRefreshToken, googleEmail) = if (oldToken.refreshToken?.startsWith("REFRESH_VIA_REAUTH:") == true) {
            // Extract email from workaround
            val email = oldToken.refreshToken.substring("REFRESH_VIA_REAUTH:".length)
            Logger.i(LogTags.TOKEN, "🔧 Fixing REFRESH_VIA_REAUTH workaround, extracted email: $email")
            null to email
        } else {
            oldToken.refreshToken to oldToken.googleAccountEmail
        }
        
        // Fallback to deprecated userEmail field if googleAccountEmail is null
        val finalEmail = googleEmail ?: oldToken.userEmail
        
        return oldToken.copy(
            refreshToken = actualRefreshToken,
            googleAccountEmail = finalEmail,
            tokenProvider = if (actualRefreshToken == null) {
                TokenProvider.GOOGLE_PLAY_SERVICES
            } else {
                TokenProvider.OAUTH2_STANDARD
            }
        )
    }
    
    /**
     * Löscht alte Daten
     */
    private fun clearOldData() {
        try {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            prefs.edit().clear().apply()
            Logger.d(LogTags.TOKEN, "Old token data cleared")
            
        } catch (e: Exception) {
            Logger.w(LogTags.TOKEN, "Could not clear old data", e)
            // Non-critical, migration was successful
        }
    }
    
    /**
     * Markiert Migration als abgeschlossen
     */
    private fun markMigrationComplete() {
        context.getSharedPreferences("migration_status", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MIGRATION_COMPLETED_KEY, true)
            .apply()
    }
}

/**
 * Migration Result
 */
sealed class MigrationResult {
    data class Success(val migratedToken: TokenData) : MigrationResult()
    data class Failed(val reason: String) : MigrationResult()
    object NoDataToMigrate : MigrationResult()
    object AlreadyMigrated : MigrationResult()
}
