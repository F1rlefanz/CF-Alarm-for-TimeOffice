package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * DataStore-basierte Token-Repository Implementation
 * 
 * Vorteile gegenüber EncryptedSharedPreferences:
 * - ✅ Async by design (keine Race Conditions)
 * - ✅ Type-safe Preferences API
 * - ✅ Atomare Operationen
 * - ✅ Native Flow-Support
 * - ✅ Bessere Performance
 * - ✅ Keine Verification-Race-Conditions
 * 
 * Migration: TokenDataMigration handled die One-Time-Migration
 */
class DataStoreTokenRepository(
    private val context: Context
) : TokenRepository {
    
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("token_data_v2")
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // DataStore Extension (wird pro Context nur einmal erstellt)
    private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "token_data_v2"
        // Note: Tink Encryption wird später via EncryptedPreferencesSerializer hinzugefügt
        // Für jetzt: Unencrypted DataStore (Migration-Phase)
    )
    
    override suspend fun get(): TokenData? {
        return try {
            context.tokenDataStore.data
                .map { preferences ->
                    preferences[TOKEN_KEY]?.let { tokenJson ->
                        json.decodeFromString<TokenData>(tokenJson)
                    }
                }
                .first()
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error reading token from DataStore", e)
            null
        }
    }
    
    override suspend fun save(token: TokenData): Result<Unit> {
        return try {
            context.tokenDataStore.edit { preferences ->
                val tokenJson = json.encodeToString(TokenData.serializer(), token)
                preferences[TOKEN_KEY] = tokenJson
            }
            Logger.d(LogTags.TOKEN, "✅ Token saved to DataStore: ${token.toLogString()}")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error saving token to DataStore", e)
            Result.failure(e)
        }
    }
    
    override suspend fun clear(): Result<Unit> {
        return try {
            context.tokenDataStore.edit { preferences ->
                preferences.remove(TOKEN_KEY)
            }
            Logger.d(LogTags.TOKEN, "✅ Token cleared from DataStore")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error clearing token from DataStore", e)
            Result.failure(e)
        }
    }
    
    override fun observe(): Flow<TokenData?> {
        return context.tokenDataStore.data
            .map { preferences ->
                preferences[TOKEN_KEY]?.let { tokenJson ->
                    try {
                        json.decodeFromString<TokenData>(tokenJson)
                    } catch (e: Exception) {
                        Logger.e(LogTags.TOKEN, "Error deserializing token", e)
                        null
                    }
                }
            }
    }
}
