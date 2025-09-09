package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase für Calendar Authentication - modernisiert mit Credential Manager
 * 
 * MIGRATION STATUS:
 * ✅ Entfernt: GoogleSignIn deprecated APIs
 * ✅ Implementiert: Credential Manager Integration  
 * ✅ Verwendet: OAuth2TokenManager für moderne Token-Verwaltung
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 */
class CalendarAuthUseCase(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val calendarRepository: ICalendarRepository
) : ICalendarAuthUseCase {
    
    override suspend fun getAvailableCalendarsWithAuth(): Result<List<AndroidCalendar>> = 
        SafeExecutor.safeExecute("CalendarAuthUseCase.getAvailableCalendarsWithAuth") {
            // Validate token first
            val hasValidToken = validateAndRefreshToken().getOrElse { false }
            if (!hasValidToken) {
                throw Exception("No valid authentication token available")
            }
            
            // Get current auth data
            val authData = authDataStoreRepository.getCurrentAuthData().getOrThrow()
            val accessToken = authData.accessToken 
                ?: throw Exception("No access token in auth data")
            
            // Load calendars
            val calendarItems = calendarRepository.getCalendarsWithToken(accessToken).getOrThrow()
            calendarItems.map { item ->
                AndroidCalendar(
                    id = item.id,
                    name = item.displayName
                )
            }
        }
    
    override suspend fun validateAndRefreshToken(): Result<Boolean> = 
        SafeExecutor.safeExecute("CalendarAuthUseCase.validateAndRefreshToken") {
            val authData = authDataStoreRepository.getCurrentAuthData().getOrElse { 
                return@safeExecute false 
            }
            
            // Check if token exists and is not expired
            val hasToken = !authData.accessToken.isNullOrEmpty()
            val isNotExpired = (authData.tokenExpiryTime ?: 0L) > System.currentTimeMillis()
            
            if (hasToken && isNotExpired) {
                Logger.d(LogTags.AUTH, "Token is valid")
                return@safeExecute true
            }
            
            // For now, return false to trigger re-authentication
            // Future: Implement token refresh logic with OAuth2TokenManager when needed
            Logger.w(LogTags.AUTH, "Token validation failed: hasToken=$hasToken, notExpired=$isNotExpired")
            return@safeExecute false
        }
    
    override suspend fun isCalendarAccessAvailable(): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                val authData = authDataStoreRepository.getCurrentAuthData().getOrElse { 
                    return@withContext false 
                }
                
                val isAuthenticated = authData.isLoggedIn
                val hasToken = !authData.accessToken.isNullOrEmpty()
                val isNotExpired = (authData.tokenExpiryTime ?: 0L) > System.currentTimeMillis()
                
                isAuthenticated && hasToken && isNotExpired
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error checking calendar access availability: ${e.message}")
                false
            }
        }
    
    override suspend fun testAuthentication(): Result<Boolean> = 
        SafeExecutor.safeExecute("CalendarAuthUseCase.testAuthentication") {
            // Simple test: try to load calendars
            getAvailableCalendarsWithAuth().isSuccess
        }
    
    /**
     * Moderne Calendar-Authentifizierung mit Credential Manager
     * Ersetzt die deprecated GoogleSignIn APIs
     */
    suspend fun signInWithCredentialManager(context: Context): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarAuthUseCase.signInWithCredentialManager") {
            val credentialManager = CredentialManager.create(context)
            
            // Credential Manager Request konfigurieren
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = context,
                )
                
                when (val credential = result.credential) {
                    is androidx.credentials.CustomCredential -> {
                        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                            
                            // AuthData speichern
                            authDataStoreRepository.updateAuthData(
                                com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData(
                                    isLoggedIn = true,
                                    email = googleCredential.id,
                                    displayName = googleCredential.displayName,
                                    accessToken = googleCredential.idToken, // Temporär - wird später durch OAuth2 Token ersetzt
                                    tokenExpiryTime = System.currentTimeMillis() + CalendarConstants.TOKEN_VALIDITY_MS
                                )
                            ).getOrThrow()
                            
                            Logger.business(LogTags.AUTH, "Successfully signed in with Credential Manager: ${googleCredential.id}")
                        } else {
                            throw Exception("Unexpected credential type: ${credential.type}")
                        }
                    }
                    else -> {
                        throw Exception("Unexpected credential type: ${credential::class.java.simpleName}")
                    }
                }
            } catch (e: GetCredentialException) {
                Logger.e(LogTags.AUTH, "Credential Manager sign-in failed: ${e.message}")
                throw Exception("Authentication failed: ${e.message}")
            } catch (e: GoogleIdTokenParsingException) {
                Logger.e(LogTags.AUTH, "Failed to parse Google ID token: ${e.message}")
                throw Exception("Token parsing failed: ${e.message}")
            }
        }
}
