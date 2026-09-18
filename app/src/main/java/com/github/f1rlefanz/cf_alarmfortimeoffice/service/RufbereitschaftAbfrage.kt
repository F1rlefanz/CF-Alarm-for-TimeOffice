package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stuendliche Kalender-Abfrage an Rufbereitschafts-Tagen.
 *
 * DER VORFALL (16.09.2026, Fairphone, Log ausgewertet): Rufbereitschaft (AD1) den ganzen Tag. Um
 * 08:51 ruft der Chef an und traegt in TimeOffice einen Spaetdienst ein; Weckzeit dafuer 12:30.
 * Die 6h-Wartung lief um 08:10 (Kalender-Abfrage uebersprungen: Daten 6h alt, Puffer > 7 Tage,
 * naechster Wecker 59h entfernt) und haette erst um 14:10 wieder gefragt. Der 3h-Vorab-Worker
 * (`CalendarPreAlarmRefreshScheduler`) plant nur fuer Wecker, die es schon GIBT. Dazwischen hat
 * niemand nachgesehen. Um 13:26 oeffnete der Nutzer die App: "Skipping alarm in the past:
 * Spaetschicht". Kein Fehler in der Weckerkette - der App fehlte eine Abfrage im richtigen
 * Zeitfenster, und zwar an genau dem Tag, an dem sie wusste, dass ein Abruf wahrscheinlich ist.
 *
 * WAS DAS HIER TUT: Solange eine Schichtspanne mit `ShiftDefinition.isOnCall` laeuft, feuert
 * jede volle Stunde ein exakter Alarm auf [RufbereitschaftAbfrageReceiver], und der startet den
 * ganz normalen Wartungslauf mit `forceSync = true` (Kalender laden + Delta-Sync, am Lade-Gate
 * vorbei). Kein zweiter Sync-Pfad, keine zweite Wartungsimplementierung - dieselbe Haltung wie
 * `WartungNetzNachholer` und `TimezoneChangeReceiver`.
 *
 * KEIN ZWEITER PLANER DER 6h-KETTE (Invariante aus CLAUDE.md): eigener Request-Code
 * ([REQUEST_CODE]), eigener Receiver, eigener Slot. Die 6h-Kette weiss von dieser hier nichts;
 * ihr `finally` ruft lediglich [reschedule], genau wie fuer Dimmer, DND und Pre-Alarm-Refresh.
 * [reschedule] ist der EINZIGE Planer dieser Kette und rechnet immer vom Ist-Zustand aus
 * (selbstkorrigierend wie der DND-Tick): es gibt nichts zu "nachstellen".
 *
 * WARUM NICHT EINFACH DAS LADE-GATE DER 6h-WARTUNG AENDERN: Das Gate entscheidet nur, ob ein
 * ohnehin laufender Lauf laedt. Der Lauf um 08:10 lag VOR dem Eintrag um 08:51, der um 14:10
 * NACH der Weckzeit. Es fehlte nicht die Entscheidung zu laden, es fehlten die Laeufe dazwischen.
 *
 * WARUM EXAKTE ALARME UND KEIN WORKMANAGER-PERIODIC: Ein PeriodicWorkRequest darf im Doze um
 * Stunden verschoben werden, und genau die Stunde ist hier der Punkt. Die Vorlage ist die
 * 6h-Kette selbst (setExactAndAllowWhileIdle mit inexaktem Fallback ohne Berechtigung).
 *
 * WAS ES NICHT LEISTET: Ein Abruf, der weniger als eine Stunde vor der Weckzeit eingetragen
 * wird, kann immer noch zu spaet kommen. Google Kalender und TimeOffice melden Aenderungen nicht
 * (siehe `cfalarm-kalender-und-schichten`, "Kein echtes Push moeglich"); enger als stuendlich
 * abzufragen waere an einem ganzen Rufbereitschaftstag ein Akku-Preis fuer wenig Gewinn.
 */
