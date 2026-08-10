package com.github.f1rlefanz.cf_alarmfortimeoffice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for Context to create DataStore
//
// corruptionHandler: gleiche Begruendung wie DataModule/ShiftConfigRepository - ohne ihn blockiert
// eine beschaedigte preferences_pb nicht nur jedes Lesen, sondern dauerhaft auch jedes Schreiben
// (DataStore liest vor jedem Write erneut), reboot-fest und ohne Selbstheilung ausser
// "App-Daten loeschen". Hier liegt login_status/user_email/calendar_id, also der Zustand, der die
// GESAMTE App gated: der Nutzer koennte sich anmelden, aber der Login liesse sich nie mehr
// persistieren. Bewusster Preis: die Datei faellt EINMAL auf "nicht angemeldet" zurueck (sie ist
// ohnehin unlesbar) - das loest einen Re-Login aus und kaschiert nichts.
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = {
        Logger.w(
            LogTags.DATASTORE,
            "⚠️ auth_prefs war beschaedigt und wurde durch einen leeren Zustand ersetzt - " +
                "eine Neuanmeldung ist noetig"
        )
        emptyPreferences()
    })
)

/**
 * AuthDataStoreRepository - implementiert IAuthDataStoreRepository Interface
 *
 * REFACTORED:
 * ✅ Implementiert IAuthDataStoreRepository für bessere Testbarkeit
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Flow-basierte reaktive Datenbeobachtung
 * ✅ Batch-Updates für bessere Performance
 *
 * Verwaltet Authentifizierungsdaten mit DataStore Preferences
 */
@Singleton
class AuthDataStoreRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : IAuthDataStoreRepository {

    private val dataStore = context.authDataStore

    // OPTIMIERUNG: Keys als companion object für bessere Performance
    companion object {
        private val LOGIN_STATUS_KEY = booleanPreferencesKey("login_status")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USER_EMAIL_KEY = stringPreferencesKey("user_email")
        private val CALENDAR_ID_KEY = stringPreferencesKey("calendar_id")
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val TOKEN_EXPIRY_KEY = longPreferencesKey("token_expiry_long")
    }

    // Interface Implementation
    //
    // .catch{}: derselbe Grund wie in ShiftConfigRepository.shiftConfig und
    // DataStoreTokenRepository.observe() - ein Upstream-Fehler (IO-Fehler, ein Defekt, den der
    // corruptionHandler nicht abfaengt) wuerde sonst ungefangen in die Collector der ViewModels
    // laufen und die App beenden. Degradation auf "nicht angemeldet" ist hier die fail-safe
    // Richtung: sie loest einen Re-Login aus, statt einen Alarm-Ausfall zu kaschieren.
    override val authData: Flow<AuthData> = dataStore.data
        .catch { e ->
            Logger.e(LogTags.DATASTORE, "auth_prefs nicht lesbar - Flow degradiert auf 'nicht angemeldet'", e)
            emit(emptyPreferences())
        }
        .map { preferences ->
        val isLoggedIn = preferences[LOGIN_STATUS_KEY] == true
        val email = preferences[USER_EMAIL_KEY] ?: ""
        val displayName = preferences[USER_ID_KEY] ?: ""
        val accessToken = preferences[ACCESS_TOKEN_KEY]
        val refreshToken = preferences[REFRESH_TOKEN_KEY]
        val tokenExpiryTime = preferences[TOKEN_EXPIRY_KEY]
        
        if (isLoggedIn) {
            AuthData(
                isLoggedIn = true,
                email = email.ifEmpty { "temp@example.com" },
                displayName = displayName.ifEmpty { "User" },
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiryTime = tokenExpiryTime
            )
        } else {
            AuthData.EMPTY
        }
    }.distinctUntilChanged()

    override suspend fun updateAuthData(authData: AuthData): Result<Unit> = 
        updateAuthDataInternal(
            isLoggedIn = authData.isLoggedIn,
            userId = authData.displayName,
            userEmail = authData.email,
            accessToken = authData.accessToken,
            refreshToken = authData.refreshToken,
            tokenExpiry = authData.tokenExpiryTime
        )

    override suspend fun clearAuthData(): Result<Unit> = 
        SafeExecutor.safeExecute("AuthDataStoreRepository.clearAuthData") {
            dataStore.edit { preferences ->
                preferences.clear()
            }
        }

    override suspend fun isAuthenticated(): Result<Boolean> = 
        SafeExecutor.safeExecute("AuthDataStoreRepository.isAuthenticated") {
            val preferences = dataStore.data.first()
            preferences[LOGIN_STATUS_KEY] == true
        }

    override suspend fun getCurrentAuthData(): Result<AuthData> = 
        SafeExecutor.safeExecute("AuthDataStoreRepository.getCurrentAuthData") {
            authData.first()
        }

    // OPTIMIERUNG: Batch-Updates für bessere Performance mit Fehlerbehandlung
    private suspend fun updateAuthDataInternal(
        isLoggedIn: Boolean? = null,
        userId: String? = null,
        userEmail: String? = null,
        calendarId: String? = null,
        accessToken: String? = null,
        refreshToken: String? = null,
        tokenExpiry: Long? = null
    ): Result<Unit> = SafeExecutor.safeExecute("AuthDataStoreRepository.updateAuthDataInternal") {
        dataStore.edit { preferences ->
            // BUGFIX: Nur updaten wenn sich wirklich etwas ändert
            isLoggedIn?.let { 
                if (preferences[LOGIN_STATUS_KEY] != it) {
                    preferences[LOGIN_STATUS_KEY] = it
                }
            }
            userId?.let { 
                if (preferences[USER_ID_KEY] != it) {
                    preferences[USER_ID_KEY] = it
                }
            }
            userEmail?.let { 
                if (preferences[USER_EMAIL_KEY] != it) {
                    preferences[USER_EMAIL_KEY] = it
                }
            }
            calendarId?.let { 
                if (preferences[CALENDAR_ID_KEY] != it) {
                    preferences[CALENDAR_ID_KEY] = it
                }
            }
            accessToken?.let { 
                if (preferences[ACCESS_TOKEN_KEY] != it) {
                    preferences[ACCESS_TOKEN_KEY] = it
                }
            }
            refreshToken?.let { 
                if (preferences[REFRESH_TOKEN_KEY] != it) {
                    preferences[REFRESH_TOKEN_KEY] = it
                }
            }
            tokenExpiry?.let { 
                if (preferences[TOKEN_EXPIRY_KEY] != it) {
                    preferences[TOKEN_EXPIRY_KEY] = it
                }
            }
        }
    }

    override suspend fun migrateTokenExpiryIfNeeded(): Result<Unit> = 
        SafeExecutor.safeExecute("AuthDataStoreRepository.migrateTokenExpiry") {
            dataStore.edit { preferences ->
                // Clear any old token_expiry data to prevent conflicts
                val keysToRemove = preferences.asMap().keys.filter { 
                    it.name == "token_expiry" && it != TOKEN_EXPIRY_KEY 
                }
                keysToRemove.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
                
                if (keysToRemove.isNotEmpty()) {
                    Logger.d(LogTags.DATASTORE, "Cleared ${keysToRemove.size} legacy token_expiry keys")
                }
            }
        }
}
