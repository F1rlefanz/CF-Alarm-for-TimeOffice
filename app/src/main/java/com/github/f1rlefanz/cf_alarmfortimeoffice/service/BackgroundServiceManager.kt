package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-Singleton: Diagnose-Stempel beim App-Start und Sofort-Wartung nach der Anmeldung, hinter
 * dem Master-Pause-Gate.
 */
@Singleton
class BackgroundServiceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val masterPausePrefs: com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
) {

    /**
     * Markiert den Start der Hintergrunddienste - inzwischen NUR im Log.
     *
     * Bis v1.45 schrieb diese Funktion Diagnose-Stempel in die SharedPreferences
     * `background_services` (Startzeit, Geraet, Version), die NIEMAND las (#130, G5-08). Der
     * Schreibzugriff war die heikelste Zeile der Klasse: sie entsteht bei JEDEM Prozessstart, auch
     * im Direct-Boot-Prozess vor der ersten Entsperrung, und ein CE-Zugriff dort hatte schon einmal
     * "Unable to create application" ausgeloest (gefunden mit `tools/geraet/pruefe_direct_boot.py`,
     * 17.08.2026). Ohne Leser gab es keinen Grund, das Risiko zu tragen. Eine alte Datei bleibt auf
     * bestehenden Geraeten liegen; die Backup-Regeln schliessen sie weiter aus.
     */
    fun initializeBackgroundServices() {
        Logger.business(
            LogTags.TOKEN,
            "🚀 Background services initialized (AlarmMaintenanceService via AuthViewModel)"
        )
    }
    
    /**
     * Startet einen sofortigen Wartungslauf — aber nur, wenn es etwas zu warten gibt.
     *
     * Wird vom Auth-Callback gerufen, und der feuert auf ZWEI Wegen:
     *   1. Erst-Onboarding — die Kalender-Auswahl kommt erst NACH der Anmeldung. Ein Lauf hier
     *      hatte nie eine Chance: er endete zuverlaessig mit "W No calendars selected, skipping"
     *      und schob dem Nutzer eine Notification hin ("Bitte oeffne die App und waehle die
     *      gewuenschten Kalender aus") — waehrend der genau davorstand und das gerade tat.
     *      Dazu ein sichtbarer Vordergrund-Service mitten im Onboarding, fuer nichts.
     *   2. Re-Autorisierung (Token weggefallen, Nutzer stimmt neu zu) — hier sind die Kalender
     *      laengst gewaehlt, und der Sofort-Lauf ist der Sinn der Sache: direkt nachsynchronisieren.
     *
     * Die Kalender-Auswahl trennt beide Faelle sauber. Ist sie leer, ist Onboarding im Gang; die
     * 6h-Kette stellt MainScreen an jedem Onboarding-Ausgang per AlarmMaintenanceService.scheduleNext()
     * - hier faellt also nichts aus, nur der Leerlauf weg.
     *
     * Die Notification bleibt damit ehrlich: Sie kann jetzt nur noch einen Nutzer erreichen, der
     * das Onboarding abgeschlossen und danach alle Kalender abgewaehlt hat — dann stimmt sie auch.
     */
    suspend fun initializeMaintenanceService() {
        if (masterPausePrefs.pausedNow()) {
            Logger.business(LogTags.MAINTENANCE, "Master-Pause aktiv - Initialisierung uebersprungen")
            return
        }
        // DataStore-Read statt StateFlow-.value: der StateFlow startet leer, "noch nicht geladen" saehe
        // aus wie "nichts ausgewaehlt" - gerade bei Re-Autorisierung in frischem Prozess.
        val selectedCalendars = calendarSelectionRepository.getCurrentSelectedCalendarIds()
            .getOrElse { error ->
                // Nicht lesbar ist NICHT "nichts ausgewaehlt" - im Zweifel den Wartungslauf
                // starten (er prueft die Auswahl selbst erneut und bricht sauber ab).
                Logger.w(
                    LogTags.MAINTENANCE,
                    "Kalenderauswahl nicht lesbar - Wartungslauf wird trotzdem angestossen",
                    error
                )
                null
            }
        if (selectedCalendars != null && selectedCalendars.isEmpty()) {
            Logger.business(
                LogTags.MAINTENANCE,
                "⏭️ Kein Wartungslauf: noch keine Kalender ausgewaehlt (Onboarding laeuft)"
            )
            return
        }

        Logger.business(LogTags.MAINTENANCE, "🚀 Initializing AlarmMaintenanceService")

        try {
            AlarmMaintenanceService.start(context)

            Logger.business(LogTags.MAINTENANCE, "✅ AlarmMaintenanceService initialized")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ Failed to initialize maintenance service", e)
        }
    }
}
