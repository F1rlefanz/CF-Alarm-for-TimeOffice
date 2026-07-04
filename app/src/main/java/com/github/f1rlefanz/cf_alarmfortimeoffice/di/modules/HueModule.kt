package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module für Hue Bridge Dependencies
 * 
 * KRITISCH: HueBridgeConnectionManager MUSS getInstance() Pattern behalten!
 * - Thread-safe Singleton mit WeakReference für Memory-Leak Prevention
 * - Kritisch für 24/7 Alarm-Funktionalität
 * - Managed eigene Background-Jobs mit CoroutineScope
 */
@Module
@InstallIn(SingletonComponent::class)
object HueModule {
    
    @Provides
    @Singleton
    fun provideHueBridgeConnectionManager(
        @ApplicationContext context: Context
    ): HueBridgeConnectionManager = HueBridgeConnectionManager.getInstance(context)

    /**
     * Brueckt den manuell konstruierten [HueSmartScheduler]-Singleton in den Hilt-Graphen
     * (dieselbe getInstance()-Instanz). So koennen ViewModels ihn injizieren, statt ihn per
     * getInstance() an Hilt vorbeizuziehen. Der Scheduler behaelt sein getInstance()/Worker-Muster.
     */
    @Provides
    @Singleton
    fun provideHueSmartScheduler(
        @ApplicationContext context: Context
    ): HueSmartScheduler = HueSmartScheduler.getInstance(context)
}
