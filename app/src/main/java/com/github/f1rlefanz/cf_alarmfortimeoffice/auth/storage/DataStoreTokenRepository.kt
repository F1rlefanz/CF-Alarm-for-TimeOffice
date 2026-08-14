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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-basierte Token-Repository Implementation mit Tink-Verschlüsselung
 *
 * Vorteile gegenüber EncryptedSharedPreferences:
 * - ✅ Async by design (keine Race Conditions)
 * - ✅ Type-safe Preferences API
 * - ✅ Atomare Operationen
 * - ✅ Native Flow-Support
 * - ✅ Bessere Performance
 * - ✅ Keine Verification-Race-Conditions
 * - ✅ Tink AEAD-Verschlüsselung (AES-256-GCM)
 * - ✅ Android Keystore-backed Master Key
 *
 * Security:
 * - Verschlüsselung: AES-256-GCM via Tink Crypto
 * - Master Key: Android Keystore (Hardware-backed wenn verfügbar)
 * - Authentifizierung: GCM authentication tag
 * - Tampering Detection: Automatisch durch AEAD
 */
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
    
    // ✅ VERSCHLÜSSELTER DataStore mit Tink Crypto (Lazy initialization)
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
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error clearing token from DataStore", e)
            Result.failure(e)
        }
    }
    
    override fun observe(): Flow<TokenData?> {
        // ✅ Muss wie get() degradieren, nicht crashen. Der Store hat inzwischen einen
        // corruptionHandler und `EncryptedPreferencesSerializer.readFrom()` uebersetzt einen
        // Tink-Fehler in eine CorruptionException (siehe EncryptedDataStoreFactory) - das .catch{}
        // ist dadurch NICHT ueberfluessig geworden:
        //  * Der Handler greift ausschliesslich bei CorruptionException. Ein IO-Fehler oder eine
        //    gescheiterte Verschluesselung beim (Ersatz-)Schreiben laeuft weiterhin als Exception
        //    aus tokenDataStore.data heraus.
        //  * readFrom() wirft einen unerwarteten Lesefehler seit dem Fix bewusst weiter, statt
        //    still leere Preferences zu liefern (ein stiller Default wuerde beim naechsten Write
        //    ueber den intakten Token geschrieben).
        // Ungefangen landet so ein Fehler direkt in AuthViewModel.observeTokenLoss()s collect{}
        // (viewModelScope.launch ohne try/catch, laeuft ab init{}) und beendet die App bei JEDEM
        // Start. Der Flow muss also degradieren - aber NICHT nach "kein Token":
        //
        // 1. WOHIN degradiert wird, ist hier die eigentliche Entscheidung. Der einzige Konsument
        //    (AuthViewModel.observeTokenLoss) wertet ausschliesslich das NEGATIVE Signal aus: ein
        //    emittiertes "kein Token" heisst fuer ihn "Google hat den Zugriff entzogen" und loest
        //    einen Zustimmungsdialog samt hasValidToken=false aus. Ein einmaliger IO-Fehler auf
        //    token_data_v2_encrypted.preferences_pb (Speicherdruck, Storage-Haenger - genau der
        //    Fall, fuer den EncryptedPreferencesSerializer.readFrom() bewusst weiterwirft) haette
        //    damit einem Nutzer mit voellig intaktem Token eine Neuanmeldung aufgedraengt.
        //    Deshalb: im Fehlerfall wird NICHTS emittiert. Kein Signal ist hier richtiger als ein
        //    falsches - der Wecker haengt nicht an diesem Flow, die Notlage-Neuanmeldung schon.
        //
        // 2. Ein blosses .catch{} BEENDET den Flow (es faengt, emittiert und laesst normal
        //    abschliessen). Der Wuerfel faellt also nur einmal: danach war der Token-Verlust-
        //    Waechter fuer die gesamte Prozesslaufzeit tot, und ein SPAETERER, echter
        //    Token-Verlust wurde nie mehr bemerkt - die App lief bis zum naechsten Kaltstart
        //    weiter, als sei alles in Ordnung, waehrend kein Kalender mehr abrufbar war.
        //    Deshalb retryWhen mit wachsendem Abstand, exakt wie beim Gegenstueck
        //    CalendarSelectionRepository: ein transienter Fehler heilt sich selbst.
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
