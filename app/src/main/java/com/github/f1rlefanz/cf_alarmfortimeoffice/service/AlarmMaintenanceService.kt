package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Hilt EntryPoint that lets [AlarmMaintenanceService.getLastMaintenanceTime] (a static/companion
 * helper called from Compose UI, e.g. SettingsTabContent, without an AlarmMaintenanceService
 * instance) reach the Hilt-managed [MainDataStore]. Same pattern as HueSmartSchedulerEntryPoint /
 * HueBridgeConnectionManagerEntryPoint.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmMaintenanceEntryPoint {
    @MainDataStore
    fun mainDataStore(): DataStore<Preferences>

    /**
     * Der Device-Protected-Spiegel der Master-Pause — synchron lesbar und damit der einzige Weg,
     * den Pausenzustand in einem `onReceive()` zu pruefen (`MasterPausePrefs` liegt im
     * CE-Storage und ist nur suspendierend lesbar). Gebraucht vom
     * [AlarmMaintenanceBroadcastReceiver], wenn der Wiederanlauf-Wachhund feuert.
     */
    fun directBootAlarmStore(): DirectBootAlarmStore
}

/**
 * Der offene Raeumauftrag nach einer Kalender-Abwahl — die Entscheidung, was damit zu geschehen
 * ist. Android-frei und als eigenes Top-Level-Objekt, aus demselben Grund wie
 * [MaintenanceLoadDecision]: pruefbar ohne eine `android.app.Service`-Ableitung.
 *
 * WOFUER DAS DA IST: `CalendarViewModel` haelt den Auftrag im
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore] fest, BEVOR
 * es raeumt. Stirbt der Prozess dazwischen (App weggewischt, Force-Stop) oder scheitert das
 * Raeumen, bleibt der Auftrag liegen — und die App wird ihn von allein nie wieder anfassen, weil
 * die Auswahl beim naechsten Start von Anfang an leer ist und der Uebergangs-Merker deshalb nicht
 * mehr greift. Die 6h-Wartung ist der Hintergrundpfad, der ohnehin laeuft; hier gehoert er hin.
 *
 * Die Master-Pause pruefen die Aufrufer VORHER — waehrend der Pause steht ohnehin kein Wecker,
 * und ein Sync wuerde ueber den zentralen Backstop zusaetzlich einen schwebenden Snooze
 * abbrechen.
 */
internal object AbwahlRaeumauftrag {

    /** Was nach dem Durchgang mit dem dauerhaften Merker zu geschehen ist. */
    enum class Merker {
        /** Nichts anfassen: der Auftrag ist weiterhin offen (oder es gab nie einen). */
        BEHALTEN,

        /** Erledigt oder hinfaellig — der Merker darf weg. */
        LOESCHEN
    }

    data class Ergebnis(val geraeumt: Boolean, val merker: Merker)

    /**
     * @param auftragOffen liegt ein Auftrag vor? Ein LESEFEHLER des Merkers ist hier `false` mit
     *   [Merker.BEHALTEN] beim Aufrufer — er darf nie als "kein Auftrag" WEGGESCHRIEBEN werden.
     * @param auswahlIstLeer die LESBAR ermittelte Kalenderauswahl ist leer. Ist sie nicht lesbar,
     *   ruft der Aufrufer diese Funktion gar nicht erst auf: "nicht lesbar" ist keine Abwahl.
     * @param shiftConfigLesen `null` = Konfiguration nicht lesbar. Dann wird NICHT geraeumt
     *   (fail-safe, wie ueberall sonst: ein Defekt im Konfigurations-Store darf keine Wecker
     *   kosten) und der Auftrag bleibt fuer den naechsten Lauf offen.
     * @param raeumen fuehrt `syncAlarms(emptyList(), config)` aus und meldet den Erfolg. Genau
     *   dieser Weg schont manuelle Wecker (`keepManualAlarms`) und schreibt zugleich die
     *   Schichtspannen leer, sonst dimmten Dimmer und DND weiter nach dem alten Dienstplan.
     */
    suspend fun abarbeiten(
        auftragOffen: Boolean,
        auswahlIstLeer: Boolean,
        shiftConfigLesen: suspend () -> com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig?,
        raeumen: suspend (com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig) -> Boolean
    ): Ergebnis {
        if (!auftragOffen) return Ergebnis(geraeumt = false, merker = Merker.BEHALTEN)

        // Es ist wieder ein Kalender ausgewaehlt: der naechste Delta-Sync deckt die Wecker ab,
        // der Auftrag ist hinfaellig. Hier zu raeumen waere sogar falsch - es wuerde die Wecker
        // des neu gewaehlten Dienstplans loeschen.
        if (!auswahlIstLeer) return Ergebnis(geraeumt = false, merker = Merker.LOESCHEN)

        val config = shiftConfigLesen()
            ?: return Ergebnis(geraeumt = false, merker = Merker.BEHALTEN)

        // Ohne Automatik gibt es keine kalenderbasierten Wecker mehr (das Abschalten selbst raeumt
        // sie). `syncAlarms()` naehme in diesem Zweig einen anderen Weg und loeschte auch MANUELLE
        // Wecker - die haben mit dem Kalender nichts zu tun und ueberleben eine Abwahl.
        if (!config.autoAlarmEnabled) return Ergebnis(geraeumt = false, merker = Merker.LOESCHEN)

        return if (raeumen(config)) {
            Ergebnis(geraeumt = true, merker = Merker.LOESCHEN)
        } else {
            // Gescheitert: der Auftrag bleibt offen, der naechste Lauf versucht es erneut.
            Ergebnis(geraeumt = false, merker = Merker.BEHALTEN)
        }
    }
}

/**
 * Reine, Android-freie Entscheidungslogik der 6h-Wartung — bewusst als eigenes Top-Level-Objekt
 * (nicht im Companion von [AlarmMaintenanceService]), damit Unit-Tests sie ohne das Laden einer
 * `android.app.Service`-Ableitung pruefen koennen.
 */
internal object MaintenanceLoadDecision {

    /** Puffer-Kriterium wie bisher: liegt der letzte geplante Alarm naeher, muss nachgeladen werden. */
    const val MIN_BUFFER_DAYS = 7L

    /**
     * Liegt der NAECHSTE Alarm innerhalb dieses Fensters, wird immer geladen.
     *
     * Warum 48h: eine Dienstplan-Aenderung schlaegt umso teurer zu, je naeher der betroffene
     * Wecker ist (falsche Weckzeit / Wecker fuer eine gestrichene Schicht). 48h deckt bei
     * 6h-Takt die letzten ~8 Wartungslaeufe vor dem Wecker ab und ueberlappt sicher mit dem
     * 3h-Vorab-Worker (CalendarPreAlarmRefreshWorker), der ohne Netz ausfallen kann.
     */
    const val NEAR_ALARM_HOURS = 48L

    /**
     * Maximales Alter der letzten ECHTEN Kalender-Abfrage, danach wird unabhaengig vom Puffer geladen.
     *
     * Warum 12h: die Kette laeuft alle 6h, also laedt hoechstens jeder ZWEITE Lauf (2 statt 4
     * Google-Kalender-Abfragen pro Tag) — der Sparzweck des alten Puffer-Gates bleibt damit
     * erhalten. Gleichzeitig ist eine Streichung/Verschiebung, die weiter als 48h in der Zukunft
     * liegt, nach spaetestens 12h erkannt statt (wie vorher) erst wenn der Puffer unter 7 Tage
     * faellt oder der Nutzer die App oeffnet. Ohne dieses Kriterium war die 6h-Wartung im
     * eingeschwungenen Zustand (Dienstplan 14 Tage gepflegt) dauerhaft blind.
     */
    const val MAX_DATA_AGE_HOURS = 12L

    /**
     * Entscheidet, ob Kalender-Events geladen werden muessen.
     *
     * @param futureAlarmTriggerTimes Trigger-Zeitpunkte aller noch zukuenftigen Alarme (Epoch-Millis)
     * @param now aktuelle Zeit (Epoch-Millis)
     * @param lastEventLoad Zeitpunkt der letzten erfolgreichen, nicht-leeren Kalender-Abfrage
     *                      (0 = noch nie geladen)
     * @param forceSync erzwungener Lauf (z.B. Zeitzonen-Wechsel, Boot) — laedt immer
     */
    fun shouldLoadEvents(
        futureAlarmTriggerTimes: List<Long>,
        now: Long,
        lastEventLoad: Long,
        forceSync: Boolean
    ): Boolean {
        if (forceSync) return true
        if (futureAlarmTriggerTimes.isEmpty()) return true

        val dataAgeHours = TimeUnit.MILLISECONDS.toHours(now - lastEventLoad)
        if (lastEventLoad <= 0L || dataAgeHours >= MAX_DATA_AGE_HOURS) return true

        val bufferDays = TimeUnit.MILLISECONDS.toDays(futureAlarmTriggerTimes.max() - now)
        if (bufferDays < MIN_BUFFER_DAYS) return true

        val hoursToNextAlarm = TimeUnit.MILLISECONDS.toHours(futureAlarmTriggerTimes.min() - now)
        return hoursToNextAlarm <= NEAR_ALARM_HOURS
    }

