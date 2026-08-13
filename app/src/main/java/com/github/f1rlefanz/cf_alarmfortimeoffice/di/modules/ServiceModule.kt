package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.app.Application
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module für Service Dependencies
 * 
 * Stellt alle Service-bezogenen Komponenten bereit
 * OAuth2TokenManager, AlarmManagerService, etc.
 * 
 * NOTE: BackgroundServiceManager is not provided here because it has its own
 * @Singleton @Inject constructor and is automatically provided by Hilt.
 * 
 * @suppress unused - All @Provides methods are used by Hilt at compile-time via annotation processing
 */
@Suppress("unused") // Hilt uses these methods via annotation processing
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {
    
    @Provides
    @Singleton
    fun provideCredentialAuthManager(
        @ApplicationContext context: Context
    ): CredentialAuthManager = CredentialAuthManager(context)
    
    @Provides
    @Singleton
    fun provideOAuth2TokenManager(
        @ApplicationContext context: Context,
        tokenRepository: TokenRepository
    ): OAuth2TokenManager = OAuth2TokenManager(context, tokenRepository)
    
    @Provides
    @Singleton
    fun provideAlarmManagerService(
        application: Application
    ): AlarmManagerService = AlarmManagerService(application)
    
    @Provides
    @Singleton
    fun provideShiftRecognitionEngine(
        shiftConfigRepository: IShiftConfigRepository
    ): ShiftRecognitionEngine = ShiftRecognitionEngine(shiftConfigRepository)
}
