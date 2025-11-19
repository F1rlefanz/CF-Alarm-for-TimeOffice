package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
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
 */
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
        @ApplicationContext context: Context
    ): AlarmManagerService = AlarmManagerService(context)
    
    @Provides
    @Singleton
    fun provideBackgroundServiceManager(
        @ApplicationContext context: Context
    ): BackgroundServiceManager = BackgroundServiceManager(context)
    
    @Provides
    @Singleton
    fun provideShiftRecognitionEngine(): ShiftRecognitionEngine = ShiftRecognitionEngine()
}