    /**
     * Entscheidet, ob nach einem Ladevorgang synchronisiert wird.
     *
     * Bewusst NUR an "es liegen ueberhaupt Events vor" gehaengt, NICHT an "es gibt neue
     * Schichten": der Delta-Sync in `AlarmUseCase.syncAlarms()` ist idempotent und beherrscht als
     * einziger Ort auch UPDATE (Schicht verschoben, Event-ID unveraendert) und DELETE (Schicht
     * gestrichen/Krankschreibung). Frueher brach die Wartung bei `newShifts.isEmpty()` ab — genau
     * die beiden Faelle, fuer die der Delta-Sync gebaut wurde, erreichten ihn damit NIE.
     *
     * Die Leerlisten-Sperre ist Pflicht und kein Detail: ein gescheiterter Abruf kann heute
     * `success(emptyList())` liefern, und eine leere Eventliste ist fuer `syncAlarms()` das Signal
     * "alle Alarme loeschen". Fuer eine Wecker-App ist "leer" die gefaehrlichste Luege (gleiche
     * Fail-safe-Logik wie in `BootReceiver.performAlarmRecovery`: lieber ein veralteter Wecker als
     * gar keiner).
     */
    fun shouldSyncAfterLoad(loadedEventCount: Int): Boolean = loadedEventCount > 0
}

/**
 * Die Slots und Zeitpunkte der 6h-Wartungskette — Android-frei und als eigenes Top-Level-Objekt,
 * aus demselben Grund wie [MaintenanceLoadDecision]: pruefbar ohne das Laden einer
 * `android.app.Service`-Ableitung.
 */
internal object WartungsKettenPlanung {

    /**
     * Request-Code der 6h-Wartungskette. EINE Kette, EIN Slot — siehe
     * [AlarmMaintenanceService.scheduleNext].
     *
     * 0 ist der Wert, den BootReceiver, BackgroundServiceManager und MainScreen schon immer
     * benutzt haben; die zweite Kette lief auf 9999 und ist weg.
     */
    const val REGULAER_REQUEST_CODE = 0

    /**
     * EIGENER Slot fuer den Nachhol-Alarm aus [AlarmMaintenanceService.start], bewusst NICHT
     * [REGULAER_REQUEST_CODE].
     *
     * Das ist kein zweiter Planer der 6h-Kette (die Invariante dazu in CLAUDE.md gilt weiter):
     * dieser Alarm ist EINMALIG, plant sich nicht selbst nach und traegt deshalb einen eigenen
     * Code, damit er den einzigen Slot der rollierenden Kette nicht ueberschreibt. Wuerde er
     * Code 0 benutzen, ersetzte er den geplanten naechsten Wartungslauf - der Nachhol-Versuch
     * wuerde die Kette also um Stunden nach vorn ziehen und damit still veraendern.
     */
    const val NACHHOL_REQUEST_CODE = 8801

    /**
     * EIGENER Slot fuer den Wiederanlauf-Wachhund — er haelt die Kette am Leben, wenn dem Geraet
     * ihr einziger Alarm unter den Haenden geloescht wurde.
     *
     * WELCHER ABLAUF GING KAPUTT: Entzieht der Nutzer auf API 31/32 die Berechtigung
     * "Alarme & Erinnerungen" (dort haengt sie an SCHEDULE_EXACT_ALARM und ist jederzeit
     * entziehbar; ab API 33 traegt die App USE_EXACT_ALARM und der Fall existiert nicht), dann
     * loescht das System laut AlarmManager-Doku ALLE mit setExact, setExactAndAllowWhileIdle und
     * setAlarmClock gestellten Alarme — also auch den EINEN auf [REGULAER_REQUEST_CODE], an dem
     * die gesamte Kette haengt. Einen Broadcast gibt es dabei nicht ("This broadcast will *not* be
     * sent when the user revokes the permission"), und alle Aufrufer von
     * [AlarmMaintenanceService.start]/[AlarmMaintenanceService.scheduleNext] setzen Boot,
     * Zeitzonenwechsel oder ein Oeffnen der App voraus. Ohne diesen Wachhund war die Wartung bis
     * zum naechsten Neustart tot: keine neuen Wecker, kein Kalender-Sync, kein Dimmer-/DND-Tick —
     * und nichts davon sichtbar.
     *
     * WARUM DAS KEIN ZWEITER PLANER IST (die CLAUDE.md-Invariante gilt weiter): der Wachhund wird
     * ausschliesslich in [AlarmMaintenanceService.scheduleNext] gestellt, liegt IMMER
     * [WACHHUND_KARENZ_MINUTEN] HINTER dem regulaeren Lauf und wird von jedem erfolgreichen Lauf
     * mitverschoben. Feuert der regulaere Alarm, ist der Wachhund laengst wieder in der Zukunft,
     * bevor er drankaeme — zwei parallele Zyklen koennen so nicht entstehen. Er feuert nur, wenn
     * der regulaere Alarm gar nicht mehr da ist.
     *
     * WARUM INEXAKT GESTELLT: genau darum. `setAndAllowWhileIdle` steht NICHT auf der Loeschliste
     * des Berechtigungsentzugs — ein exakt gestellter Wachhund waere mit demselben Handgriff
     * mitgeloescht worden und damit wertlos.
     *
     * WAS ER LEISTET UND WAS NICHT (hier stand vorher zu viel): Ein inexakt gestellter Alarm
     * traegt beim Feuern KEINE Vordergrunddienst-Startfreigabe — nur ein EXAKTER Alarm steht auf
     * Androids Ausnahmeliste (dieselbe Grenze begruendet den Weck-Notausgang in `AlarmReceiver`
     * und den Fallback in `AlarmManagerService`). Im Zielzustand des Wachhunds ist die
     * Exact-Alarm-Berechtigung aber gerade entzogen. Sein Feuern kann den Wartungslauf also NICHT
     * erzwingen; abgewiesen wuerde er mit `ForegroundServiceStartNotAllowedException`.
     *
     * Was er tut, ist das, was aus dem Hintergrund immer erlaubt ist: er zieht die Kette wieder
     * auf ([AlarmMaintenanceService.scheduleNext] ist reine AlarmManager-Arbeit). Damit ist der
     * Slot kein Einweg-Slot mehr, und in dem Moment, in dem die Berechtigung zurueckkommt, stellt
     * derselbe Weg wieder EXAKTE Alarme — und deren Feuern darf den Dienst dann auch starten. Den
     * sofortigen Wiederanlauf im Vordergrund leistet die Statuskarte
     * (`ui/screens/tabs/StatusPermissionCards.kt`), nicht dieser Wachhund.
     */
    const val WACHHUND_REQUEST_CODE = 8802

    const val INTERVALL_STUNDEN = 6L

    /**
     * Abstand des Wachhunds zum regulaeren Lauf.
     *
     * Nicht kleiner: ein normaler Wartungslauf (Token-Refresh -> Kalender -> Alarme) muss fertig
     * werden und in seinem `finally` [AlarmMaintenanceService.scheduleNext] erreicht haben, bevor
     * der Wachhund drankaeme — sonst startet er den Service ein zweites Mal.
     */
    const val WACHHUND_KARENZ_MINUTEN = 30L

    /**
     * Die beiden Zeitpunkte EINER Runde. Faellt der Abstand auf null, feuert der Wachhund zusammen
     * mit dem regulaeren Lauf und erzeugt genau die zwei parallelen Zyklen, gegen die die
     * Ein-Planer-Invariante existiert.
     */
    data class Zeitpunkte(val regulaer: Long, val wiederanlauf: Long)

    fun zeitpunkte(jetzt: Long): Zeitpunkte {
        val regulaer = jetzt + TimeUnit.HOURS.toMillis(INTERVALL_STUNDEN)
        return Zeitpunkte(
            regulaer = regulaer,
            wiederanlauf = regulaer + TimeUnit.MINUTES.toMillis(WACHHUND_KARENZ_MINUTEN)
        )
    }

    /** Obergrenze fuer die Nachholversuche aus [AlarmMaintenanceService.start]. */
    const val MAX_NACHHOLVERSUCHE = 2

