package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedDataStoreFactory
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Token-Repository auf einem mit Tink (AES-256-GCM, Keystore-Master-Key) verschlüsselten DataStore. */
@Singleton
class DataStoreTokenRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TokenRepository {
    
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("token_data_v2")

        /** Wiederholversuche des [observe]-Flows nach einem Upstream-Lesefehler - siehe dort. */
        private const val OBSERVE_RETRY_ATTEMPTS = 5L
        private const val OBSERVE_RETRY_BASE_DELAY_MS = 500L
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // Selbst gebaut (lazy), bewusst kein injizierter @TokenDataStore - siehe CLAUDE.md.
    private val tokenDataStore: DataStore<Preferences> by lazy {
        EncryptedDataStoreFactory.create(
            context = context,
            name = "token_data_v2_encrypted"
        )
    }
    
    override suspend fun get(): TokenData? {
        return try {
            tokenDataStore.data
                .map { preferences ->
                    preferences[TOKEN_KEY]?.let { tokenJson ->
                        json.decodeFromString<TokenData>(tokenJson)
                    }
                }
                .first()
        } catch (e: CancellationException) {
            // Muss VOR dem generischen Fang stehen. CancellationException erbt von Exception, ein
            // blosses `catch (e: Exception)` verschluckt sie also und macht aus einem abgebrochenen
            // Aufruf ein inhaltliches Ergebnis. Das widerspricht der Projekt-Invariante "eine
            // Cancellation laeuft weiter" (SafeExecutor, AlarmRepository) und setzte hier konkret
            // das eigene `catch (e: CancellationException) { throw e }` in `getValidToken()` ausser
            // Kraft, das dadurch nie erreicht wurde. Kein bekannter Schaden - `getValidToken()`
            // steht in `withContext(Dispatchers.IO)`, das die Cancellation beim Verlassen ohnehin
            // wirft -, aber die Absicherung darf nicht davon abhaengen, dass ein Aufrufer sie
            // zufaellig nachholt.
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error reading token from DataStore", e)
            null
        }
    }
    
    override suspend fun save(token: TokenData): Result<Unit> {
        return try {
            tokenDataStore.edit { preferences ->
                val tokenJson = json.encodeToString(TokenData.serializer(), token)
                preferences[TOKEN_KEY] = tokenJson
            }
            Logger.d(LogTags.TOKEN, "🔐 Token encrypted and saved to DataStore: ${token.toLogString()}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e   // siehe get(): darf nicht im generischen Fang landen
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error saving token to DataStore", e)
            Result.failure(e)
        }
    }
    
    override suspend fun clear(): Result<Unit> {
        return try {
            tokenDataStore.edit { preferences ->
                preferences.remove(TOKEN_KEY)
            }
            Logger.d(LogTags.TOKEN, "✅ Token cleared from DataStore")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e   // siehe get(): darf nicht im generischen Fang landen
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error clearing token from DataStore", e)
            Result.failure(e)
        }
    }
    
    override fun observe(): Flow<TokenData?> {
        // Ungefangen beendete ein Lesefehler die App bei jedem Start (observeTokenLoss collect ohne
        // try/catch). Im Fehlerfall wird NICHTS emittiert - kein Signal statt falschem "kein Token"
        // (das erzwaenge eine Neuanmeldung); retryWhen statt .catch, weil .catch den Flow beendet.
        // Hergang: Skill cfalarm-persistenz-und-auth, reference/auth-und-token.md.
        return tokenDataStore.data
            .retryWhen { cause, attempt ->
                if (attempt >= OBSERVE_RETRY_ATTEMPTS) {
                    Logger.e(
                        LogTags.TOKEN,
                        "Token-DataStore nach ${attempt} Versuchen nicht lesbar - Token-Verlust-Waechter endet",
                        cause
                    )
                    false
                } else {
                    Logger.w(
                        LogTags.TOKEN,
                        "Token-DataStore nicht lesbar (Versuch ${attempt + 1}/$OBSERVE_RETRY_ATTEMPTS) - neuer Versuch",
                        cause
                    )
                    delay(OBSERVE_RETRY_BASE_DELAY_MS * (attempt + 1))
                    true
                }
            }
            .catch { e ->
                // Letzte Verteidigungslinie gegen den Absturz im Collector - bewusst OHNE emit,
                // siehe Punkt 1 oben.
                Logger.e(LogTags.TOKEN, "Error reading token DataStore (observe)", e)
            }
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
