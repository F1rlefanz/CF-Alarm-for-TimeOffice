package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.TokenDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module für DataStore und Core Data Dependencies
 * 
 * WICHTIG: Nur Preferences DataStore (KEIN Proto DataStore!)
 * TinkEncryptionHelper bleibt als getInstance() Singleton
 */

// DataStore Extensions - NICHT Proto!
private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.hueDataStore: DataStore<Preferences> by preferencesDataStore(name = "hue_settings")
private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "oauth_tokens")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    
    @Provides
    @Singleton
    @MainDataStore
    fun provideMainDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.mainDataStore
    
    @Provides
    @Singleton
    @HueDataStore
    fun provideHueDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.hueDataStore
    
    @Provides
    @Singleton
    @TokenDataStore
    fun provideTokenDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.tokenDataStore
    
    // KRITISCH: TinkEncryptionHelper MUSS als Singleton bleiben!
    // Security-kritische Komponente mit getInstance() Pattern
    @Provides
    @Singleton
    fun provideTinkEncryptionHelper(
        @ApplicationContext context: Context
    ): TinkEncryptionHelper = TinkEncryptionHelper.getInstance(context)
    
    // ErrorHandler ist bereits ein Kotlin object - Singleton by design
    @Provides
    @Singleton
    fun provideErrorHandler(): ErrorHandler = ErrorHandler
}
