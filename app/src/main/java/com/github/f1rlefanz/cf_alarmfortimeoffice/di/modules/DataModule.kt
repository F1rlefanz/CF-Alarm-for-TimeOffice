package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
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
 *
 * HIER LIEGEN NUR DIE UNVERSCHLÜSSELTEN STORES. Der Token-Store gehört bewusst NICHT dazu:
 * `DataStoreTokenRepository` baut ihn sich selbst über `EncryptedDataStoreFactory`
 * (`token_data_v2_encrypted`, Tink-verschlüsselt) und injiziert von hier nur den Context.
 * Wer Tokens sucht, sucht dort — nicht in diesem Modul. `TinkEncryptionHelper` wird ebenfalls
 * NICHT hier bereitgestellt: einziger Zugriffsweg ist `TinkEncryptionHelper.getInstance()` in
 * `EncryptedDataStoreFactory` (der frühere Hilt-Provider war ein ungenutzter Leichenrest der
 * Hilt-Migration und hätte den CE-Storage-Keyset-Read in fremde DI-Graphen gezogen —
 * inklusive der directBootAware-Komponenten).
 */

// DataStore Extensions - NICHT Proto!
//
// corruptionHandler: Ohne ihn blockiert eine beschädigte preferences_pb NICHT nur jedes Lesen,
// sondern auch jedes Schreiben (DataStore liest vor jedem Write erneut) — und zwar dauerhaft, weil
// die kaputte Datei liegen bleibt. Im "settings"-Store liegen Alarme, Master-Pause, AlarmPrefs,
// Skip-Zustand, Dimmer- und DND-Konfiguration: ein Defekt hätte den Wecker reboot-fest stumm
// gelassen, ohne jede Selbstheilung außer "App-Daten löschen". Bewusster Preis: die betroffene
// Datei fällt EINMAL auf Werkszustand zurück (sie ist ohnehin unlesbar). Gleiches Muster wie
// CalendarSelectionRepository.
//
// "Werkszustand" ist dabei NICHT harmlos, deshalb wird der Fall laut geloggt statt still ersetzt:
// `MasterPausePrefs` liest bei fehlendem Schlüssel `false` — eine AKTIVE Master-Pause (Urlaub)
// endet damit lautlos, der Backstop in `syncAlarms()` greift nicht mehr und Wecker/Dimmer/DND/Hue
// planen wieder. Ebenso still verschwinden Dimm-Regeln und die DND-Konfiguration. Eine Sicherung
// wie `ShiftConfigRepository.backupBrokenConfig()` ist hier nicht möglich: dort ist nur EIN
// String-Wert defekt, hier das protobuf der ganzen Datei — es gibt nichts Lesbares zu retten.
// Diese Log-Zeile ist damit die einzige Spur, an der ein "meine Einstellungen sind weg" später
// überhaupt zuzuordnen ist. Nicht entfernen.
private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = {
        Logger.w(
            LogTags.DATASTORE,
            "⚠️ 'settings' war beschaedigt und wurde durch einen leeren Zustand ersetzt: Alarme, " +
                "AlarmPrefs, Skip-Zustand, Dimm-Regeln und DND-Konfiguration sind auf Werkszustand " +
                "zurueck - eine AKTIVE Master-Pause ist damit ebenfalls beendet"
        )
        emptyPreferences()
    })
)
private val Context.hueDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hue_settings",
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = {
        Logger.w(
            LogTags.DATASTORE,
            "⚠️ 'hue_settings' war beschaedigt und wurde durch einen leeren Zustand ersetzt - " +
                "Bridge-Kopplung und Hue-Regeln muessen neu eingerichtet werden"
        )
        emptyPreferences()
    })
)

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
    
    // ErrorHandler ist bereits ein Kotlin object - Singleton by design
    @Provides
    @Singleton
    fun provideErrorHandler(): ErrorHandler = ErrorHandler
}