    /**
     * Darf ein abgelehnter Vordergrund-Start per Alarm nachgeholt werden?
     *
     * WELCHER ABLAUF OHNE DIESE BREMSE ENTSTAND: Der Nachholversuch war der Rueckfallpfad von
     * [AlarmMaintenanceService.start] und wurde OHNE Zaehler und OHNE Bedingung gestellt — bei
     * fehlender Exact-Alarm-Berechtigung eben inexakt. Nur traegt ein inexakt gestellter Alarm
     * beim Feuern KEINE Vordergrunddienst-Startfreigabe (siehe [WACHHUND_REQUEST_CODE]). Der
     * nachgeholte Start wurde also erneut abgewiesen, stellte den naechsten Nachholversuch, wurde
     * abgewiesen ... — eine sich selbst erhaltende RTC_WAKEUP-Schleife, die das Geraet dauerhaft
     * weckt, ohne je Erfolg zu haben. Aus "Wartung steht still" wurde "Wartung steht still UND
     * der Akku laeuft leer".
     *
     * Deshalb zwei Bedingungen: nachgeholt wird nur, was EXAKT gestellt werden kann (nur das
     * traegt die Freigabe) — und hoechstens [MAX_NACHHOLVERSUCHE] Mal, falls der Start aus einem
     * anderen Grund dauerhaft scheitert. Ohne Berechtigung ist die Wartung fuer diesen Anlass
     * verloren; zurueck kommt sie ueber den Wachhund und die Statuskarte, nicht ueber Wecken im
     * 10-Sekunden-Takt.
     */
    fun darfNachholen(bisherigeVersuche: Int, kannExakteAlarme: Boolean): Boolean =
        kannExakteAlarme && bisherigeVersuche < MAX_NACHHOLVERSUCHE
}

/**
 * Zaehlt die gleichzeitig laufenden Zyklen eines Service, der pro `onStartCommand` eine Coroutine
 * auf einem GETEILTEN Scope startet, und sagt, WANN mit WELCHER startId abgeraeumt werden darf.
 *
 * WARUM ES DAS GIBT: `stopSelf(startId)` stoppt genau dann, wenn `startId` der zuletzt vergebene
 * ist. Das schuetzt nur den Fall "der FRUEHERE Lauf wird zuerst fertig". Wird der SPAETERE zuerst
 * fertig — und das ist der wahrscheinliche Fall, weil ein erzwungener Lauf (Zeitzone, Boot,
 * Master-Pause-resume, Re-Login) im Netz haengt, waehrend der regulaere 6h-Lauf ueber
 * [MaintenanceLoadDecision.shouldLoadEvents] in Millisekunden mit "Puffer reicht" zurueckkehrt —
 * traf sein `stopSelf` den aktuellsten startId, Android zerstoerte den Service, `onDestroy` cancelte
 * den geteilten Scope und riss den noch laufenden frueheren Lauf mitten in `syncAlarms()` ab.
 * Zwischen `deleteAlarm()` und dem folgenden `saveAlarm()`/`scheduleSystemAlarm()` abgeschnitten
 * heisst: fuer diese Schicht existiert bis zum naechsten Wartungslauf KEIN Wecker — und gemeldet
 * haette es niemand, weil der andere Lauf "erfolgreich (uebersprungen)" loggte.
 *
 * Deshalb raeumt erst der ZULETZT endende Lauf ab, und zwar mit dem HOECHSTEN bis dahin gesehenen
 * startId (dem, den Android als "letzten Start" kennt).
 *
 * Bewusst ein eigenes, Android-freies Top-Level-Objekt (wie [MaintenanceLoadDecision]), damit
 * Unit-Tests es ohne das Laden einer `android.app.Service`-Ableitung pruefen koennen. Es wird auch
 * vom `DimNotificationService` benutzt, der exakt dasselbe Muster faehrt.
 *
 * Thread-Sicherheit: `onStart` kommt vom Main-Thread (onStartCommand), `onFinish` aus beliebigen
 * Coroutine-Threads. Zaehler und startId muessen GEMEINSAM konsistent sein (zwei Atomics reichen
 * nicht), deshalb ein Monitor statt `AtomicInteger`.
 */
internal class ServiceRunTracker {

    private val lock = Any()
    private var activeRuns = 0
    private var latestStartId = 0

    /** Meldet einen begonnenen Lauf an. Auf dem Main-Thread aufzurufen (onStartCommand). */
    fun onStart(startId: Int) = synchronized(lock) {
        activeRuns++
        // max() statt blindem Zuweisen: Android vergibt startIds aufsteigend, aber der Vergleich
        // macht die Invariante explizit — abgeraeumt wird mit dem hoechsten je gesehenen Start.
        if (startId > latestStartId) latestStartId = startId
    }

    /**
     * Meldet einen beendeten Lauf ab — MUSS aus einem `finally` kommen, damit auch der
     * Ausnahme- und der Abbruchpfad den Zaehler senken. Ein dauerhaft haengender Lauf haelt den
     * Service offen; das ist unveraendert zur Lage vor dieser Klasse (auch `stopSelf` stand schon
     * immer im `finally`) und wird von Androids eigenem Foreground-Service-Timeout gedeckelt.
     *
     * @return die startId fuer `stopSelf(...)`, oder `null`, wenn noch ein Lauf arbeitet.
     */
    fun onFinish(): Int? = synchronized(lock) {
        if (activeRuns > 0) activeRuns--
        if (activeRuns == 0) latestStartId else null
    }

    /** Nur fuer Tests/Diagnose: Anzahl der aktuell laufenden Zyklen. */
    fun activeRunCount(): Int = synchronized(lock) { activeRuns }
}

/**
 * AlarmMaintenanceService - Short-Lived Foreground Service
 *
 * ARCHITECTURE (Briefing 4.0):
 * - Runs as Foreground Service (visible notification for 10-30 seconds)
 * - Triggered by Exact Alarm every 6 hours
 * - Performs: Token Refresh → Health Check → Event Loading → Alarm Creation
 * - Self-Healing: Schedules next run before stopping
 *
 * HEALTH CHECK (siehe [MaintenanceLoadDecision.shouldLoadEvents]):
 * - Puffer des letzten geplanten Alarms (< 7 Tage → laden)
 * - Alter der letzten echten Kalender-Abfrage (>= 12h → laden)
 * - Naehe des naechsten Alarms (<= 48h → laden)
 * - Erzwungener Lauf (Zeitzonen-Wechsel, Boot) → immer laden
 *
 * LIFECYCLE:
 * 1. BroadcastReceiver starts service
 * 2. startForeground() - notification appears
 * 3. performMaintenance() in coroutine
 * 4. stopSelf() - notification disappears
 * 5. Service returns START_NOT_STICKY
 */
@AndroidEntryPoint
class AlarmMaintenanceService : Service() {

    @Inject lateinit var tokenManager: OAuth2TokenManager
    @Inject lateinit var alarmUseCase: IAlarmUseCase
    @Inject lateinit var calendarUseCase: ICalendarUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var calendarSelectionRepository: CalendarSelectionRepository
    @Inject lateinit var shiftRecognitionEngine: ShiftRecognitionEngine
    @Inject @MainDataStore lateinit var mainDataStore: DataStore<Preferences>

    @Inject
    lateinit var dimSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase

    @Inject
    lateinit var dndSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase

    @Inject
    lateinit var calendarPreAlarmRefreshScheduler: com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler

    @Inject
    lateinit var masterPausePrefs: com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs

    @Inject
    lateinit var calendarUnavailableNotifier: com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier

    @Inject
    lateinit var pendingDeselectionCleanupStore:
        com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Siehe [ServiceRunTracker]: erst der zuletzt endende Zyklus darf den Service abraeumen. */
    private val runTracker = ServiceRunTracker()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "alarm_maintenance"
        private const val NOTIFICATION_ID = 1001

        // Slots, Intervall und Karenz der Kette liegen in WartungsKettenPlanung - Android-frei und
        // damit pruefbar. Der Wachhund-Slot dort traegt die Begruendung, warum es ihn gibt.

        /** Kurz, aber nicht sofort: der Wurf kommt aus dem Hintergrund, ein Alarm darf das. */
        private const val CATCHUP_DELAY_MS = 10_000L

        /**
         * Erzwingt einen vollen Lauf (Kalender laden + synchronisieren), auch wenn der Puffer
         * reicht und die Daten frisch sind. Gesetzt vom [TimezoneChangeReceiver] und vom
         * Boot-Pfad — Anlaesse, bei denen die abgeleiteten Weckzeiten neu berechnet werden
         * MUESSEN, nicht nur "koennten".
         */
        const val EXTRA_FORCE_SYNC = "force_sync"

        /**
         * Zaehler der bereits unternommenen Nachholversuche — reist im Intent des Nachhol-Alarms
         * mit, damit [WartungsKettenPlanung.darfNachholen] eine Obergrenze durchsetzen kann. Ohne
         * ihn haette ein dauerhaft abgelehnter Vordergrund-Start sich selbst endlos nachgestellt.
         */
        const val EXTRA_NACHHOLVERSUCHE = "nachholversuche"