@Singleton
class RufbereitschaftAbfrage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shiftSpanStore: ShiftSpanStore,
    private val shiftConfigRepository: IShiftConfigRepository,
    private val freieTageStore: FreieTageStore,
    private val masterPausePrefs: MasterPausePrefs,
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /** NICHT 0/8801/8802 (6h-Wartung), 7710 (Dimmer) oder 7712 (DND) - eigene Kette. */
        const val REQUEST_CODE = 8803

        val INTERVALL_MS: Long = TimeUnit.HOURS.toMillis(1)

        /**
         * Naechste geplante Abfrage (Epoch-Millis), 0 = keine. Laufzeitzustand DIESES Geraets -
         * steht deshalb in `ConfigBackupFilter` auf der Ausschlussliste. Angezeigt im Status-Tab,
         * damit die Kette eine Oberflaeche hat ("Eine Funktion ohne Bedienoberflaeche gibt es
         * fuer den Nutzer nicht").
         */
        const val KEY_NAECHSTE_ABFRAGE_NAME = "rufbereitschaft_naechste_abfrage"
        // Als LITERAL, damit die Schluessel-Inventur (Pruefrunde6BackupSchluesselInventurTest) ihn sieht.
        private val KEY_NAECHSTE_ABFRAGE = longPreferencesKey("rufbereitschaft_naechste_abfrage")

        /**
         * Ein Termin muss mindestens so weit in der Zukunft liegen. Der Tick selbst plant seinen
         * Nachfolger (ueber das `finally` der Wartung); laege "jetzt" durch Uhren-Schlupf ein paar
         * Millisekunden VOR dem gerade gefeuerten Termin, kaeme derselbe Termin noch einmal heraus
         * und der Wartungslauf liefe sofort doppelt.
         */
        val MINDEST_ABSTAND_MS: Long = TimeUnit.MINUTES.toMillis(1)

        /**
         * REIN UND TESTBAR: der naechste Abfrage-Zeitpunkt, oder `null`, wenn keine
         * Rufbereitschafts-Spanne (mehr) ansteht.
         *
         * Je Spanne mit passendem Namen: der erste Termin ist ihr Beginn, danach jede volle
         * Stunde ab Beginn; genommen wird der frueheste, der mehr als [mindestAbstandMs] nach
         * [now] liegt. Termine ab dem Spannen-ENDE zaehlen nicht - danach kann kein Abruf mehr
         * kommen, den diese Spanne rechtfertigt. Ueber alle Spannen gewinnt der frueheste.
         *
         * "Volle Stunde ab Beginn" statt Wanduhr-Stunde: die Spanne einer ganztaegigen
         * Rufbereitschaft beginnt um 00:00, dann ist beides gleich; bei einer Spanne ab 21:00
         * kommt die erste Abfrage um 22:00 statt um 21:xx.
         */
        internal fun naechsteAbfrage(
            spans: List<ShiftSpan>,
            onCallNames: Set<String>,
            now: Long,
            intervallMs: Long = INTERVALL_MS,
            mindestAbstandMs: Long = MINDEST_ABSTAND_MS
        ): Long? {
            val fruehestens = now + mindestAbstandMs
            return spans
                .asSequence()
                .filter { it.shiftName in onCallNames && it.endTime > it.startTime }
                .mapNotNull { span ->
                    val kandidat = if (span.startTime > fruehestens) {
                        span.startTime
                    } else {
                        // Naechster Rasterpunkt ECHT nach `fruehestens`.
                        val vergangen = fruehestens - span.startTime
                        span.startTime + (vergangen / intervallMs + 1) * intervallMs
                    }
                    kandidat.takeIf { it < span.endTime }
                }
                .minOrNull()
        }

        /** Fuer die Status-Karte: naechste geplante Abfrage, 0 = keine. */
        suspend fun naechsteAbfrageZeit(context: Context): Long {
            val dataStore = EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .mainDataStore()
            return dataStore.data.first()[KEY_NAECHSTE_ABFRAGE] ?: 0L
        }

        private fun pendingIntent(context: Context) =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, RufbereitschaftAbfrageReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    /**
     * Rechnet die naechste Abfrage vom Ist-Zustand neu und stellt sie (oder raeumt sie ab).
     * Wirft nie - alle Aufrufer stehen in einem Best-effort-Block, und ein Fehler hier darf weder
     * Wartung noch Boot-Recovery stoeren.
     */
    suspend fun reschedule() {
        try {
            // Master-Pause-Backstop, zentral wie bei Dimmer/DND: pausiert heisst keine Kette.
            if (masterPausePrefs.pausedNow()) {
                cancel()
                return
            }

            val onCallNames = shiftConfigRepository.getCurrentShiftConfig()
                .map { config -> config.definitions.filter { it.isOnCall }.map { it.name }.toSet() }
                .getOrElse { error ->
                    // NICHT abraeumen: ein unlesbarer Konfigurations-Read sagt nichts darueber,
                    // ob heute Rufbereitschaft ist. Eine bereits gestellte Abfrage bleibt stehen.
                    Logger.w(LogTags.MAINTENANCE, "Rufbereitschafts-Abfrage: Schicht-Konfiguration nicht lesbar - Planung unveraendert", error)
                    return
                }
            if (onCallNames.isEmpty()) {
                cancel()
                return
            }

            val spans = shiftSpanStore.spansNow().getOrElse { error ->
                Logger.w(LogTags.MAINTENANCE, "Rufbereitschafts-Abfrage: Schichtspannen nicht lesbar - Planung unveraendert", error)
                return
            }
            // Ein freigegebener Tag ist kein Dienst - also auch keine Rufbereitschaft. Dieselbe
            // Funktion wie bei Dimmer und DND, damit ein Tag nicht hier frei ist und dort nicht.
            val wirksam = FreieTageStore.filtereSpannen(spans, freieTageStore.freieTageNow(), ZoneId.systemDefault())

            val next = naechsteAbfrage(wirksam, onCallNames, System.currentTimeMillis())
            if (next == null) {
                cancel()
                return
            }
            stelle(next)
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ Rufbereitschafts-Abfrage konnte nicht geplant werden", e)
        }
    }

    /** Raeumt die Kette ab (Master-Pause, keine Rufbereitschaft). Wirft nie. */
    suspend fun cancel() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
            merkeNaechste(0L)
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "Rufbereitschafts-Abfrage konnte nicht abgeraeumt werden", e)
        }
    }

    private suspend fun stelle(zeitpunkt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        // Explizite SDK_INT-Verzweigung wie in AlarmMaintenanceService.scheduleNext - der Lint
        // versteht diese Form sicher.
        val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canBeExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeitpunkt, pi)
        } else {
            Logger.w(LogTags.MAINTENANCE, "Keine Berechtigung fuer exakte Alarme - Rufbereitschafts-Abfrage laeuft ungenau")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeitpunkt, pi)
        }
        merkeNaechste(zeitpunkt)
        Logger.business(LogTags.MAINTENANCE, "📞 Rufbereitschaft: naechste Kalender-Abfrage ${Date(zeitpunkt)} (exakt=$canBeExact)")
    }

    private suspend fun merkeNaechste(zeitpunkt: Long) {
        try {
            dataStore.edit { it[KEY_NAECHSTE_ABFRAGE] = zeitpunkt }
        } catch (e: Exception) {
            // Nur die Anzeige - die Kette selbst steht bereits im AlarmManager.
            Logger.w(LogTags.MAINTENANCE, "Rufbereitschafts-Abfrage: Anzeige-Merker nicht geschrieben", e)
        }
    }
}
