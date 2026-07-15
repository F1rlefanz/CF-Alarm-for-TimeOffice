package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
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
 *
 * HIER LIEGEN NUR DIE UNVERSCHLÜSSELTEN STORES. Der Token-Store gehört bewusst NICHT dazu:
 * `DataStoreTokenRepository` baut ihn sich selbst über `EncryptedDataStoreFactory`
 * (`token_data_v2_encrypted`, Tink-verschlüsselt) und injiziert von hier nur den Context.
 * Wer Tokens sucht, sucht dort — nicht in diesem Modul.
 */

// DataStore Extensions - NICHT Proto!
private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.hueDataStore: DataStore<Preferences> by preferencesDataStore(name = "hue_settings")

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