        /**
         * Markiert den Broadcast des Wiederanlauf-Wachhunds. Nur er zieht die Kette im Empfaenger
         * selbst wieder auf — der regulaere Lauf tut das in seinem eigenen `finally`.
         */
        const val EXTRA_WACHHUND = "wachhund"

        // CONSOLIDATION: moved off the standalone "cf_alarm_prefs" SharedPreferences file
        // and into the existing Hilt @MainDataStore.
        // NO MIGRATION: only the developer uses the app right now, so the old SharedPreferences
        // value is intentionally left behind - losing the last-maintenance timestamp once is harmless.
        private val KEY_LAST_MAINTENANCE = longPreferencesKey("last_maintenance_time")

        /**
         * Zeitpunkt der letzten ECHTEN, erfolgreichen und nicht-leeren Kalender-Abfrage.
         *
         * Bewusst ein EIGENER Schluessel und nicht [KEY_LAST_MAINTENANCE]: der wird auch im
         * Skip-Zweig und aus dem Vordergrund-Sync gestempelt ("Letzter Sync" in den
         * Einstellungen) und ist damit als Frische-Signal fuer die Kalenderdaten wertlos —
         * ein Skip-Lauf wuerde die Daten sonst dauerhaft "frisch" aussehen lassen und das
         * 12h-Kriterium aus [MaintenanceLoadDecision] aushebeln.
         */
        private val KEY_LAST_EVENT_LOAD = longPreferencesKey("last_event_load_time")

        /**
         * Gets the timestamp of the last successful maintenance.
         *
         * Suspends to read the @MainDataStore (resolved via Hilt EntryPoint, since this is a
         * static helper called without a Service instance - e.g. from SettingsTabContent's
         * LaunchedEffect). Callers must invoke this from a coroutine.
         */
        suspend fun getLastMaintenanceTime(context: Context): Long {
            val dataStore = EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .mainDataStore()
            return dataStore.data.first()[KEY_LAST_MAINTENANCE] ?: 0L
        }

        /**
         * Stamps "now" as the last sync time.
         *
         * Wird zusätzlich aus dem VORDERGRUND-Sync (CalendarViewModel → syncAlarms) aufgerufen,
         * damit "Letzter Sync" den tatsächlich letzten Sync widerspiegelt und nicht nur den
         * 6h-Hintergrundlauf. Ein alter Wert bleibt damit das ehrliche Signal "seit X nichts
         * synchronisiert" (weder Vordergrund noch Hintergrund).
         */
        suspend fun recordSyncTime(context: Context) {
            val dataStore = EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .mainDataStore()
            dataStore.edit { it[KEY_LAST_MAINTENANCE] = System.currentTimeMillis() }
        }

