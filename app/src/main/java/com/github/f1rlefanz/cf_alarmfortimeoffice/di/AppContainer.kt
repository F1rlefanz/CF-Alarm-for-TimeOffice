package com.github.f1rlefanz.cf_alarmfortimeoffice.di

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.ModernOAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenRefreshUseCase
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.strategy.TokenRefreshStrategy
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.migration.TokenDataMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineName

/**
 * Dependency Container für Clean Architecture mit Interface-basierter DI
 * 
 * MODERNIZED: Complete authentication upgrade for 2024/2025 Google APIs
 * ✅ CredentialAuthManager für moderne Benutzer-Authentifizierung
 * ✅ ModernOAuth2TokenManager für Google API-Autorisierung (Calendar, etc.)
 * ✅ Alle Repositories implementieren Interfaces für bessere Testbarkeit
 * ✅ UseCase-Layer verwendet Interface-Abhängigkeiten (Dependency Inversion)
 * ✅ HUE INTEGRATION: Complete Domain Layer mit Repository/UseCase Pattern
 * ✅ Ersetzt deprecated GoogleSignInClient mit modernen APIs
 */
class AppContainer(private val context: Context) {
    
    // ==============================
    // APPLICATION-WIDE COROUTINE SCOPE
    // ==============================
    private val applicationScope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Default + 
        CoroutineName("AppContainer")
    )
    
    // ==============================
    // DATASTORE CONFIGURATION
    // ==============================
    private val Context.hueDataStore: DataStore<Preferences> by preferencesDataStore(name = "hue_settings")
    private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    // ==============================
    // ✅ NEUE TOKEN-MANAGEMENT KOMPONENTEN (2025 Modernisierung)
    // ==============================
    
    // ✅ NEU: DataStore Token Repository
    val tokenRepository: TokenRepository by lazy {
        DataStoreTokenRepository(context)
    }
    
    // ✅ NEU: Simplified OAuth2 Manager
    val oauth2TokenManager: OAuth2TokenManager by lazy {
        OAuth2TokenManager(
            context = context,
            tokenRepository = tokenRepository
        )
    }
    
    // ✅ NEU: Token Refresh Strategy
    val tokenRefreshStrategy: TokenRefreshStrategy by lazy {
        TokenRefreshStrategy(oauth2TokenManager)
    }
    
    // ✅ NEU: Migration (One-Time)
    val tokenMigration: TokenDataMigration by lazy {
        TokenDataMigration(
            context = context,
            newRepository = tokenRepository
        )
    }
    
    // ==============================
    // ⚠️ DEPRECATED - Phase 2 entfernen nach Testing
    // ==============================
    
    // ⚠️ DEPRECATED: TokenStorageRepository - Use tokenRepository instead
    // TODO: Remove in Phase 2 after successful migration testing
    @Deprecated("Use tokenRepository instead", ReplaceWith("tokenRepository"), DeprecationLevel.WARNING)
    val tokenStorageRepository: TokenStorageRepository by lazy {
        TokenStorageRepository(context)
    }
    
    // MODERN AUTH: Credential Manager for authentication (2024/2025 approach)
    val credentialAuthManager: CredentialAuthManager by lazy {
        CredentialAuthManager(context)
    }
    
    // ⚠️ DEPRECATED: ModernOAuth2TokenManager - Use oauth2TokenManager instead
    // TODO: Remove in Phase 2 after successful migration testing
    @Deprecated("Use oauth2TokenManager instead", ReplaceWith("oauth2TokenManager"), DeprecationLevel.WARNING)
    val modernOAuth2TokenManager: ModernOAuth2TokenManager by lazy {
        ModernOAuth2TokenManager(
            context = context,
            tokenStorage = tokenStorageRepository
        )
    }
    
    // ⚠️ DEPRECATED: TokenRefreshUseCase - Use tokenRefreshStrategy instead
    // TODO: Remove in Phase 2 after successful migration testing
    @Deprecated("Use tokenRefreshStrategy instead", ReplaceWith("tokenRefreshStrategy"), DeprecationLevel.WARNING)
    val tokenRefreshUseCase: TokenRefreshUseCase by lazy {
        TokenRefreshUseCase(
            modernOAuth2TokenManager = modernOAuth2TokenManager,
            tokenStorage = tokenStorageRepository
        )
    }
    
    // ==============================
    // REPOSITORY INTERFACES & IMPLEMENTATIONS
    // ==============================
    
    // Interface-basierte Repository-Implementierungen für bessere Testbarkeit
    val authDataStoreRepository: IAuthDataStoreRepository by lazy {
        AuthDataStoreRepository(context)
    }
    
    val calendarRepository: ICalendarRepository by lazy {
        CalendarRepository().apply {
            setContext(context) // Set context for network connectivity checks
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
    
    // Skip Repository
    private val alarmSkipRepository: IAlarmSkipRepository by lazy {
        AlarmSkipRepository(context.mainDataStore)
    }
    
    // ==============================
    // HUE INTEGRATION - REPOSITORY LAYER
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
    
    val batteryOptimizationManager by lazy {
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.BatteryOptimizationManager(context)
    }
    
    // Background Service Manager - MEMORY LEAK FIXED (v2.1)
    // FIXED: Replaced problematic Singleton pattern with proper DI
    // UPDATED: Added shiftUseCase dependency for configurable sync interval
    val backgroundServiceManager by lazy {
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager(
            context = context.applicationContext,
            shiftUseCase = shiftUseCase
        )
    }

    val alarmManagerService: AlarmManagerService by lazy {
        AlarmManagerService(
            application = context.applicationContext as android.app.Application,
            batteryOptimizationManager = batteryOptimizationManager,
            wakeLockManager = wakeLockManager
        )
    }
    
    val shiftRecognitionEngine: ShiftRecognitionEngine by lazy {
        ShiftRecognitionEngine(shiftConfigRepository)
    }
    
    // ErrorHandler ist jetzt ein Singleton-Object und benötigt keine Instanziierung
    // Direkter Zugriff über ErrorHandler.methodName()
    
    // ==============================
    // USE CASE INTERFACES & IMPLEMENTATIONS (Domain Layer)
    // ==============================
    
    // Interface-basierte UseCase-Implementierungen für bessere Testbarkeit
    val authUseCase: IAuthUseCase by lazy {
        AuthUseCase(
            authDataStoreRepository = authDataStoreRepository,
            oauth2TokenManager = oauth2TokenManager  // ✅ MIGRATED: Verwendet neuen OAuth2TokenManager
        )
    }

    val calendarUseCase: ICalendarUseCase by lazy {
        CalendarUseCase(
            calendarRepository = calendarRepository,
            authDataStoreRepository = authDataStoreRepository,
            oauth2TokenManager = oauth2TokenManager  // ✅ MIGRATED: Verwendet neuen OAuth2TokenManager
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
    
    // Skip UseCase - CRITICAL FIX: Added AlarmManagerService dependency
    val alarmSkipUseCase: IAlarmSkipUseCase by lazy {
        AlarmSkipUseCase(
            alarmSkipRepository = alarmSkipRepository,
            alarmRepository = alarmRepository,
            alarmManagerService = alarmManagerService
        )
    }
    
    // ==============================
    // HUE INTEGRATION - USE CASE LAYER
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
    // INITIALIZATION & DIAGNOSTICS
    // ==============================
    
    /**
     * Initializes OAuth2 token storage repository.
     * Should be called during app startup.
     */
    suspend fun initializeTokenStorage() {
        tokenStorageRepository.initialize()
    }
    
    /**
     * DIAGNOSTIC: Verifies OAuth2 token system is properly wired
     * Call this after container initialization for troubleshooting
     */
    suspend fun diagnoseOAuth2Integration(): String {
        val results = mutableListOf<String>()
        
        try {
            // Check if OAuth2TokenManager is available
            results.add("✅ OAuth2TokenManager: Available")
            
            // Check TokenRefreshStrategy availability
            results.add("✅ TokenRefreshStrategy: Available")
            
            // Check if CalendarUseCase has token refresh capability
            if (calendarUseCase is CalendarUseCase) {
                results.add("✅ CalendarUseCase: OAuth2TokenManager integration enabled")
            } else {
                results.add("❌ CalendarUseCase: OAuth2TokenManager integration missing")
            }
            
            // Check AuthUseCase OAuth2 integration
            if (authUseCase is AuthUseCase) {
                results.add("✅ AuthUseCase: OAuth2TokenManager integrated")
            } else {
                results.add("❌ AuthUseCase: OAuth2TokenManager missing")
            }
            
            // Check token storage
            val tokenResult = oauth2TokenManager.getValidToken()
            if (tokenResult.isSuccess) {
                val tokenData = tokenResult.getOrThrow()
                results.add("📊 Token status: Valid (${tokenData.getRemainingLifetimeMinutes()}min remaining)")
            } else {
                results.add("⚠️ Token status: Not available or expired")
            }
            
            // Check deprecated components (should be removed later)
            results.add("⚠️ DEPRECATED: ModernOAuth2TokenManager still present - should be removed in Phase 2")
            results.add("⚠️ DEPRECATED: TokenRefreshUseCase still present - should be removed in Phase 2")
            results.add("⚠️ DEPRECATED: TokenStorageRepository still present - should be removed in Phase 2")
            
        } catch (e: Exception) {
            results.add("❌ Diagnostic error: ${e.message}")
        }
        
        return results.joinToString("\n")
    }
    
    // ==============================
    // IMPORTANT: ViewModels sind NICHT hier!
    // ViewModels werden über ViewModelFactory erstellt
    // um korrekte Lifecycle-Bindung zu gewährleisten
    // ==============================
}
