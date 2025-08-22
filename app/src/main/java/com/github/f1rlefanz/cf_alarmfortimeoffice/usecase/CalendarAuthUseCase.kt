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
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase für Calendar Authentication - implementiert ICalendarAuthUseCase
 * 
 * REFACTORED:
 * ✅ Implementiert ICalendarAuthUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interfaces statt konkrete Implementierungen
 * ✅ Proper Token Handling mit OAuth2 Integration
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Erweiterte Business Logic für Auth-Management
 */
@Suppress("DEPRECATION") // GoogleSignIn APIs: Complex migration required, keeping for stability
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
    
    // Legacy methods für Kompatibilität
    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(CalendarScopes.CALENDAR_READONLY))
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestServerAuthCode(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
    }
    
    /**
     * FIXED: Proper Token Extraction and Storage
     * 
     * Das Problem war: serverAuthCode ist KEIN Access Token!
     * Lösung: Verwende das tatsächliche Access Token vom Google Account
     */
    suspend fun saveAccessToken(account: GoogleSignInAccount, context: Context): Result<Unit> = 
        withContext(Dispatchers.IO) {
            SafeExecutor.safeExecute("CalendarAuthUseCase.saveAccessToken") {
                
                // Try to get the actual access token from the account
                // Note: GoogleSignInAccount doesn't directly expose access token
                // We need to use GoogleApiClient or credential manager
                
                // For now, use a working approach: get token through GoogleAuthUtil
                val accountName = account.email ?: throw Exception("No email in account")
                
                try {
                    // Use GoogleAuthUtil to get OAuth 2.0 token
                    val scopes = "oauth2:${CalendarScopes.CALENDAR_READONLY}"
                    @Suppress("DEPRECATION") // GoogleAuthUtil: Complex migration to Credential Manager API
                    val token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                        context,
                        accountName,
                        scopes
                    )
                    
                    if (token.isNotEmpty()) {
                        authDataStoreRepository.updateAuthData(
                            com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData(
                                isLoggedIn = true,
                                email = account.email,
                                displayName = account.displayName,
                                accessToken = token,
                                tokenExpiryTime = System.currentTimeMillis() + CalendarConstants.TOKEN_VALIDITY_MS
                            )
                        ).getOrThrow()
                        
                        Logger.business(LogTags.AUTH, "Access token retrieved and saved successfully")
                    } else {
                        throw Exception("Empty token received")
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.AUTH, "Failed to get OAuth token, trying ID token fallback: ${e.message}")
                    
                    // Fallback: Use ID Token (not ideal but may work for some APIs)
                    val idToken = account.idToken
                    if (idToken != null) {
                        Logger.w(LogTags.AUTH, "Using ID Token as fallback - limited functionality expected")
                        authDataStoreRepository.updateAuthData(
                            com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData(
                                isLoggedIn = true,
                                email = account.email,
                                displayName = account.displayName,
                                accessToken = idToken,
                                tokenExpiryTime = System.currentTimeMillis() + CalendarConstants.TOKEN_VALIDITY_MS
                            )
                        ).getOrThrow()

                    } else {
                        throw Exception("No valid token available: ${e.message}")
                    }
                }
            }
        }
}
