package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * Enhanced BroadcastReceiver with Hilt DI.
 *
 * ROLLE: Entscheiden, ob geweckt wird - nicht, WIE geweckt wird.
 *
 * Der Receiver prueft den Skip-Status, startet den [AlarmSoundService] und stoesst die
 * Hue-Regeln an. Ton, Vibration, Audio-Fokus, Notification und Full-Screen-Intent gehoeren
 * vollstaendig dem Service. Der Receiver postet im Normalfall bewusst KEINE eigene Notification
 * (das war die Quelle des zweiten Klingeltons) und startet die Activity NICHT direkt (das ist seit
 * Android 10 kein erlaubter Background-Activity-Start und wird stillschweigend verworfen).
 *
 * ZWEI dokumentierte Ausnahmen von "keine eigene Notification": die stille Skip-Bestaetigung
 * (ID 9999) und der WECK-NOTAUSGANG (ID 2003, siehe [posteWeckNotausgang]). Letzterer greift nur,
 * wenn das System den Vordergrund-Start des [AlarmSoundService] abgelehnt hat - dann gibt es
 * keinen Wecker-Besitzer, den man doppeln koennte, und ohne ihn bliebe der Wecker komplett stumm.
 *
 * CORE FEATURES:
 * - Reliable wake lock management
 * - Skip-Check vor dem Wecken, Direct-Boot-fest
 * - 🎨 HUE INTEGRATION: Automatic light control based on shift patterns
 * - 🏗️ HILT DI: Modern dependency injection
 *
 * Die Alarm-Wartung (genügend zukünftige Alarme vorhalten) übernimmt seit
 * Briefing 4.0 vollständig der AlarmMaintenanceService (Exact Alarm alle 6h,
 * zeitbasierter 7-Tage-Puffer); die frühere count-basierte Opportunistic-Variante
 * im Receiver wurde als redundant entfernt.
 *
 * Philosophy: If the alarm works (and it does!), keep it simple.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var skipUseCase: IAlarmSkipUseCase
    @Inject lateinit var hueRuleUseCase: IHueRuleUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var alarmUseCase: IAlarmUseCase
    @Inject lateinit var alarmPrefs: AlarmPrefs

    companion object {
        /**
         * Gesamtbudget fuer die Hue-Regelausfuehrung innerhalb des Broadcast-Fensters.
         *
         * WARUM NICHT KLEINER: `executeRulesForAlarm()` schaltet erst in der Regel-Schleife alle
         * Lampen ein und legt das Auto-Aus als Bridge-Zeitplan ERST DANACH an. Schneidet der Deckel
         * dazwischen, ist das Licht an und es gibt keinen Mechanismus mehr, der es ausschaltet -
         * CLAUDE.md begruendet das ersatzlose Entfernen des `AutoOffWorker` genau damit, dass
         * "ging das Licht an, war die Bridge erreichbar und der Zeitplan entsteht". Ein zu knappes
         * Budget hebt diese Invariante auf. 20 s waren zu knapp: allein der Batch-Timeout einer
         * einzigen Regel ist 30 s.
         *
         * WARUM NICHT GROESSER: ein Hintergrund-Broadcast hat 60 s, danach protokolliert das System
         * ein ANR und darf den Prozess abwuergen - und `pendingResult.finish()` kommt erst nach
         * diesem Aufruf.
         *
         * 45 s ist der Kompromiss: genug fuer eine Regel samt Auto-Aus und in der Praxis fuer zwei,
         * mit Luft bis zur Broadcast-Grenze. RESTRISIKO, bewusst akzeptiert: bei sehr vielen Regeln
         * und einer nicht antwortenden Bridge kann der Schnitt weiterhin zwischen "an" und
         * "Auto-Aus" fallen; dann bleibt das Licht an und der Nutzer braucht die Hue-App. Die
         * saubere Loesung waere, die Hue-Ausfuehrung in den `AlarmSoundService` zu verlegen (ein
         * Vordergrunddienst hat kein Broadcast-Fenster) - das ist ein Umbau am Weckpfad und
         * bewusst nicht Teil dieser Runde.
         */
        private const val HUE_EXECUTION_BUDGET_MS = 45_000L
        const val EXTRA_SHIFT_NAME = "shift_name"

        /**
         * STILLE SCHICHT: true, wenn dieser Alarm keinen Ton/Vibration/Vollbild/Hue ausloesen
         * darf (ShiftDefinition.isSilent, uebernommen in AlarmInfo.isSilent). Reine Funktion,
         * damit die eigentliche Gate-Entscheidung ohne Android-Kontext testbar ist - der
         * umgebende onReceive-Fluss bleibt wegen goAsync()/Hilt Android-gebunden und ungetestet
         * (gleiche Konvention wie AlarmSoundService).
         *
         * Fail-safe wie der Skip-Check daneben: fehlt die AlarmInfo (Lookup fehlgeschlagen,
         * Direct Boot vor Entsperrung, o.ae.), gilt der Alarm NICHT als still - im Zweifel
         * wecken statt versehentlich stumm bleiben.
         */
        fun isSilentAlarm(alarmInfo: AlarmInfo?): Boolean = alarmInfo?.isSilent == true

        /**
         * MUSS "shift_start_time_formatted" heissen. AlarmManagerService.
         * createEnhancedAlarmIntent(), scheduleSnooze() und rescheduleFromDirectBoot()
         * schreiben die Uhrzeit unter genau diesem Schluessel (frueher "shift_time" - ein
         * Schluessel, den niemand je gesetzt hat; die Uhrzeit war dadurch immer leer).
         *
         * Der Wert ist der tatsaechliche SCHICHTBEGINN (Kalender-Event-Start), NICHT die
         * Weckzeit. Bis v1.20.0 stand hier "alarm_time" befuellt mit
         * ShiftMatch.calculatedAlarmTime (der Weckzeit) - die Notification/das Vollbild
         * zeigten "Deine Schicht beginnt um" dann faelschlich die Weckzeit (bei S2 z.B. die
         * Default-Weckzeit 14:30 statt des echten Schichtbeginns). Wer hier wieder die
         * Weckzeit eintraegt, holt sich den Fehler zurueck. Der eigentlich dominante Bugherd war
         * NICHT die Erstplanung (AlarmManagerService.createEnhancedAlarmIntent), sondern das weit
         * haeufigere Re-Arming ueber AlarmUseCase.scheduleSystemAlarm() (jeder syncAlarms()-Zweig,
         * also praktisch jeder App-Start/jede 6h-Wartung) - siehe CLAUDE.md "Wecker".
         */
        const val EXTRA_SHIFT_START_TIME = "shift_start_time_formatted"
        const val EXTRA_ALARM_ID = "alarm_id"

        private const val SKIP_CHANNEL_ID = "skip_channel"
        private const val WAKE_LOCK_TAG = "CFAlarm:WakeLock"
        private const val WAKE_LOCK_TIMEOUT = 60000L // 1 Minute

        /**
         * Channel und ID des WECK-NOTAUSGANGS — bewusst getrennt von Channel und ID (2002) des
         * [AlarmSoundService].
         *
         * Die Invariante "eine Instanz besitzt den Wecker" bleibt damit unangetastet: dieser
         * Channel wird NUR bespielt, wenn der Vordergrund-Start des Dienstes nachweislich
         * abgelehnt wurde — es gibt in diesem Moment also gar keinen Besitzer, den man doppeln
         * koennte. Genau deshalb darf (und muss) er im Gegensatz zum stummen Service-Channel
         * einen eigenen Ton tragen.
         */
        private const val NOTAUSGANG_CHANNEL_ID = "alarm_notausgang"
        private const val NOTAUSGANG_NOTIFICATION_ID = 2003

        /** Offset gegen [AlarmSoundService]s eigenen Vollbild-PendingIntent (dort: requestCode = alarmId). */
        private const val NOTAUSGANG_REQUEST_CODE_OFFSET = 20000

        internal const val MELDUNG_DIENST_ABGELEHNT =
            "⚠️ WECKER: Vordergrund-Start des AlarmSoundService abgelehnt - Ton, Vibration und " +
                "die Wecker-Notification koennen so nicht laufen. Notausgang greift: " +
                "Weckton-Benachrichtigung direkt aus dem Receiver."

        internal const val MELDUNG_NOTAUSGANG_GESCHEITERT =
            "❌ WECKER: auch der Notausgang scheiterte - dieser Wecker bleibt stumm."

        /**
         * Startet den Weckton-Dienst und faellt bei Ablehnung auf den Notausgang zurueck.
         *
         * WELCHER ABLAUF GING KAPUTT: Wird ein Wecker inexakt gestellt (das tut
         * `AlarmManagerService.setExactOrInexact()`, wenn auf API 31/32 die Berechtigung
         * "Alarme & Erinnerungen" fehlt), traegt sein Feuern NICHT die Vordergrunddienst-Freigabe,
         * die exakte Alarme mitbringen: die AlarmManager-Doku sagt den Satz "Alarms scheduled via
         * this API will be allowed to start a foreground service even if the app is in the
         * background" ausdruecklich nur bei `setAlarmClock` und `setExactAndAllowWhileIdle`, nicht
         * bei `setAndAllowWhileIdle` — und diese Datei haelt dieselbe Grenze in
         * `AlarmMaintenanceService.start()` bereits als geltend fest. `startForegroundService()`
         * wirft dann eine `ForegroundServiceStartNotAllowedException`. Vorher wurde die nur
         * geloggt: der Wecker war damit nicht "um Minuten verzoegert", sondern vollstaendig stumm —
         * kein Ton, keine Vibration, keine Benachrichtigung, kein Vollbild. Der Aufrufer loggte
         * unmittelbar danach trotzdem "triggered successfully".
         *
         * Der Notausgang ist eine Benachrichtigung mit eigenem Weckton. Eine Benachrichtigung
         * unterliegt den Vordergrunddienst-Startbeschraenkungen NICHT — sie ist der einzige
         * Weckweg, der in diesem Zustand ueberhaupt noch offensteht (Details und die bewusst
         * offene Restluecke an [posteWeckNotausgang]).
         *
         * Reine Funktion ohne Android-Typen, damit die Verzweigung pruefbar bleibt; die beiden
         * Seiteneffekte kommen als Lambda herein.
         *
         * @return true, wenn der Vordergrunddienst wirklich gestartet wurde.
         */
        internal fun starteWeckerMitNotausgang(
            starteDienst: () -> Unit,
            notausgang: () -> Unit,
            melde: (String, Throwable?) -> Unit
        ): Boolean {
            try {
                starteDienst()
                return true
            } catch (e: Exception) {
                melde(MELDUNG_DIENST_ABGELEHNT, e)
            }

            // Eigenes try/catch: der Notausgang ist der letzte Weckweg, aber sein Scheitern darf
            // nicht als Exception aus onReceive() herauslaufen - das reisst den Prozess mit und
            // verhindert auch noch pendingResult.finish().
            try {
                notausgang()
            } catch (e: Exception) {
                melde(MELDUNG_NOTAUSGANG_GESCHEITERT, e)
            }
            return false
        }
    }

    // Coroutine Scope für die asynchrone onReceive-Verarbeitung (goAsync)
    //
    // KEIN `.cancel()` — und das ist Absicht, kein vergessenes Aufräumen (die Frage kam in
    // mehreren Prüfrunden auf). Zwei Gründe:
    //  1. Das System erzeugt für JEDEN Broadcast eine FRISCHE Receiver-Instanz und gibt sie
    //     danach frei. Der Scope lebt also ohnehin nur so lange wie diese eine Zustellung; es
    //     sammelt sich nichts über mehrere Alarme hinweg an.
    //  2. Die Arbeit MUSS `onReceive()` überleben — genau dafür steht `goAsync()` darüber. Ein
    //     `cancel()` am Ende von `onReceive()` würde Ton-Start, Skip-Prüfung und Hue-Regeln
    //     mitten im Lauf abschneiden, also den Wecker abwürgen.
    // Das Ende der Verarbeitung markiert `pendingResult.finish()` im finally, nicht der Scope.
    private val receiverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Schicht"

        // ANR-SCHUTZ: goAsync() statt runBlocking auf dem Main-Thread.
        // Die gesamte Verarbeitung (Skip-Check, Alarm-Start, Hue-Regeln) läuft asynchron
        // auf einem Background-Dispatcher; pendingResult.finish() wird im finally garantiert.
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                // DIRECT BOOT: Vor der ersten Entsperrung ist der Skip-Status (CE-DataStore) nicht
                // lesbar - dann NICHT blockieren, sondern klingeln (lieber wecken als still skippen).
                val userUnlocked = try {
                    (context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked
                } catch (e: Exception) {
                    true // im Zweifel wecken
                }

                // CRITICAL: Skip-Check VOR Alarm-Trigger (nur bei entsperrtem Storage)
                if (userUnlocked) {
                    try {
                        Logger.business(
                            LogTags.ALARM_RECEIVER,
                            "🔍 SKIP-CHECK: Checking skip status for alarm $alarmId ($shiftName)"
                        )

                        val skipResult = skipUseCase.checkAndProcessSkip(alarmId)

                        when (skipResult.getOrNull()) {
                            SkipProcessResult.ALARM_SKIPPED -> {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "⏭️ SKIP-SUCCESS: Alarm $alarmId ($shiftName) SKIPPED by user"
                                )
                                showSkipNotification(context, shiftName)
                                return@launch // EARLY RETURN: Alarm nicht ausführen
                            }

                            SkipProcessResult.ALARM_EXECUTED -> {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "✅ SKIP-CHECK: Alarm $alarmId ($shiftName) will execute normally"
                                )
                                // Continue with normal alarm logic below
                            }

                            null -> {
                                Logger.w(
                                    LogTags.ALARM_RECEIVER,
                                    "⚠️ SKIP-CHECK: Check failed, executing alarm $alarmId normally"
                                )
                                // Continue with normal alarm logic
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.ALARM_RECEIVER,
                            "❌ SKIP-CHECK: Error during skip check for alarm $alarmId, executing alarm normally",
                            e
                        )
                        // Continue with normal alarm logic
                    }
                } else {
                    Logger.business(
                        LogTags.ALARM_RECEIVER,
                        "🔐 DIRECT BOOT: Nutzer noch nicht entsperrt - Skip-Check uebersprungen, Alarm $alarmId klingelt"
                    )
                }

                // Existing alarm logic continues here...
                Logger.business(LogTags.ALARM_RECEIVER, "📱 ALARM TRIGGERED! Shift: $shiftName")

                // STILLE SCHICHT: Ton/Vibration/Vollbild-Wecker UND Hue bleiben aus, wenn diese
                // Schicht als still markiert ist. Der Zeit-Anker selbst (dieser Broadcast) feuert
                // trotzdem normal weiter - DND/Dimmer/Feature A und die AlarmInfo in
                // getAllAlarms() sind davon unberuehrt, nur die Wecker-Instanz
                // (AlarmSoundService) und executeHueRulesForAlarm() werden nicht angestossen.
                if (userUnlocked) {
                    val alarmInfo = try {
                        alarmUseCase.getAllAlarms().getOrNull()?.find { it.id == alarmId }
                    } catch (e: Exception) {
                        Logger.w(
                            LogTags.ALARM_RECEIVER,
                            "⚠️ SILENT-CHECK: Konnte AlarmInfo fuer $alarmId nicht laden, wecke sicherheitshalber normal",
                            e
                        )
                        null
                    }

                    if (isSilentAlarm(alarmInfo)) {
                        Logger.business(
                            LogTags.ALARM_RECEIVER,
                            "🔕 STILLE SCHICHT: Alarm $alarmId ($shiftName) ist als still markiert - kein Ton/Vibration/Vollbild/Hue"
                        )
                        return@launch
                    }
                }

                // Wake Lock to ensure device wakes up
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT)
                }

                try {
                    val shiftStartTime = intent.getStringExtra(EXTRA_SHIFT_START_TIME).orEmpty()

                    // 🔊 EINZIGER Einstiegspunkt fuer Ton UND Sichtbarkeit.
                    // Der Service startet den MediaPlayer, holt den Audio-Fokus und postet die
                    // Notification mit dem Full-Screen-Intent. Der Receiver postet bewusst KEINE
                    // eigene Notification mehr und startet die Activity NICHT mehr direkt:
                    // - Die fruehere zweite Notification (2001) brachte ueber ihren Channel einen
                    //   zweiten Klingelton mit.
                    // - Ein direktes startActivity() aus einem Receiver ist seit Android 10 kein
                    //   erlaubter Background-Activity-Start (AlarmManager-Broadcasts stehen NICHT
                    //   auf der Ausnahmeliste) und wird stillschweigend verworfen. Der einzige
                    //   sanktionierte Weg ist der vom System gesendete Full-Screen-PendingIntent.
                    // AUSNAHME: lehnt das System den Vordergrund-Start des Dienstes ab, postet der
                    //   Receiver den Notausgang (ID 2003) - siehe posteWeckNotausgang(). Dann gibt
                    //   es keinen Dienst, der doppeln koennte, und es waere sonst gar kein Wecker.
                    val dienstLaeuft = startAlarmSoundService(
                        context, shiftName, shiftStartTime, alarmId, userUnlocked
                    )

                    // Die Erfolgsmeldung haengt am tatsaechlichen Ausgang. Vorher stand sie
                    // unbedingt hier, waehrend startAlarmSoundService() den abgelehnten
                    // Vordergrund-Start intern wegfing: im Log standen dann eine Fehlerzeile und
                    // "triggered successfully" nebeneinander - unauswertbar genau in dem Fall, in
                    // dem der Nutzer nicht geweckt wurde.
                    if (dienstLaeuft) {
                        Logger.business(
                            LogTags.ALARM_RECEIVER,
                            "✅ Alarm $alarmId for $shiftName triggered successfully!"
                        )
                    } else {
                        Logger.w(
                            LogTags.ALARM_RECEIVER,
                            "⚠️ Alarm $alarmId for $shiftName: Weckton-Dienst kam nicht hoch - " +
                                "es weckt nur der Notausgang"
                        )
                    }

                    // 🎨 HUE INTEGRATION - Execute matching light rules
                    // Läuft NACH dem Alarm-Start, damit ein langsamer Netzwerkaufruf
                    // Sound + Full-Screen-Intent nicht verzögert.
                    // Direct Boot: Hue braucht Netz + CE/Hue-Storage - vor Entsperrung ueberspringen.
                    if (userUnlocked) {
                        // GEDECKELT, weil `pendingResult.finish()` erst danach kommt.
                        //
                        // Ein BroadcastReceiver muss sein `finish()` innerhalb des
                        // Broadcast-Zeitfensters erreichen; danach protokolliert das System ein
                        // "Broadcast of Intent"-ANR und darf den Prozess abwuergen. Der Hue-Pfad
                        // hat aber KEINE Gesamtschranke: `executeRulesForAlarm()` laeuft ueber
                        // ALLE passenden Regeln, jede mit eigenem 30-s-Batch-Timeout, danach folgt
                        // `scheduleBridgeAutoOff()` voellig ohne Timeout (GET + n DELETEs + ein POST
                        // pro Ziel, je 10 s OkHttp). Zwei Regeln und eine Bridge, die nicht
                        // antwortet (Handy nicht im Heim-WLAN - der Normalfall auf Reisen), reichen
                        // fuer eine Minute und mehr.
                        //
                        // Der Wecker selbst ist davon unabhaengig: Ton, Vibration und
                        // Full-Screen-Intent laufen ueber den bereits gestarteten
                        // AlarmSoundService, nicht ueber diese Coroutine. Licht, das nicht angeht,
                        // ist ein hinnehmbarer Verlust; ein abgewuergter Prozess ist es nicht.
                        val hueDone = withTimeoutOrNull(HUE_EXECUTION_BUDGET_MS) {
                            executeHueRulesForAlarm(shiftName)
                            true
                        }
                        if (hueDone == null) {
                            Logger.w(
                                LogTags.ALARM_RECEIVER,
                                "⚠️ HUE: Regelausfuehrung nach ${HUE_EXECUTION_BUDGET_MS / 1000}s " +
                                    "abgebrochen (Bridge nicht erreichbar?) - der Wecker selbst ist " +
                                    "davon unberuehrt (AlarmSoundService). ACHTUNG: faellt der " +
                                    "Abbruch zwischen Einschalten und Auto-Aus-Zeitplan, bleibt das " +
                                    "Licht an und muss von Hand ausgeschaltet werden."
                            )
                        }
                    }

                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_RECEIVER, "❌ Error handling alarm", e)
                } finally {
                    // Release wake lock
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            } finally {
                // GARANTIERT: BroadcastReceiver-Lebenszyklus beenden
                pendingResult.finish()
            }
        }
    }

    private fun showSkipNotification(context: Context, shiftName: String) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel MUSS vor dem Posten existieren: seit Android 8 verwirft der
            // NotificationManager stillschweigend jede Notification auf einem unbekannten
            // Channel. Frueher wurde hier auf "skip_channel" gepostet, ohne ihn je anzulegen -
            // die Skip-Bestaetigung war damit unsichtbar.
            notificationManager.createNotificationChannel(
                android.app.NotificationChannel(
                    SKIP_CHANNEL_ID,
                    "Übersprungene Alarme",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Bestätigung, dass ein Alarm wie gewünscht übersprungen wurde"
                }
            )

            val notification = NotificationCompat.Builder(context, SKIP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle("Alarm übersprungen")
                .setContentText("$shiftName-Alarm wurde wie gewünscht übersprungen")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(30000) // 30 Sekunden
                .build()

            notificationManager.notify(9999, notification)
            Logger.business(LogTags.ALARM_RECEIVER, "✅ Skip notification shown")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "Error showing skip notification", e)
        }
    }

    /**
     * 🎨 HUE INTEGRATION: Execute matching light rules for alarm
     *
     * Creates a synthetic ShiftMatch from available alarm data and executes
     * any applicable Hue rules configured for this shift pattern.
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of appContainer
     * NOTE: context parameter removed - all dependencies injected via Hilt
     */
    private suspend fun executeHueRulesForAlarm(shiftName: String) {
        try {
            Logger.business(
                LogTags.ALARM_RECEIVER,
                "🎨 Starting Hue rule execution for shift: $shiftName"
            )

            // Läuft als suspend-Aufruf in der onReceive-Coroutine (kein runBlocking mehr)
            try {
                // Try to find matching shift definition using injected shiftUseCase
                val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()

                if (shiftConfigResult.isSuccess) {
                    val shiftConfig = shiftConfigResult.getOrNull()
                    // Gestaffelt nach Genauigkeit - siehe ShiftConfig.findDefinitionFor().
                    // Ein unscharfes find{} stand mal hier und hat die Regeln fast jeder
                    // Schicht auf "Spaetschicht" umgebogen.
                    // ZUGEORDNET wird ueber den REINEN Schichtnamen: ein von Hand angelegter
                    // Wecker heisst "Fruehschicht (Manuell)", und findDefinitionFor findet dazu
                    // nichts. Am Emulator gemessen (27.08.2026): der Wecker klingelte normal, die
                    // Hue-Regel derselben Schicht lief nicht, und im Log stand nur ein
                    // unauffaelliges "No shift definition found" - ein stiller Ausfall genau der
                    // Art, die diese App nicht haben darf. ANGEZEIGT wird weiter `shiftName`.
                    val zuordnungsName = reinerSchichtname(shiftName)
                    val matchingShiftDef = shiftConfig?.findDefinitionFor(zuordnungsName)

                    if (matchingShiftDef != null) {
                        // Create synthetic ShiftMatch for Hue rules
                        val syntheticShiftMatch = createSyntheticShiftMatch(
                            shiftDefinition = matchingShiftDef,
                            shiftName = zuordnungsName
                        )

                        // Execute Hue rules for this shift using injected hueRuleUseCase
                        val currentTime = LocalTime.now()
                        val executionResult = hueRuleUseCase.executeRulesForAlarm(
                            shift = syntheticShiftMatch,
                            alarmTime = currentTime
                        )

                        if (executionResult.isSuccess) {
                            val result = executionResult.getOrNull()
                            if (result != null && result.rulesExecuted > 0) {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "🎨✅ Hue rules executed successfully: ${result.rulesExecuted} rules, " +
                                            "${result.successfulActions}/${result.actionsExecuted} actions successful"
                                )

                                if (result.errors.isNotEmpty()) {
                                    Logger.w(
                                        LogTags.ALARM_RECEIVER,
                                        "🎨⚠️ Some Hue actions failed: ${result.errors}"
                                    )
                                }
                            } else {
                                Logger.d(
                                    LogTags.ALARM_RECEIVER,
                                    "🎨💡 No Hue rules configured for shift: $shiftName"
                                )
                            }
                        } else {
                            Logger.w(
                                LogTags.ALARM_RECEIVER,
                                "🎨❌ Hue rule execution failed",
                                executionResult.exceptionOrNull()
                            )
                        }
                    } else {
                        Logger.d(
                            LogTags.ALARM_RECEIVER,
                            "🎨💡 No shift definition found for: $zuordnungsName (skipping Hue rules)"
                        )
                    }
                } else {
                    Logger.w(
                        LogTags.ALARM_RECEIVER,
                        "🎨⚠️ Could not load shift configuration for Hue rules",
                        shiftConfigResult.exceptionOrNull()
                    )
                }

            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Exception during Hue rule execution", e)
            }

        } catch (e: Exception) {
            // Don't let Hue errors crash the alarm
            Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Critical error in Hue integration", e)
        }
    }

    /**
     * Creates a synthetic ShiftMatch from available alarm data
     *
     * Since the AlarmReceiver doesn't have access to the original ShiftMatch,
     * we reconstruct the essential information needed for Hue rule execution.
     */
    private fun createSyntheticShiftMatch(
        shiftDefinition: ShiftDefinition,
        shiftName: String
    ): ShiftMatch {
        val now = LocalDateTime.now()

        // Create synthetic calendar event
        val syntheticCalendarEvent = CalendarEvent(
            id = "synthetic_$shiftName",
            title = shiftName,
            startTime = now,
            endTime = now.plusHours(8), // Assume 8-hour shift
            calendarId = "synthetic",
            isAllDay = false
        )

        // Calculate synthetic alarm time (now, since the alarm just triggered)
        val calculatedAlarmTime = now

        return ShiftMatch(
            shiftDefinition = shiftDefinition,
            calendarEvent = syntheticCalendarEvent,
            calculatedAlarmTime = calculatedAlarmTime
        )
    }

    /**
     * Starts AlarmSoundService to handle audio playback independently
     * 
     * CRITICAL: Service must start BEFORE Activity to ensure sound begins immediately
     * and survives Activity lifecycle events.
     *
     * @param context Context for starting the service
     * @param shiftName Name of the shift for notification display
     * @param shiftStartTime Formatierte Startzeit der Schicht (Notification-Text)
     * @param alarmId Unique alarm identifier
     *
     * SUSPEND: liest [alarmPrefs] EINMAL hier - der einzige Ort, der die konfigurierte
     * Schlummer-Dauer aus dem DataStore holt. Beide Snooze-Ausloeser (Vollbild-Button,
     * Notification-Button) bleiben synchron und lesen den Wert stattdessen aus dem Intent-Extra
     * [AlarmSoundService.EXTRA_SNOOZE_MINUTES], das hier gesetzt wird. Der einzige Aufrufer laeuft
     * bereits in receiverScope.launch, also ist der suspend-Read hier sicher.
     *
     * DIRECT BOOT: [alarmPrefs] liegt im @MainDataStore (CE-Storage) und ist vor der ersten
     * Entsperrung NICHT lesbar - genau wie der Skip- und Silent-Check weiter oben in onReceive().
     * Diese Funktion wird dort aber UNGEGATET aufgerufen (der Wecker muss auch im Direct-Boot-Fall
     * klingeln), deshalb bekommt sie [userUnlocked] durchgereicht und liest den DataStore nur bei
     * true - sonst waere ein CE-Storage-Read hier fatal statt bloss uebersprungen: der try/catch
     * darunter haette eine Exception (oder ein Haengen) abgefangen, ohne dass startForegroundService()
     * je erreicht wird - der Wecker bliebe nach einem Reboot vor der ersten Entsperrung stumm.
     *
     * @return true, wenn der Vordergrunddienst wirklich gestartet wurde; false, wenn nur der
     *   Notausgang greift (siehe [starteWeckerMitNotausgang]).
     */
    private suspend fun startAlarmSoundService(
        context: Context,
        shiftName: String,
        shiftStartTime: String,
        alarmId: Int,
        userUnlocked: Boolean
    ): Boolean {
        // Eigenes try/catch um den DataStore-Read: ein Lesefehler darf nicht den ganzen Weckvorgang
        // kosten. Vorher lag er im selben try wie der Dienst-Start - warf er, wurde nur geloggt und
        // startForegroundService() nie erreicht.
        val snoozeMinutes = if (userUnlocked) {
            try {
                alarmPrefs.snoozeMinutesNow()
            } catch (e: Exception) {
                Logger.w(
                    LogTags.ALARM_RECEIVER,
                    "⚠️ Schlummer-Dauer nicht lesbar - Wecker laeuft mit dem Standardwert weiter",
                    e
                )
                AlarmPrefs.DEFAULT_SNOOZE_MINUTES
            }
        } else {
            AlarmPrefs.DEFAULT_SNOOZE_MINUTES
        }

        val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_START_ALARM
            putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, shiftName)
            putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, shiftStartTime)
            putExtra(AlarmSoundService.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmSoundService.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        }

        val gestartet = starteWeckerMitNotausgang(
            // minSdk = 26, startForegroundService ist immer verfuegbar.
            starteDienst = { context.startForegroundService(serviceIntent) },
            notausgang = { posteWeckNotausgang(context, shiftName, shiftStartTime, alarmId) },
            melde = { text, fehler -> Logger.w(LogTags.ALARM_RECEIVER, text, fehler) }
        )

        if (gestartet) {
            Logger.business(
                LogTags.ALARM_RECEIVER,
                "✅ AlarmSoundService started as foreground service (API ${Build.VERSION.SDK_INT})"
            )
        }
        return gestartet
    }

    /**
     * DER NOTAUSGANG: weckt ohne Vordergrunddienst.
     *
     * Warum das kein zweiter Wecker-Besitzer ist: diese Funktion laeuft ausschliesslich, nachdem
     * [starteWeckerMitNotausgang] den Vordergrund-Start als abgelehnt gesehen hat - der
     * [AlarmSoundService] laeuft in diesem Moment nachweislich NICHT, es gibt also nichts zu
     * doppeln. Eigener Channel, eigene ID (2003), damit der Dienst seinen Slot 2002 unveraendert
     * behaelt, falls er auf einem anderen Weg doch noch hochkommt.
     *
     * BEWUSST OHNE FULL-SCREEN-INTENT AUF [AlarmFullScreenActivity], und das ist kein Versehen:
     * die Activity beobachtet `AlarmSoundService.alarmActive` und schliesst sich, sobald der Wert
     * false ist (observeAlarmState dort). Genau das ist er hier - der Dienst
     * ist ja nicht gestartet. Ein Full-Screen-Intent haette den Weck-Bildschirm also aufblitzen
     * und im selben Moment wieder verschwinden lassen; ein Vollbild, das sich selbst schliesst,
     * ist der Fehler, den dieses Projekt schon einmal tagelang gesucht hat. Es bleibt die
     * Heads-up-Benachrichtigung auf einem IMPORTANCE_HIGH-Channel mit Weckton (USAGE_ALARM,
     * kommt damit auch durch "Nicht stoeren") und Vibration - laut, sichtbar auf dem
     * Sperrbildschirm, und ohne den Ausschalt-Knopf zu verlieren.
     *
     * BEWUSST OHNE FLAG_INSISTENT (Dauerklingeln): abschalten liesse sich das nur ueber genau
     * diese Benachrichtigung, und der Nutzer sucht den Ausschalter im Weck-Bildschirm. Ein
     * Wecker, den man nicht ausbekommt, ist schlimmer als einer, der einmal laut wird.
     *
     * RESTLUECKE, bewusst und benannt: der Weck-BILDSCHIRM kommt in diesem Zweig nicht. Ihn hier
     * ebenfalls hochzuziehen, hiesse den "Wecker laeuft"-Zustand ohne den Dienst herstellen zu
     * koennen - ein Umbau an [AlarmSoundService]/[AlarmFullScreenActivity], nicht an dieser Datei.
     */
    private fun posteWeckNotausgang(
        context: Context,
        shiftName: String,
        shiftStartTime: String,
        alarmId: Int
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Channel MUSS vor dem Posten existieren (siehe showSkipNotification), und er MUSS
        // IMPORTANCE_HIGH tragen: darunter ignoriert das System den Full-Screen-Intent - und der
        // ist hier der einzige verbliebene Weckweg.
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(
                NOTAUSGANG_CHANNEL_ID,
                "Wecker-Notausgang",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Weckt, wenn der Weckton-Dienst vom System nicht gestartet werden durfte"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )

        val oeffneApp = PendingIntent.getActivity(
            context,
            // Eigener Slot: AlarmSoundService benutzt fuer seinen Vollbild-PendingIntent
            // requestCode = alarmId. Derselbe Code plus FLAG_UPDATE_CURRENT wuerde dessen Intent
            // ueberschreiben, sobald der Dienst spaeter doch noch startet.
            alarmId + NOTAUSGANG_REQUEST_CODE_OFFSET,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SHIFT_NAME, shiftName)
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Leere Uhrzeit sauber abfangen statt "Deine Schicht beginnt um " anzuzeigen - gleiche
        // Behandlung wie in AlarmSoundService.
        val text = if (shiftStartTime.isBlank()) {
            context.getString(R.string.alarm_notification_text_no_time)
        } else {
            context.getString(R.string.alarm_notification_text, shiftStartTime)
        }

        val notification = NotificationCompat.Builder(context, NOTAUSGANG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title, shiftName))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(oeffneApp)
            .build()

        notificationManager.notify(NOTAUSGANG_NOTIFICATION_ID, notification)

        // WARN, damit die Zeile im Release-Log steht (dort landet nur WARN+): ohne sie waere
        // spaeter nicht unterscheidbar, ob der Wecker gar nicht feuerte oder ob er feuerte und
        // nur der Vordergrunddienst abgelehnt wurde.
        Logger.w(
            LogTags.ALARM_RECEIVER,
            "⚠️ WECKER: Notausgang gestellt (Weckton-Benachrichtigung) fuer Alarm " +
                "$alarmId/$shiftName - kein Dauerton und kein Weck-Bildschirm, weil der " +
                "AlarmSoundService nicht starten durfte"
        )
    }
}