        /**
         * Startet den Wartungslauf. FAENGT den Fehlschlag - und zwar HIER, nicht bei den Aufrufern.
         *
         * `startForegroundService()` wirft ab Android 12 eine
         * `ForegroundServiceStartNotAllowedException` (eine `IllegalStateException`), wenn die App
         * im Hintergrund ist und der Anlass nicht auf Androids Ausnahmeliste steht. Auf der Liste
         * stehen u. a. BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED und das Feuern
         * eines Exact-Alarms - **`ACTION_TIMEZONE_CHANGED` steht dort NICHT**. Von den sechs
         * Aufrufstellen fing genau eine nicht (`TimezoneChangeReceiver`), und eine Exception aus
         * `onReceive()` reisst den Prozess mit: doppelter Schaden, denn ausgefallen waere damit
         * genau die Neuberechnung der Weckzeiten, fuer die dieser Receiver als einzige
         * Verteidigungslinie existiert.
         *
         * Der Fang steht deshalb an DIESER Stelle: sie deckt alle heutigen und kuenftigen Aufrufer
         * ab, statt sich darauf zu verlassen, dass jeder von ihnen daran denkt - dieselbe
         * Ueberlegung wie beim Master-Pause-Backstop in `syncAlarms()`.
         *
         * Statt es dabei zu belassen, wird per EXAKTEM Alarm nachgeholt: das Feuern eines exakten
         * Alarms IST ein erlaubter Anlass, der Lauf kommt also ~10 s spaeter doch zustande. Ohne
         * das waere die Zeitzonen-Korrektur bis zum naechsten regulaeren 6h-Lauf verzoegert.
         *
         * "EXAKT" ist dabei die tragende Bedingung und keine Feinheit — ein inexakt gestellter
         * Nachholversuch traegt die Freigabe NICHT und wuerde sich endlos selbst nachstellen.
         * Deshalb entscheidet [WartungsKettenPlanung.darfNachholen], ob ueberhaupt nachgeholt wird.
         *
         * @param forceSync ueberspringt das Lade-Gate ([MaintenanceLoadDecision.shouldLoadEvents])
         *   und erzwingt Kalender-Abfrage + Delta-Sync. Default false — die regulaere 6h-Kette
         *   und alle Bestandsaufrufer verhalten sich unveraendert.
         * @param nachholVersuche wie viele Nachholversuche dieser Anlass schon hinter sich hat.
         *   Setzt nur der [AlarmMaintenanceBroadcastReceiver] aus dem Intent des Nachhol-Alarms.
         */
        fun start(context: Context, forceSync: Boolean = false, nachholVersuche: Int = 0) {
            val intent = Intent(context, AlarmMaintenanceService::class.java)
                .putExtra(EXTRA_FORCE_SYNC, forceSync)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Logger.w(
                    LogTags.MAINTENANCE,
                    "⚠️ WARTUNG: Vordergrund-Start abgelehnt (App im Hintergrund, Anlass nicht " +
                        "ausgenommen) - Nachholversuch wird geprueft",
                    e
                )
                scheduleCatchUp(context, forceSync, nachholVersuche)
            }
        }

        /**
         * Nachhol-Alarm fuer [start] — gedeckelt, siehe [WartungsKettenPlanung.darfNachholen].
         *
         * Selbst wieder in try/catch: eine entzogene Exact-Alarm-Berechtigung darf aus dem
         * Fehlerpfad keinen zweiten Absturz machen.
         */
        private fun scheduleCatchUp(context: Context, forceSync: Boolean, bisherigeVersuche: Int) {
            try {
                val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
                val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                // NUR exakt und nur gedeckelt. Ein inexakt gestellter Nachholversuch traegt beim
                // Feuern keine Vordergrunddienst-Startfreigabe und wuerde sich selbst endlos
                // nachstellen - die Begruendung steht vollstaendig an darfNachholen().
                if (!WartungsKettenPlanung.darfNachholen(bisherigeVersuche, canBeExact)) {
                    Logger.w(
                        LogTags.MAINTENANCE,
                        "⚠️ WARTUNG: kein Nachholversuch (Versuche=$bisherigeVersuche, " +
                            "exakteAlarme=$canBeExact) - dieser Anlass faellt aus. Zurueck kommt " +
                            "die Kette ueber den Wiederanlauf-Wachhund bzw. die Statuskarte."
                    )
                    return
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WartungsKettenPlanung.NACHHOL_REQUEST_CODE,
                    Intent(context, AlarmMaintenanceBroadcastReceiver::class.java)
                        .putExtra(EXTRA_FORCE_SYNC, forceSync)
                        .putExtra(EXTRA_NACHHOLVERSUCHE, bisherigeVersuche + 1),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = System.currentTimeMillis() + CATCHUP_DELAY_MS
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Logger.w(
                    LogTags.MAINTENANCE,
                    "⚠️ WARTUNG: Nachholversuch ${bisherigeVersuche + 1}/" +
                        "${WartungsKettenPlanung.MAX_NACHHOLVERSUCHE} in ${CATCHUP_DELAY_MS / 1000}s geplant"
                )
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE, "❌ WARTUNG: auch der Nachhol-Alarm scheiterte", e)
            }
        }

        /**
         * Stellt den naechsten Wartungslauf (+6h) — der EINZIGE Planer der Kette.
         *
         * Frueher gab es einen zweiten: die Instanzmethode scheduleNextAlarm() stellte denselben
         * Alarm noch einmal, nur mit Request-Code 9999 statt 0. Verschiedene Request-Codes heissen
         * verschiedene PendingIntents, also ZWEI unabhaengige AlarmManager-Eintraege. Da der
         * finally-Block von [onStartCommand] ohnehin immer hier landet, plante jeder erfolgreiche
         * Lauf beide — Ergebnis: dauerhaft zwei Wartungszyklen alle 6h, im Abstand von
         * Millisekunden. Doppelte Google-Kalender-Abfragen, doppelte Arbeit, und genau die zwei
         * ueberlappenden Starts, gegen die stopSelf(startId) haerten musste.
         *
         * Die Pruefung auf canScheduleExactAlarms() stammt aus der geloeschten Methode und bleibt:
         * setExactAndAllowWhileIdle() wirft eine SecurityException, wenn die Berechtigung fehlt.
         * Ab API 33 traegt die App USE_EXACT_ALARM (bei Installation erteilt, nicht entziehbar),
         * auf 31/32 greift nur SCHEDULE_EXACT_ALARM — und das darf der Nutzer abschalten. Vorher
         * stand hier ein ungeprueftes setExactAndAllowWhileIdle().
         *
         * Seit dieser Runde stellt dieselbe Funktion zusaetzlich den Wiederanlauf-Wachhund
         * ([armiereWiederanlauf]). Das bleibt EIN Planer: beide Alarme entstehen hier und nur
         * hier, der Wachhund liegt immer hinter dem regulaeren Lauf und wird von jedem Lauf
         * mitverschoben. Warum es ihn braucht, steht an [WartungsKettenPlanung.WACHHUND_REQUEST_CODE].
         */
        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager

            val zeitpunkte = WartungsKettenPlanung.zeitpunkte(System.currentTimeMillis())
            val pendingIntent = wartungsPendingIntent(context, WartungsKettenPlanung.REGULAER_REQUEST_CODE)

            // Explizite SDK_INT-Verzweigung (nicht als ||-Kurzschluss): minSdk ist 26,
            // canScheduleExactAlarms() gibt es erst ab 31 - diese Form versteht der Lint sicher.
            val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    zeitpunkte.regulaer,
                    pendingIntent
                )
            } else {
                Logger.w(
                    LogTags.MAINTENANCE,
                    "Keine Berechtigung fuer exakte Alarme - Wartung laeuft ungenau (setAndAllowWhileIdle)"
                )
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    zeitpunkte.regulaer,
                    pendingIntent
                )
            }

            armiereWiederanlauf(context, alarmManager, zeitpunkte.wiederanlauf)

            Logger.business(
                LogTags.MAINTENANCE,
                "⏰ Next maintenance scheduled for ${Date(zeitpunkte.regulaer)} " +
                    "(Wiederanlauf-Wachhund ${Date(zeitpunkte.wiederanlauf)})"
            )
        }

        /**
         * EINE Stelle fuer den PendingIntent der Kette. Setzen und Abbrechen MUESSEN denselben
         * Request-Code, dieselbe Ziel-Komponente und dieselben Flags benutzen, sonst trifft
         * `alarmManager.cancel()` einen zweiten, unabhaengigen PendingIntent statt des gesetzten
         * Alarms — und die Master-Pause haette die Kette nur scheinbar gekappt.
         *
         * Das EXTRA gehoert bewusst nicht zu dieser Aufzaehlung: `Intent.filterEquals()` (und
         * damit das Zuordnen eines PendingIntent) vergleicht Action, Daten, Typ, Paket, Komponente
         * und Kategorien — nicht die Extras. Der Wachhund-Marker unterscheidet die beiden Alarme
         * also NICHT; das leistet allein der Request-Code. Er reist nur mit, damit der Empfaenger
         * weiss, wer ihn geweckt hat.
         */
        /*
         * KEIN expliziter Rueckgabetyp - der inferierte PLATTFORMTYP ist hier Absicht, und die
         * Zeile darf nicht "aufgeraeumt" werden. Mit `: PendingIntent` erzeugt Kotlin eine
         * Intrinsics-Null-Pruefung; im Unit-Test-Modus (isReturnDefaultValues -> die statische
         * Android-Methode liefert null) wirft die. Der Wurf lief in MasterPauseUseCase.pause() in
         * das per-Abhaengigkeit-try/catch und verschluckte dort BEIDE cancel()-Aufrufe der
         * Wartungskette: der Test sah "null Interaktionen" statt einer falschen Anzahl, also genau
         * die Zusicherung nicht mehr, die die Master-Pause traegt. Am Geraet ist es folgenlos
         * (getBroadcast liefert mit FLAG_UPDATE_CURRENT nie null), im Test verdeckt es alles.
         * `: PendingIntent?` ist keine Alternative - AlarmManager.cancel/setExact* sind @NonNull.
         */
        private fun wartungsPendingIntent(
            context: Context,
            requestCode: Int,
            istWachhund: Boolean = false
        ) =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, AlarmMaintenanceBroadcastReceiver::class.java)
                    .putExtra(EXTRA_WACHHUND, istWachhund),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * Stellt den Wiederanlauf-Wachhund — siehe [WartungsKettenPlanung.WACHHUND_REQUEST_CODE] fuer den
         * Ablauf, der ohne ihn kaputt ging.
         *
         * Bewusst IMMER inexakt, auch wenn die Berechtigung gerade da ist: exakt gestellt waere
         * er beim Entzug mitgeloescht worden und damit genau in dem Fall weg, fuer den er
         * existiert. Der Wurf wird gefangen: der Wachhund ist eine Zusatzsicherung, sein
         * Scheitern darf den regulaeren Lauf oben nicht mit umreissen.
         */
        private fun armiereWiederanlauf(
            context: Context,
            alarmManager: AlarmManager,
            triggerAt: Long
        ) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    wartungsPendingIntent(
                        context,
                        WartungsKettenPlanung.WACHHUND_REQUEST_CODE,
                        istWachhund = true
                    )
                )
            } catch (e: Exception) {
                Logger.w(
                    LogTags.MAINTENANCE,
                    "⚠️ WARTUNG: Wiederanlauf-Wachhund konnte nicht gestellt werden - ein " +
                        "Berechtigungsentzug bliebe bis zum naechsten Neustart unbemerkt",
                    e
                )
            }
        }

        /**
         * Kappt die 6h-Wartungskette (Master-Pause).
         *
         * Baut denselben Intent/PendingIntent wie [scheduleNext] (gleicher Request-Code
         * [WartungsKettenPlanung.REGULAER_REQUEST_CODE], gleiche Flags) — das ist Pflicht, damit
         * alarmManager.cancel() wirklich den Alarm trifft, den scheduleNext zuletzt gesetzt hat,
         * statt einen zweiten, unabhaengigen PendingIntent zu erzeugen.
         */
        fun cancelNext(context: Context) {
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager

            alarmManager.cancel(wartungsPendingIntent(context, WartungsKettenPlanung.REGULAER_REQUEST_CODE))

            // Der Wachhund MUSS mit weg: er startet die Kette aus sich heraus neu, und ein
            // Wiederanlauf trotz Master-Pause waere genau der Zustand, den pause() herstellen
            // soll. Vergisst man ihn, kaeme die pausierte Wartung 30 Minuten spaeter von selbst
            // zurueck.
            alarmManager.cancel(
                wartungsPendingIntent(
                    context,
                    WartungsKettenPlanung.WACHHUND_REQUEST_CODE,
                    istWachhund = true
                )
            )

            Logger.business(
                LogTags.MAINTENANCE,
                "⏸️ Wartungskette pausiert (Master-Pause aktiv) - regulaerer Lauf und " +
                    "Wiederanlauf-Wachhund abgebrochen"
            )
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Logger.d(LogTags.MAINTENANCE, "AlarmMaintenanceService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceSync = intent?.getBooleanExtra(EXTRA_FORCE_SYNC, false) == true
        Logger.business(
            LogTags.MAINTENANCE,
            "🔧 Maintenance service started${if (forceSync) " (erzwungener Lauf)" else ""}"
        )

        // Create notification and start foreground
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // VOR dem launch und auf dem Main-Thread: sonst koennte ein bereits laufender Zyklus
        // zwischen startForeground() und dem Anmelden dieses Laufs fertig werden, den Zaehler auf
        // 0 sehen und den Service abraeumen, waehrend dieser Start schon quittiert ist.
        runTracker.onStart(startId)

        // Perform maintenance in background
        serviceScope.launch {
            try {
                performMaintenance(forceSync)
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE, "Maintenance failed with exception", e)
            } finally {
                // withContext(NonCancellable): der Abschluss MUSS auch dann vollstaendig laufen,
                // wenn die Coroutine gecancelt wurde (onDestroy -> serviceScope.cancel(), z.B.
                // weil das System den Foreground-Service unter Speicherdruck stoppt). Vorher stand
                // hier als ERSTE Anweisung der suspendierende Read masterPausePrefs.pausedNow():
                // in einer gecancelten Coroutine wirft der sofort CancellationException, wodurch
                // WEDER scheduleNext() NOCH stopSelf(startId) je liefen. Damit war die Annahme der
                // Invariante "die 6h-Kette hat genau einen Planer, und der finally-Block deckt
                // jeden Pfad ab" verletzt: Request-Code 0 ist der einzige Slot - ohne
                // scheduleNext() war die rollierende Kette bis zum naechsten Boot/Re-Login tot.
                withContext(NonCancellable) {
                    // Eigenes try/catch um den DataStore-Read: ist @MainDataStore gerade nicht
                    // lesbar (IOException, korrupte Datei), darf das die Neuplanung nicht
                    // verhindern. Im Zweifel NICHT pausiert annehmen - ein ueberfluessiger
                    // Wartungslauf ist harmlos, eine abgerissene Kette ist der teuerste,
                    // weil unsichtbare Ausfall.
                    val paused = try {
                        masterPausePrefs.pausedNow()
                    } catch (e: Exception) {
                        Logger.w(
                            LogTags.MAINTENANCE,
                            "Master-Pause-Status nicht lesbar - Kette wird sicherheitshalber weitergeplant",
                            e
                        )
                        false
                    }

                    try {
                        // Master-Pause: Kette kappen statt neu zu planen, wenn der Nutzer pausiert hat.
                        if (paused) {
                            cancelNext(applicationContext)
                        } else {
                            scheduleNext(applicationContext)
                        }
                    } catch (e: Exception) {
                        Logger.e(LogTags.MAINTENANCE, "Neuplanung der Wartungskette fehlgeschlagen", e)
                    }

                    // Dimmer/DND/Pre-Alarm-Refresh laufen hier - NICHT mehr nur im tiefsten
                    // Erfolgszweig von performMaintenance(). Dort waren sie hinter fuenf Returns
                    // versteckt (Puffer reicht, keine Kalender, Ladefehler, keine neuen Schichten,
                    // Auto-Alarm aus), also gerade bei den HAEUFIGSTEN Laeufen unerreichbar - und
                    // der Zeitzonen-Wechsel-Pfad, der genau diese Neuberechnung braucht, lief
                    // dadurch praktisch immer ins Leere. Gleiches Argument wie bei scheduleNext():
                    // der finally-Block ist die einzige Stelle, die jeden Pfad abdeckt.
                    rescheduleSideChannels(paused)

                    // Abraeumen erst, wenn KEIN Zyklus mehr arbeitet - siehe [ServiceRunTracker].
                    //
                    // Wird der Service zweimal gestartet (z.B. der 6h-Alarm faellt mit einem Start
                    // nach der Autorisierung oder einem Zeitzonen-Wechsel zusammen), laufen zwei
                    // Zyklen auf demselben serviceScope. Das blanke stopSelf() des ERSTEN, der
                    // fertig wurde, loeste onDestroy() -> serviceScope.cancel() aus und riss den
                    // zweiten mitten in der Arbeit ab (JobCancellationException, Log 14.07.
                    // 22:07:30). Fuer eine Wecker-App ist das gefaehrlich: der abgeschnittene
                    // Zyklus koennte gerade zwischen deleteAlarm() und saveAlarm() stehen.
                    //
                    // Das damals eingesetzte stopSelf(startId) deckte davon nur eine Richtung ab -
                    // "der FRUEHERE Lauf wird zuerst fertig". Wird der SPAETERE zuerst fertig
                    // (regulaerer Lauf kehrt sofort mit "Puffer reicht" zurueck, waehrend ein
                    // forceSync-Lauf im Netz haengt), IST seine startId die zuletzt vergebene -
                    // Android zerstoerte den Service, und derselbe Abriss passierte doch. Deshalb
                    // entscheidet jetzt der Zaehler, und nur der zuletzt endende Zyklus ruft
                    // stopSelf - weiterhin mit startId, nie blank.
                    runTracker.onFinish()?.let { stopSelf(it) }
                }
            }
        }

        // Don't restart if killed by system
        return START_NOT_STICKY
    }

    /**
     * Dimmer-, DND- und Pre-Alarm-Refresh-Planung auffrischen (Best-effort).
     *
     * Jeder Block hat sein EIGENES try/catch: keiner der drei darf den jeweils anderen oder die
     * Wartungskette beeintraechtigen. Wirft daher nie - Aufrufer im finally-Block verlassen sich
     * darauf, dass danach noch stopSelf(startId) erreicht wird.
     *
     * Bei aktiver Master-Pause wird abgeschaltet statt neu geplant (gleiches Muster wie
     * `BootReceiver`); `disable()` ruehrt laut Invariante keine persistierten Toggles an.
     */
    private suspend fun rescheduleSideChannels(paused: Boolean) {
        try {
            if (paused) {
                dimSchedule.disable()
            } else {
                dimSchedule.applyCurrentState()
                dimSchedule.scheduleNextTransition()
            }
        } catch (e: Exception) {
            Logger.w(LogTags.DIMMER, "Wartung: Dimm-Reschedule fehlgeschlagen", e)
        }

        try {
            if (paused) {
                dndSchedule.disable()
            } else {
                dndSchedule.applyCurrentState()
                dndSchedule.scheduleNextTransition()
            }
        } catch (e: Exception) {
            Logger.w(LogTags.DND, "Wartung: DND-Reschedule fehlgeschlagen", e)
        }

        try {
            if (paused) {
                calendarPreAlarmRefreshScheduler.cancelAll()
            } else {
                calendarPreAlarmRefreshScheduler.reschedule()
            }
        } catch (e: Exception) {
            Logger.w(
                LogTags.BACKGROUND_WORKER,
                "Wartung: Pre-Alarm-Refresh-Reschedule fehlgeschlagen",
                e
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Logger.d(LogTags.MAINTENANCE, "AlarmMaintenanceService destroyed")
    }
    
    /**
     * Saves the current time as last maintenance timestamp in @MainDataStore.
     * Instance method (not companion) since the service already has the injected
     * mainDataStore available - no need to re-resolve it via EntryPoint.
     */
    private suspend fun saveMaintenanceTime() {
        mainDataStore.edit { prefs ->
            prefs[KEY_LAST_MAINTENANCE] = System.currentTimeMillis()
        }
    }

    /**
     * Stempelt den Zeitpunkt einer erfolgreichen, nicht-leeren Kalender-Abfrage.
     * Grundlage des 12h-Frische-Kriteriums in [MaintenanceLoadDecision].
     */
    private suspend fun saveEventLoadTime() {
        mainDataStore.edit { prefs ->
            prefs[KEY_LAST_EVENT_LOAD] = System.currentTimeMillis()
        }
    }

    /**
     * Arbeitet einen offenen Raeumauftrag nach einer Kalender-Abwahl ab — siehe
     * [AbwahlRaeumauftrag] fuer das Warum.
     *
     * EIGENER try/catch um alles: dieser Schritt ist ein Nachzuegler, nicht die Aufgabe der
     * Wartung. Faellt er aus, muss der Rest (Token, Kalender, Sync) trotzdem laufen.
     */
    private suspend fun arbeiteOffenenRaeumauftragAb() {
        try {
            val offenSeit = pendingDeselectionCleanupStore.pendingSince().getOrElse { error ->
                // NICHT als "kein Auftrag" deuten und schon gar nicht loeschen: dann faellt der
                // Auftrag stillschweigend unter den Tisch und die verwaisten Wecker klingeln
                // weiter. Der naechste Lauf versucht es erneut.
                Logger.w(
                    LogTags.MAINTENANCE,
                    "Offener Raeumauftrag nicht lesbar - bleibt fuer den naechsten Lauf stehen",
                    error
                )
                return
            } ?: return

            // Die Auswahl kommt aus dem SPEICHER, und ein Lesefehler ist keine Abwahl - dann wird
            // nichts geraeumt und der Auftrag bleibt offen. Gleiche Unterscheidung wie unten in
            // STEP 3 und im CalendarViewModel.
            val auswahl = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { error ->
                    Logger.w(
                        LogTags.MAINTENANCE,
                        "Raeumauftrag vertagt: Kalenderauswahl nicht lesbar (kein Beleg fuer eine Abwahl)",
                        error
                    )
                    return
                }

            val alterStunden = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - offenSeit)
            Logger.business(
                LogTags.MAINTENANCE,
                "🗑️ ABWAHL: offener Raeumauftrag gefunden (seit ${alterStunden}h) - wird abgearbeitet"
            )

            val ergebnis = AbwahlRaeumauftrag.abarbeiten(
                auftragOffen = true,
                auswahlIstLeer = auswahl.isEmpty(),
                shiftConfigLesen = { shiftUseCase.getCurrentShiftConfig().getOrNull() },
                raeumen = { config ->
                    alarmUseCase.syncAlarms(emptyList(), config)
                        .onSuccess { verbleibend ->
                            Logger.business(
                                LogTags.MAINTENANCE,
                                "✅ ABWAHL: nachtraeglich aufgeraeumt - ${verbleibend.size} Wecker " +
                                    "verbleiben (manuelle)"
                            )
                        }
                        .onFailure { e ->
                            Logger.e(
                                LogTags.MAINTENANCE,
                                "❌ ABWAHL: nachtraegliches Aufraeumen fehlgeschlagen - Auftrag bleibt offen",
                                e
                            )
                        }
                        .isSuccess
                }
            )

            if (ergebnis.merker == AbwahlRaeumauftrag.Merker.LOESCHEN) {
                pendingDeselectionCleanupStore.clearIfPending()
                if (!ergebnis.geraeumt) {
                    Logger.business(
                        LogTags.MAINTENANCE,
                        "ABWAHL: Raeumauftrag hinfaellig (wieder ein Kalender ausgewaehlt oder " +
                            "Automatik aus) - verworfen"
                    )
                }
            }

            if (ergebnis.geraeumt) saveMaintenanceTime()
        } catch (e: CancellationException) {
            // Ein Abbruch der Wartung ist kein Fehlschlag des Auftrags - er muss durchschlagen.
            throw e
        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE,
                "Raeumauftrag konnte nicht abgearbeitet werden - Wartung laeuft weiter",
                e
            )
        }
    }

    /**
     * Main maintenance logic
     *
     * STEPS:
     * 1. Token Refresh (2-5s)
     * 2. Health Check - Time-based (1s)
     * 3. Event Loading if needed (5-10s)
     * 4. Shift Recognition (1-2s)
     * 5. Alarm Creation (1-2s)
     *
     * @param forceSync ueberspringt Schritt 2 (Lade-Gate) — siehe [EXTRA_FORCE_SYNC].
     */
    private suspend fun performMaintenance(forceSync: Boolean) {
        val startTime = System.currentTimeMillis()
        Logger.business(LogTags.MAINTENANCE, "🔧 Starting maintenance cycle")

        // STEP 0: LOG RETENTION - Kaltstart allein ist nicht zuverlaessig genug fuer einen
        // lange laufenden Prozess (SimpleFileTree.init{} feuert dann nie erneut). Eigener
        // try/catch, damit ein Fehler hier niemals die eigentliche Wartung abbricht.
        try {
            applicationContext.getExternalFilesDir(null)?.let { SimpleFileTree.cleanupOldLogs(it) }
        } catch (e: Exception) {
            Logger.w(LogTags.FILE_SYSTEM, "Log-Bereinigung uebersprungen", e)
        }

        // MASTER-PAUSE: gesamte Wartung ueberspringen, wenn der Nutzer pausiert hat.
        if (masterPausePrefs.pausedNow()) {
            Logger.business(LogTags.MAINTENANCE, "Wartung uebersprungen (Master-Pause aktiv)")
            return
        }

        // STEP 0a: OFFENER RAEUMAUFTRAG nach einer Kalender-Abwahl.
        //
        // GANZ VORNE, und das ist die tragende Entscheidung: weiter unten steigt die Wartung
        // gleich zweimal aus, bevor sie die Kalenderauswahl ueberhaupt ansieht - beim
        // fehlgeschlagenen Token-Refresh und im Skip-Zweig des Lade-Gates. Genau der Skip-Zweig
        // ist nach einer Abwahl der Normalfall: die verwaisten Wecker reichen 14 Tage weit, der
        // Puffer ist also reichlich, und die Kalenderdaten sind frisch. Stuende dieser Schritt
        // hinter dem Gate, waere er in genau der Lage blind, fuer die es ihn gibt. Ein Token
        // braucht er ohnehin nicht - geraeumt wird ohne jede Netzabfrage.
        arbeiteOffenenRaeumauftragAb()

        // STEP 1: TOKEN REFRESH
        Logger.d(LogTags.MAINTENANCE, "Step 1: Token refresh")
        val tokenResult = tokenManager.getValidToken()
        
        if (tokenResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Token refresh failed, aborting maintenance")
            showActionRequiredNotification(
                title = "Anmeldung erforderlich",
                message = "Dein Kalender kann nicht synchronisiert werden. Bitte öffne die App und melde dich an."
            )
            return
        }
        
        Logger.d(LogTags.MAINTENANCE, "✅ Token valid")
        
        // STEP 2: HEALTH CHECK (Time-based v3.0)
        Logger.d(LogTags.MAINTENANCE, "Step 2: Health check (time-based)")
        
        val allAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        val futureAlarms = allAlarms.filter { it.triggerTime > now }
        val lastEventLoad = mainDataStore.data.first()[KEY_LAST_EVENT_LOAD] ?: 0L

        Logger.d(
            LogTags.MAINTENANCE,
            "Found ${futureAlarms.size} future alarms, letzte Kalender-Abfrage: " +
                if (lastEventLoad > 0) "${TimeUnit.MILLISECONDS.toHours(now - lastEventLoad)}h alt" else "nie"
        )

        val needsLoad = MaintenanceLoadDecision.shouldLoadEvents(
            futureAlarmTriggerTimes = futureAlarms.map { it.triggerTime },
            now = now,
            lastEventLoad = lastEventLoad,
            forceSync = forceSync
        )

        Logger.d(
            LogTags.MAINTENANCE,
            if (needsLoad) "Kalender wird geladen" else "Kalender-Abfrage uebersprungen (Puffer + Daten frisch)"
        )

        if (!needsLoad) {
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed (skipped, buffer sufficient) in ${duration}ms"
            )
            saveMaintenanceTime()
            return
        }
        
        // STEP 3: EVENT LOADING
        Logger.d(LogTags.MAINTENANCE, "Step 3: Loading events")
        
        // getOrElse statt getOrNull ?: emptySet(): "Lesefehler" und "nichts ausgewaehlt" sind zwei
        // verschiedene Aussagen, und seit getCurrentSelectedCalendarIds() den DataStore liest
        // (statt den StateFlow-Wert) kann der Read wirklich scheitern (IOException, gerade
        // greifender ReplaceFileCorruptionHandler). Als leere Menge gedeutet endete der Lauf mit
        // der Notification "Keine Kalender ausgewaehlt" - eine Fehldiagnose, die den Nutzer in
        // eine App schickt, in der seine Kalender korrekt angehakt sind, waehrend im Log kein
        // Lesefehler stand. Gleiche Unterscheidung wie im CalendarPreAlarmRefreshWorker.
        val selectedCalendars = calendarSelectionRepository.getCurrentSelectedCalendarIds()
            .getOrElse { error ->
                Logger.e(
                    LogTags.MAINTENANCE,
                    "Kalenderauswahl nicht lesbar - Wartungslauf uebersprungen (KEINE Konfigurations-Meldung, die Auswahl ist nur unlesbar)",
                    error
                )
                return
            }

        if (selectedCalendars.isEmpty()) {
            Logger.w(LogTags.MAINTENANCE, "No calendars selected, skipping")
            showActionRequiredNotification(
                title = "Keine Kalender ausgewählt",
                message = "Es wurden keine Kalender für die Synchronisation ausgewählt. Bitte öffne die App und wähle die gewünschten Kalender aus."
            )
            return
        }
        
        // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
        // forceRefresh nur beim erzwungenen Lauf: der Cache haelt CalendarEvents als
        // LocalDateTime, also in der Zone, die beim Abruf galt. Nach einem Zeitzonen-Wechsel waere
        // ein Cache-Treffer wertlos (dieselben falschen Wanduhr-Zeiten) - genau deshalb muss der
        // erzwungene Lauf wirklich an die API. Im Normalbetrieb bleibt es beim Cache.
        val eventsResult = calendarUseCase.getCalendarEventsWithStatus(
            calendarIds = selectedCalendars,
            forceRefresh = forceSync
        )

        if (eventsResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Event loading failed", eventsResult.exceptionOrNull())
            return
        }

        val fetchOutcome = eventsResult.getOrThrow()
        val events = fetchOutcome.events
        Logger.d(LogTags.MAINTENANCE, "Loaded ${events.size} events")

        // AUCH BEI LEERER MENGE aufrufen, und ZWINGEND VOR der isComplete-Sperre unten: die
        // Entprellung braucht jeden Lauf, um "beharrlich" von "Aussetzer" zu unterscheiden - und
        // sie muss ausserdem mitbekommen, wenn sich ein Kalender wieder erholt hat. Stuende der
        // Aufruf hinter dem `return`, saehe der Notifier ausschliesslich Stoerungen und wuerde
        // nach der ersten nie wieder verstummen.
        //
        // Das ist der EINZIGE Weg, auf dem der Nutzer von diesem Zustand erfaehrt, ohne die App zu
        // oeffnen - und genau das ist der Fehlerfall: die App laeuft ja scheinbar, waehrend die
        // Wecker einer nach dem anderen auslaufen.
        try {
            calendarUnavailableNotifier.onFetchOutcome(fetchOutcome.failedCalendarIds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // KEIN runCatching hier: das faengt Throwable und damit auch die CancellationException,
            // was der Projekt-Invariante "eine Cancellation laeuft weiter" widerspricht. Wird die
            // Wartung abgebrochen, muss der Abbruch durchschlagen und nicht als "Benachrichtigung
            // fehlgeschlagen" weggeloggt werden.
            throw e
        } catch (e: Exception) {
            // Die Wartungskette darf an einer Benachrichtigung NIEMALS scheitern - sie ist
            // Diagnostik, der Sync darunter ist die eigentliche Aufgabe.
            Logger.e(LogTags.MAINTENANCE, "Kalender-Warnung fehlgeschlagen - Wartung laeuft weiter", e)
        }

        // FAIL-SAFE, zweiter Teil: NICHT synchronisieren, wenn nur ein TEIL der Kalender
        // geantwortet hat. Ein Teilerfolg ist bewusst Result.success (siehe
        // getCalendarEventsWithStatus) - aber der Delta-Sync unten loescht jeden Alarm, dessen
        // eventId in der uebergebenen Liste fehlt. Ist der Dienstplan-Feed gerade der
        // ausgefallene Kalender, waeren das ALLE Schicht-Wecker, und die Loeschung trifft
        // Repository, AlarmManager und Direct-Boot-Spiegel gleichzeitig. Dieselbe Ueberlegung
        // wie bei der Leerlisten-Sperre direkt darunter und bei BootReceiver: lieber ein
        // veralteter Wecker als gar keiner. Auch hier bewusst KEIN Zeitstempel - der naechste
        // Lauf soll es sofort erneut versuchen.
        if (!fetchOutcome.isComplete) {
            Logger.w(
                LogTags.MAINTENANCE,
                "${fetchOutcome.failedCalendars}/${fetchOutcome.requestedCalendars} Kalender nicht " +
                    "abrufbar - Sync uebersprungen (fail-safe, bestehende Alarme bleiben)"
            )
            return
        }

        // FAIL-SAFE: leere Eventliste NICHT synchronisieren. syncAlarms() versteht "keine Events"
        // als "alle Alarme loeschen" - und ein gescheiterter Abruf kann als success(emptyList())
        // zurueckkommen. Lieber ein veralteter Wecker als gar keiner (gleiche Logik wie
        // BootReceiver.performAlarmRecovery). Auch NICHT stempeln: der naechste Lauf soll es
        // sofort erneut versuchen, nicht erst nach 12h.
        if (!MaintenanceLoadDecision.shouldSyncAfterLoad(events.size)) {
            Logger.w(
                LogTags.MAINTENANCE,
                "Kalender-Abfrage lieferte 0 Events - Sync uebersprungen (fail-safe, bestehende Alarme bleiben)"
            )
            return
        }

        saveEventLoadTime()

        // STEP 4: SHIFT RECOGNITION (nur Diagnose)
        Logger.d(LogTags.MAINTENANCE, "Step 4: Shift recognition")

        // getAllMatchingShifts() wirft bei defekter Schicht-Konfiguration (getOrThrow in
        // ShiftRecognitionEngine.performRecognition). Ungefangen landete das im generischen catch
        // von onStartCommand ("Maintenance failed with exception"): der Nutzer bekam KEINEN
        // Hinweis, waehrend alle 6h jede Dienstplan-Aenderung (auch Streichung/Krankschreibung)
        // unbemerkt liegen blieb - in Release-Builds (nur WARN+) rueckwirkend kaum
        // rekonstruierbar. Deshalb hier als das behandeln, was es ist: ein Konfigurationsdefekt,
        // der den Nutzer erreichen muss. Bestehende Alarme bleiben bewusst stehen (kein Sync,
        // kein saveMaintenanceTime) - lieber ein veralteter Wecker als gar keiner.
        val shiftMatches = try {
            shiftRecognitionEngine.getAllMatchingShifts(events)
        } catch (e: CancellationException) {
            // Cancellation ist kein Konfigurationsdefekt (Service wird gestoppt) - weiterwerfen,
            // damit der finally-Block in onStartCommand die Kette regulaer neu plant.
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "Schicht-Erkennung fehlgeschlagen - Konfiguration nicht lesbar", e)
            showActionRequiredNotification(
                title = "Schicht-Konfiguration nicht lesbar",
                message = "Deine Schichtdefinitionen konnten nicht gelesen werden. Bitte öffne die App und prüfe die Schicht-Einstellungen — bis dahin werden keine Dienstplan-Änderungen mehr übernommen."
            )
            return
        }

        val newShifts = shiftMatches.filter { match ->
            // Use pre-calculated alarm time from ShiftMatch
            val alarmTimeMillis = match.calculatedAlarmTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            alarmTimeMillis > now && futureAlarms.none { alarm ->
                // Check if alarm already exists for this event
                alarm.eventId == match.calendarEvent.id
            }
        }

        // NUR LOGGING - kein Abbruch mehr. Frueher stand hier ein Early-Return bei
        // newShifts.isEmpty(): damit erreichten geaenderte (gleiche Event-ID, neue Zeit) und
        // gestrichene Schichten den Delta-Sync NIE, obwohl genau er Update/Delete beherrscht.
        // Siehe MaintenanceLoadDecision.shouldSyncAfterLoad.
        Logger.d(
            LogTags.MAINTENANCE,
            "${newShifts.size} neue Schichten erkannt (Sync laeuft unabhaengig davon - erkennt auch Aenderungen/Streichungen)"
        )

        // STEP 5: ALARM CREATION
        Logger.d(LogTags.MAINTENANCE, "Step 5: Alarm creation")
        
        // "Konfiguration nicht lesbar" und "Auto-Alarm aus" bewusst getrennt: der frueher
        // gemeinsame Zweig (shiftConfig?.autoAlarmEnabled != true) loggte bei einem echten Defekt
        // "Auto-alarm disabled, skipping" - die falsche Diagnose fuer den teureren der beiden
        // Faelle, und die einzige Spur im Log.
        val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()
        val shiftConfig = shiftConfigResult.getOrNull()

        if (shiftConfig == null) {
            Logger.e(
                LogTags.MAINTENANCE,
                "Schicht-Konfiguration nicht lesbar - Sync uebersprungen (bestehende Alarme bleiben)",
                shiftConfigResult.exceptionOrNull()
            )
            showActionRequiredNotification(
                title = "Schicht-Konfiguration nicht lesbar",
                message = "Deine Schichtdefinitionen konnten nicht gelesen werden. Bitte öffne die App und prüfe die Schicht-Einstellungen — bis dahin werden keine Dienstplan-Änderungen mehr übernommen."
            )
            return
        }

        if (!shiftConfig.autoAlarmEnabled) {
            Logger.d(LogTags.MAINTENANCE, "Auto-alarm disabled, skipping")
            return
        }
        
        // Delta-Sync erwartet den VOLLSTAENDIGEN Soll-Zustand. Nur die neu erkannten Schichten
        // zu uebergeben wuerde alle bereits geplanten Wecker loeschen, deren Event nicht in der
        // Teilliste steht (syncAlarms entfernt Alarme ohne passendes Event).
        // Orchestrator: syncAlarms setzt die System-Alarme INTERN (Delta-Sync + idempotentes
        // Re-Arming). Kein separates scheduleSystemAlarm mehr noetig (frueher: Doppel-Scheduling).
        // Dimmer-/DND-/Pre-Alarm-Reschedule stehen bewusst NICHT mehr hier, sondern im
        // finally-Block von onStartCommand (siehe rescheduleSideChannels) - hier waren sie hinter
        // allen Early-Returns unerreichbar.
        val syncResult = alarmUseCase.syncAlarms(events, shiftConfig)

        if (syncResult.isSuccess) {
            val syncedAlarms = syncResult.getOrThrow()
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed: ${syncedAlarms.size} alarms in sync in ${duration}ms"
            )
            saveMaintenanceTime()
        } else {
            Logger.e(LogTags.MAINTENANCE, "Alarm sync failed", syncResult.exceptionOrNull())
        }
    }
    
    /**
     * Creates notification for foreground service
     */
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Alarm-Wartung",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hintergrund-Synchronisation der Wecker"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CF Alarm Wartung")
            .setContentText("Wecker werden aktualisiert...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()
    }
    
    /**
     * Shows a user-facing notification indicating action is required (e.g. login or calendar selection)
     */
    private fun showActionRequiredNotification(title: String, message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val channelId = "alarm_maintenance_alerts"
        val channel = NotificationChannel(
            channelId,
            "Wartungshinweise",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Wichtige Hinweise zur Wecker-Synchronisation"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
            
        notificationManager.notify(1002, notification)
    }
}
