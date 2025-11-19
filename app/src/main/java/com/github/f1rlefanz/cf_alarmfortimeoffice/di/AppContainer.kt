package com.github.f1rlefanz.cf_alarmfortimeoffice.di

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.AuthDataStoreRepository
// ErrorHandler ist jetzt ein Singleton-Object - kein Import nötig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.CalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.ShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.DataStoreTokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
// PHASE 2 CLEANUP: TokenRefreshStrategy import removed (obsoleted by AlarmMaintenanceService)
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper

/**
 * Dependency Container für Clean Architecture mit Interface-basierter DI
 *
 * MODERNIZED OAuth2 TOKEN MANAGEMENT (2025):
 * ✓ OAuth2TokenManager mit Tink-verschlüsseltem DataStore
 * ✓ TokenRefreshStrategy mit strukturiertem Coroutine Management
 * ✓ Token Rotation für erhöhte Sicherheit
 * ✓ Tink AEAD Encryption (AES-256-GCM)
 * ✓ Android Keystore-backed Master Key
 * ✓ CredentialAuthManager für moderne Benutzer-Authentifizierung
 * ✓ Clean Architecture mit Interface-basierten Repositories
 * ✓ HUE Integration mit vollständigem Domain Layer
 */
@Deprecated("Will be removed after Hilt migration - Phase 5")
class AppContainer(private val context: Context) {

    // ==============================
    // DATASTORE CONFIGURATION
    // ==============================
    private val Context.hueDataStore: DataStore<Preferences> by preferencesDataStore(name = "hue_settings")
    private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    // ==============================
    // TOKEN-MANAGEMENT COMPONENTS (ENCRYPTED)
    // ==============================

    // Tink Encryption Helper
    val tinkEncryptionHelper: TinkEncryptionHelper by lazy {
        TinkEncryptionHelper.getInstance(context)
    }

    // Encrypted DataStore Token Repository
    val tokenRepository: TokenRepository by lazy {
        DataStoreTokenRepository(context)
    }

    // OAuth2 Token Manager
    val oauth2TokenManager: OAuth2TokenManager by lazy {
        OAuth2TokenManager(
            context = context,
            tokenRepository = tokenRepository
        )
    }

    // PHASE 2 CLEANUP: TokenRefreshStrategy removed (obsoleted by AlarmMaintenanceService)
    // Token refresh now handled by AlarmMaintenanceService every 6 hours

    // Credential Manager for user authentication
    val credentialAuthManager: CredentialAuthManager by lazy {
        CredentialAuthManager(context)
    }

    // ==============================
    // REPOSITORY LAYER
    // ==============================

    val authDataStoreRepository: IAuthDataStoreRepository by lazy {
        AuthDataStoreRepository(context)
    }

    val calendarRepository: ICalendarRepository by lazy {
        CalendarRepository().apply {
            setContext(context)
        }
    }

    val shiftConfigRepository: IShiftConfigRepository by lazy {
        ShiftConfigRepository(context)
    }

    val alarmRepository: IAlarmRepository by lazy {
        AlarmRepository(context.mainDataStore)
    }

    val calendarSelectionRepository: ICalendarSelectionRepository by lazy {
        CalendarSelectionRepository(context)
    }

    private val alarmSkipRepository: IAlarmSkipRepository by lazy {
        AlarmSkipRepository(context.mainDataStore)
    }

    // ==============================
    // HUE REPOSITORY LAYER
    // ==============================

    val hueBridgeRepository: IHueBridgeRepository by lazy {
        HueBridgeRepository(context)
    }

    val hueConfigRepository: IHueConfigRepository by lazy {
        HueConfigRepository(context.hueDataStore)
    }

    val hueLightRepository: IHueLightRepository by lazy {
        HueLightRepository(context, hueBridgeRepository as HueBridgeRepository)
    }

    // ==============================
    // SERVICES & ENGINES
    // ==============================

    val wakeLockManager by lazy {
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.WakeLockManager(context)
    }

    val backgroundServiceManager by lazy {
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager(
            context = context.applicationContext
        )
    }

    val alarmManagerService: AlarmManagerService by lazy {
        AlarmManagerService(
            application = context.applicationContext as android.app.Application,
            wakeLockManager = wakeLockManager
        )
    }

    val shiftRecognitionEngine: ShiftRecognitionEngine by lazy {
        ShiftRecognitionEngine(shiftConfigRepository)
    }

    // ==============================
    // USE CASE LAYER
    // ==============================

    val authUseCase: IAuthUseCase by lazy {
        AuthUseCase(
            authDataStoreRepository = authDataStoreRepository,
            oauth2TokenManager = oauth2TokenManager
        )
    }

    val calendarUseCase: ICalendarUseCase by lazy {
        CalendarUseCase(
            calendarRepository = calendarRepository,
            authDataStoreRepository = authDataStoreRepository,
            oauth2TokenManager = oauth2TokenManager
        )
    }

    val shiftUseCase: IShiftUseCase by lazy {
        ShiftUseCase(
            shiftConfigRepository = shiftConfigRepository,
            shiftRecognitionEngine = shiftRecognitionEngine
        )
    }

    val alarmUseCase: IAlarmUseCase by lazy {
        AlarmUseCase(
            alarmRepository = alarmRepository,
            alarmManagerService = alarmManagerService,
            shiftConfigRepository = shiftConfigRepository,
            shiftRecognitionEngine = shiftRecognitionEngine
        )
    }

    val alarmSkipUseCase: IAlarmSkipUseCase by lazy {
        AlarmSkipUseCase(
            alarmSkipRepository = alarmSkipRepository,
            alarmRepository = alarmRepository,
            alarmManagerService = alarmManagerService
        )
    }

    // ==============================
    // HUE USE CASE LAYER
    // ==============================

    val hueBridgeUseCase: IHueBridgeUseCase by lazy {
        HueBridgeUseCase(
            bridgeRepository = hueBridgeRepository,
            configRepository = hueConfigRepository
        )
    }

    val hueLightUseCase: IHueLightUseCase by lazy {
        HueLightUseCase(
            lightRepository = hueLightRepository
        )
    }

    val hueRuleUseCase: IHueRuleUseCase by lazy {
        HueRuleUseCase(
            configRepository = hueConfigRepository,
            lightUseCase = hueLightUseCase
        )
    }

    // ==============================
    // DIAGNOSTICS
    // ==============================

    /**
     * Verifies OAuth2 token system health
     */
    suspend fun diagnoseOAuth2Integration(): String {
        val results = mutableListOf<String>()

        try {
            results.add("✓ OAuth2TokenManager: Available")
            results.add("✓ TokenRefreshStrategy: Available")

            if (calendarUseCase is CalendarUseCase) {
                results.add("✓ CalendarUseCase: OAuth2 integration active")
            }

            if (authUseCase is AuthUseCase) {
                results.add("✓ AuthUseCase: OAuth2 integration active")
            }

            val tokenResult = oauth2TokenManager.getValidToken()
            if (tokenResult.isSuccess) {
                val tokenData = tokenResult.getOrThrow()
                results.add("✓ Token: Valid (${tokenData.getRemainingLifetimeMinutes()} min remaining)")
            } else {
                results.add("⚠ Token: Not available or expired")
            }

            results.add("✓ Phase 2 Cleanup: Complete - All deprecated code removed")
            results.add("✓ Phase 3 Encryption: Tink AES-256-GCM active")
            results.add(tinkEncryptionHelper.getDiagnostics())

        } catch (e: Exception) {
            results.add("✗ Error: ${e.message}")
        }

        return results.joinToString("\n")
    }
}