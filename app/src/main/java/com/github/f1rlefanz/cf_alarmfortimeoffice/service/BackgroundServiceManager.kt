package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.os.Build
import androidx.core.content.edit
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
     * `by lazy`, NICHT als sofortiger Initializer - und das ist kein Stilentscheid.
     *
     * Diese Klasse ist das ERSTE @Inject-Feld von [CFAlarmApplication] (im generierten
     * Hilt-Code das erste `inject...`), sie entsteht also bei JEDEM Prozessstart in der
     * Feld-Injektion - auch in dem, den Android VOR der ersten Entsperrung fuer den
     * directBootAware BootReceiver startet. `getSharedPreferences()` auf dem normalen
     * (CREDENTIAL-ENCRYPTED) Application-Context wirft dort ab targetSdk 26:
     * "SharedPreferences in credential encrypted storage are not available until after user
     * (id 0) is unlocked". Aus der Feld-Injektion heraus wird daraus "Unable to create
     * application" - der Prozess stirbt, BEVOR `BootReceiver.onReceive()` laeuft, und der
     * Direct-Boot-Restore der Alarme und der schwebenden Snoozes findet NIE statt.
     *
     * Das ist derselbe Absturz, der am 11.08.2026 ueber `HueSmartScheduler`/WorkManager
     * gefunden wurde - dieselbe Fehlerklasse, zweite Stelle. Am EMULATOR ist er hier nicht
     * sichtbar: ohne Bildschirmsperre gilt der Nutzer schon beim LOCKED_BOOT_COMPLETED als
     * entsperrt, CE-Storage ist lesbar und die Exception bleibt aus. Auf einem Geraet MIT
     * PIN (Fairphone) nicht. Wer hier wieder einen sofortigen Initializer hinschreibt, baut
     * einen Absturz, den kein Emulator ohne Bildschirmsperre und kein Unit-Test zeigt.
     *
     * [initializeBackgroundServices] laeuft auch im Direct-Boot-Prozess - gemessen mit
     * `tools/geraet/pruefe_direct_boot.py` (17.08.2026). `by lazy` rettet nur den Prozessstart; jeder
     * ZUGRIFF fragt zusaetzlich [userUnlocked].
     */
    private val preferences by lazy {
        context.getSharedPreferences("background_services", Context.MODE_PRIVATE)
    }

    /**
     * Ist der Nutzer entsperrt, also CREDENTIAL-ENCRYPTED Storage lesbar?
     *
     * Gleiche Umsetzung wie in `AlarmRepository`, inklusive Fehlerrichtung: im Zweifel `true`.
     * Ein ueberfluessiger Versuch landet im vorhandenen try/catch; ein faelschlich
     * uebersprungener Schreibvorgang waere stiller Datenverlust.
     */
    private val userUnlocked: Boolean
        get() = context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true

    /**
     * Schreibt Diagnose-Stempel (nur bei entsperrtem Nutzer).
     */
    fun initializeBackgroundServices() {
        Logger.business(
            LogTags.TOKEN,
            "🚀 Initializing background services (Phase 1 Migration - Worker removed)"
        )

        // Vor der ersten Entsperrung ist CE-Storage nicht lesbar. Hier stehen ausschliesslich
        // DIAGNOSE-Werte - die Wartungskette haengt nicht daran (die startet ueber
        // AuthViewModel bzw. den Wartungs-Anker des BootReceivers). Ohne dieses Gate warf der
        // Schreibvorgang im Direct-Boot-Prozess und landete als ERROR MIT STACKTRACE im Log;
        // Release-Logs enthalten WARN+, also war es dort sichtbar. Genau solches Rauschen macht
        // den naechsten echten Vorfall unauswertbar, und die Meldung "Failed to initialize
        // background services" liest sich dramatischer, als der Sachverhalt ist.
        // Gemessen am Emulator mit PIN via tools/geraet/pruefe_direct_boot.py (17.08.2026).
        if (!userUnlocked) {
            Logger.d(
                LogTags.TOKEN,
                "⏭️ Direct Boot: Diagnose-Werte uebersprungen (CE-Storage noch nicht lesbar). " +
                    "Kein Funktionsverlust - die Wartungskette haengt nicht daran."
            )
            return
        }

        try {
            // Mark services as started
            preferences.edit {
                putLong("services_started_at", System.currentTimeMillis())
                putString("device_info", "${Build.MANUFACTURER} ${Build.MODEL}")
                putString("version", "Phase1-ExactAlarm")
            }

            Logger.business(
                LogTags.TOKEN,
                "✅ Background services initialized (AlarmMaintenanceService via AuthViewModel)"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to initialize background services", e)
        }
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
