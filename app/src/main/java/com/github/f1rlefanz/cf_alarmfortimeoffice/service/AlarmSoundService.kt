package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.VorweckEntscheidung
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WeckbildschirmVerdraengungPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AlarmSoundService - alleiniger Besitzer des Weckers.
 *
 * VERANTWORTUNG (alles an EINER Stelle, bewusst):
 * - MediaPlayer (Ton), unabhaengig vom Activity-Lebenszyklus
 * - Vibration
 * - Audio-Fokus, damit fremde Medien pausieren statt weiterzulaufen
 * - Die EINZIGE Alarm-Notification der App, inkl. Full-Screen-Intent
 *
 * WARUM ZENTRAL: Vorher postete der AlarmReceiver eine zweite Notification auf einem Channel
 * MIT eigenem Klingelton, waehrend dieser Service parallel denselben Ton per MediaPlayer
 * abspielte. Ergebnis: zwei Wecker, zwei Eintraege in der Leiste, zwei Stopp-Wege, die nichts
 * voneinander wussten. Genau eine Instanz besitzt den Wecker - das ist die Invariante.
 *
 * SICHTBARKEIT: Die Notification traegt den Full-Screen-Intent. Der vom System gesendete
 * PendingIntent ist auf Android 10+ der einzige erlaubte Weg, aus dem Hintergrund eine Activity
 * zu starten; AlarmManager-Broadcasts stehen NICHT auf der Ausnahmeliste. Der Stop-Button der
 * Notification ist zugleich der Notausgang fuer restriktive Geraete, auf denen die
 * Full-Screen-Activity gar nicht erst hochkommt.
 *
 * LIFECYCLE:
 * - START_REDELIVER_INTENT: Wird der Prozess unter Speicherdruck beendet, liefert Android denselben
 *   START_ALARM-Intent erneut aus - Ton, Vibration und Notification kommen also zurueck. Mit dem
 *   frueheren START_STICKY war die Zusage "Auto-restart if killed" eine Attrappe: sticky startet den
 *   Service mit intent == null, und der else-Zweig unten kann daraus nichts wiederherstellen (kein
 *   startForeground, kein Ton) - uebrig blieb ein stummer Zombie-Service, waehrend das Log wie ein
 *   funktionierender Wecker aussah.
 * - Foreground: No background execution limits, reliable alarm execution
 * - onDestroy: Guaranteed cleanup hook for all resources
 * - onTaskRemoved bewusst NICHT ueberschrieben (siehe Kommentar unten)
 *
 * CRITICAL FEATURES:
 * - isShuttingDown flag prevents MediaPlayer race conditions
 * - OnPreparedListener checks shutdown state before starting playback
 * - Defensive cleanup in multiple lifecycle hooks
 * - Foreground service ensures Android doesn't kill during alarm
 *
 * FIXES SNOOZE BUG:
 * Previous issue: MediaPlayer.prepareAsync() completed AFTER Activity.finish()
 * Solution: Service-managed MediaPlayer with shutdown flag guards
 *
 * @author CF-Alarm Development Team
 * @since 1.4.4 - Snooze Bug Fix Release
 */
class AlarmSoundService : Service() {
    
    companion object {
        // Service Actions
        const val ACTION_START_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.START_ALARM"
        const val ACTION_STOP_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.STOP_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.SNOOZE_ALARM"

        // Intent Extras
        const val EXTRA_SHIFT_NAME = "shift_name"
        const val EXTRA_ALARM_ID = "alarm_id"

        // Tatsaechlicher Schichtbeginn (Kalender-Event-Start), NICHT die Weckzeit - siehe
        // AlarmReceiver.EXTRA_SHIFT_START_TIME.
        const val EXTRA_SHIFT_START_TIME = "shift_start_time_formatted"

        // Vom AlarmReceiver EINMALIG aus AlarmPrefs gelesene, konfigurierte Schlummer-Dauer in
        // Minuten. Beide Snooze-Ausloeser (Notification-Button hier, Vollbild-Button in
        // AlarmFullScreenActivity) lesen sie synchron aus dem Intent statt selbst den DataStore
        // anzufassen - siehe AlarmPrefs-Klassenkommentar.
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"

        private const val VORWECK_LOCK_TAG = "CFAlarm:VorweckenFuerWeckbildschirm"

        /** Nur eine Ueberbrueckung bis zum eigenen Lock des Weckbildschirms. */
        private const val VORWECK_LOCK_TIMEOUT_MS = 10_000L

        // Notification Configuration
        // EINZIGE Alarm-Notification der App. Der AlarmReceiver postete frueher eine zweite
        // (ID 2001) mit eigenem Channel-Sound - das ergab zwei Klingeltoene und zwei Eintraege
        // in der Leiste. Ton + Sichtbarkeit haengen jetzt an genau diesem Service.
        const val NOTIFICATION_ID = 2002

        // VERSIONIERTE Kanal-ID, und das "_v2" ist kein Schoenheitsfehler: Android aendert an einem
        // BESTEHENDEN Kanal die Importance nur nach UNTEN und ignoriert alle uebrigen Felder
        // ("All other fields are ignored for channels that already exist",
        // NotificationManager.createNotificationChannel). Bis v1.9.7 wurde dieser Kanal mit
        // IMPORTANCE_LOW angelegt; die spaetere Anhebung auf IMPORTANCE_HIGH samt setBypassDnd und
        // VISIBILITY_PUBLIC lief unter derselben ID und blieb auf jedem Bestandsgeraet wirkungslos -
        // dort stand der Wecker-Kanal weiter auf LOW, das System verwarf den Full-Screen-Intent, und
        // der Wecker klingelte ohne Weck-Bildschirm und ohne Stopp-/Schlummer-Knopf.
        // Loeschen und unter derselben ID neu anlegen hilft NICHT: Android holt einen geloeschten
        // Kanal mit genau seinen alten Einstellungen zurueck. Nur eine neue ID entkommt.
        // Beim Umbenennen mitziehen: NotificationDeliverability.WECKER_KANAL_ID (der Test
        // NotificationDeliverabilityTest haelt beide zusammen).
        private const val CHANNEL_ID = "alarm_sound_service_v2"

        // Der abgeloeste Kanal. Wird nur noch geloescht, damit in den Systemeinstellungen kein
        // toter Zwilling "Schicht-Wecker" neben dem lebenden steht.
        private const val ALTER_CHANNEL_ID = "alarm_sound_service"

        /**
         * Laeuft gerade ein Wecker? Die [AlarmFullScreenActivity] beobachtet das und schliesst
         * sich selbst, sobald der Ton ueber den Notification-Button beendet wurde - sonst muesste
         * der Nutzer den Wecker zweimal stoppen (einmal in der Leiste, einmal im Vollbild).
         */
        private val _alarmActive = MutableStateFlow(false)
        val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

        // Eigener Kanal und eigene ID fuer den Schlummer-Hinweis. NICHT die Wecker-Notification
        // (2002) umtexten: die gehoert dem laufenden Wecker und traegt den Full-Screen-Intent -
        // wer sie ueberschreibt, nimmt dem Nutzer im selben Moment den Stop-Knopf. 2003 ist der
        // Wecker-Notausgang des AlarmReceivers, deshalb 2004.
        private const val HINWEIS_CHANNEL_ID = "snooze_hinweis_v1"
        private const val HINWEIS_NOTIFICATION_ID = 2004

        /**
         * Sagt dem Nutzer, dass sein Schlummern keinen VERLAESSLICHEN Weckruf gestellt hat.
         *
         * WARUM ES DAS GIBT (Pruefrunde 8): Ein gescheitertes Schlummern war von einem
         * erfolgreichen nicht zu unterscheiden - Ton aus, Vollbild zu, kein Wecker, nur eine Zeile
         * im Log. Wer "15 Min spaeter" gedrueckt hatte, verschlief ohne jeden Hinweis.
         *
         * Gemeinsam genutzt von beiden Schlummer-Ausloesern (Notification-Knopf hier im Dienst,
         * Vollbild-Knopf in der [AlarmFullScreenActivity]) - eine Meldung, ein Ort. Titel UND Text
         * kommen dabei aus [SchlummerEntscheidung.hinweis], also je Ergebnis verschieden: die drei
         * Lagen (pausiert / verlaesslich nicht gestellt / ungewiss) sagen Verschiedenes, und ein
         * gemeinsamer Titel hat genau das schon einmal ueberschrieben.
         *
         * Bewusst STUMM ([NotificationCompat.Builder.setSilent]): daneben klingelt in der Regel
         * noch der Wecker weiter, ein zweiter Ton waere reine Verwirrung. IMPORTANCE_HIGH bleibt
         * trotzdem noetig, damit die Meldung nicht unbemerkt in der Leiste versinkt.
         */
        fun posteSchlummerHinweis(context: Context, ergebnis: SnoozeErgebnis) {
            val meldung = SchlummerEntscheidung.hinweis(ergebnis) ?: return
            val text = meldung.text
            try {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                // Gefahrlos wiederholbar: bei einem schon vorhandenen Kanal zieht Android nur
                // Name und Beschreibung nach, nie die Wichtigkeit (die geht ohne NEUE Kanal-ID
                // nur nach unten - siehe die Weckerkanal-Falle in CLAUDE.md).
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        HINWEIS_CHANNEL_ID,
                        "Schlummer-Hinweise",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        // "verlaesslich": der unklare Ausgang stellt moeglicherweise doch einen
                        // Weckruf - eine Kanalbeschreibung, die das Gegenteil behauptet, waere
                        // dieselbe Luege wie der frueher gemeinsame Titel.
                        description =
                            "Meldet, wenn ein Schlummern keinen verlaesslichen Weckruf gestellt hat"
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )

                // Tippen oeffnet die App - der Weg, auf dem der Nutzer die Pause beenden oder
                // seinen Wecker von Hand stellen kann. Eine Meldung ohne Ausweg waere nur halb.
                val oeffneApp = PendingIntent.getActivity(
                    context,
                    HINWEIS_NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        setPackage(context.packageName)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val hinweis = NotificationCompat.Builder(context, HINWEIS_CHANNEL_ID)
                    // Titel aus DERSELBEN Meldung wie der Text: eingeklappt am Sperrbildschirm
                    // ist diese Zeile die einzige, die vollstaendig gelesen wird - eine
                    // Ueberschrift, die dem Text widerspricht, ist dort die ganze Nachricht.
                    .setContentTitle(meldung.titel)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setContentIntent(oeffneApp)
                    .build()

                notificationManager.notify(HINWEIS_NOTIFICATION_ID, hinweis)
                Logger.w(
                    LogTags.ALARM,
                    "⚠️ Schlummer-Hinweis gepostet: ${meldung.titel} ($ergebnis)"
                )
            } catch (e: Exception) {
                // Der Hinweis ist die Zweitmeldung; der Wecker selbst laeuft davon unberuehrt
                // weiter. Ein Wurf hier duerfte niemals den Weckpfad mitreissen.
                Logger.w(LogTags.ALARM, "⚠️ Schlummer-Hinweis konnte nicht gepostet werden", e)
            }
        }
    }
    
    // Audio Management
    private var alarmMediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // Audio-Fokus: ohne ihn laufen fremde Player (Podcast, Musik) einfach ueber den Wecker
    // weiter, weil sie gar nicht erfahren, dass sie pausieren sollen.
    private var audioFocusRequest: AudioFocusRequest? = null

    // Shutdown Flag - prevents async callbacks from starting sound after stop
    @Volatile
    private var isShuttingDown = false

    /** Traegt den verzoegerten [starteVordergrundUndWecken]-Aufruf des Vorweck-Pfades. */
    private val vorweckHandler = Handler(Looper.getMainLooper())

    private var vorweckLock: PowerManager.WakeLock? = null

    // Generation counter - each new MediaPlayer gets a unique ID so stale
    // OnPreparedListener callbacks from a previous player can never start playback.
    @Volatile
    private var playerGeneration = 0
    
    /**
     * Reiner Started-Service, kein Binding.
     *
     * Die Activity hing frueher per Binder am Service, hat die Referenz aber nie benutzt - der
     * Bind war Zeremonie. Den Zustand "Wecker laeuft" liefert jetzt [alarmActive]; das ueberlebt
     * im Gegensatz zu einer Bindung auch das Zerstoeren der Activity.
     */
    override fun onBind(intent: Intent?): IBinder? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Alarm"
                val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                val shiftStartTime = intent.getStringExtra(EXTRA_SHIFT_START_TIME).orEmpty()
                val snoozeMinutes = intent.getIntExtra(
                    EXTRA_SNOOZE_MINUTES,
                    AlarmManagerService.SNOOZE_MINUTES.toInt()
                )

                Logger.business(
                    LogTags.ALARM,
                    "🔊 AlarmSoundService START_ALARM",
                    "Shift: $shiftName, ID: $alarmId, Start: $shiftStartTime"
                )

                // Reset shutdown flag for new alarm
                isShuttingDown = false

                // MUSS vor startForeground() stehen: die Notification traegt den
                // Full-Screen-Intent, das System kann die Activity also unmittelbar nach
                // startForeground() starten. Stuende alarmActive dann noch auf false, wuerde
                // die Activity das als "Wecker bereits beendet" lesen und sich sofort wieder
                // schliessen.
                _alarmActive.value = true

                // Create notification channel (idempotent, safe to call multiple times)
                createNotificationChannel()

                // Start as foreground service (Android 8+ requirement).
                // Diese Notification traegt auch den Full-Screen-Intent: der vom SYSTEM gesendete
                // PendingIntent ist auf Android 10+ der einzige erlaubte Weg, aus dem Hintergrund
                // eine Activity zu starten (ein direktes startActivity() aus dem Receiver wird
                // verworfen und ist deshalb kein tragfaehiger Fallback mehr).
                val notification = createAlarmNotification(shiftName, shiftStartTime, alarmId, snoozeMinutes)

                // VORWECKEN (v1.40.0): auf Geraeten, die den Weckbildschirm nachweislich
                // verdraengen, zuerst selbst den Bildschirm wecken und erst danach die
                // Notification posten. Warum das hilft, steht bei [vorlaufFuerWeckbildschirm].
                val vorlauf = vorlaufFuerWeckbildschirm()
                if (vorlauf > 0L) {
                    weckeBildschirmVorab()
                    vorweckHandler.postDelayed({ starteVordergrundUndWecken(notification) }, vorlauf)
                } else {
                    starteVordergrundUndWecken(notification)
                }

                // Start alarm sound and vibration. BEWUSST NICHT verzoegert, auch nicht auf dem
                // Vorweck-Pfad: der Ton ist der Wecker. Er darf nicht auf eine Bildschirmfrage
                // warten - und er braucht den Vordergrund-Zustand nicht, um zu spielen.
                requestAudioFocus()
                startAlarmSound()
                startVibration()
            }

            ACTION_SNOOZE_ALARM -> {
                // Snooze-Button der Sperrbildschirm-Benachrichtigung. Wichtiger Notausgang: kommt
                // das Vollbild auf restriktiven Geraeten gar nicht erst sichtbar hoch, ist das
                // hier der EINZIGE Weg zu schlummern (der Vollbild-Button waere unerreichbar).
                val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Alarm"
                val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                val shiftStartTime = intent.getStringExtra(EXTRA_SHIFT_START_TIME).orEmpty()
                // Fallback-Default matters: Direct-Boot-Restore oder ein Snooze-auf-Snooze-Zyklus
                // (dieser Notification-Button selbst) tragen dieses Extra ggf. nicht mit.
                val snoozeMinutes = intent.getIntExtra(
                    EXTRA_SNOOZE_MINUTES,
                    AlarmManagerService.SNOOZE_MINUTES.toInt()
                )
                Logger.i(LogTags.ALARM, "😴 AlarmSoundService SNOOZE_ALARM (Notification): $shiftName, id=$alarmId")

                // ZUERST den neuen Wecker planen (setAlarmClock ist synchron + schnell), DANN den Ton
                // stoppen - so steht der Snooze sicher, selbst wenn stopSelf() gleich greift. Exakt
                // derselbe Weg wie "5 Min spaeter" im Vollbild: AlarmManagerService.scheduleSnooze.
                //
                // Eigenes try/catch: ein Planungsfehler (z.B. entzogene Exact-Alarm-Berechtigung auf
                // API 31/32) darf diesen Service NIEMALS mit in den Absturz ziehen - sonst stirbt der
                // Prozess, stopAlarmAndService() wird nie erreicht und der Nutzer steht ohne Snooze
                // UND ohne jede Rueckmeldung da.
                val ergebnis = try {
                    AlarmManagerService.scheduleSnooze(
                        applicationContext, alarmId, shiftName, shiftStartTime,
                        minutes = snoozeMinutes.toLong()
                    )
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Snooze konnte nicht geplant werden (id=$alarmId)", e)
                    SnoozeErgebnis.FEHLGESCHLAGEN
                }

                // DAS ERGEBNIS ENTSCHEIDET, ob der Wecker aufhoeren darf. Bis zur Pruefrunde 8 lief
                // hier unbedingt stopAlarmAndService(): ein gescheiterter Schlummer sah aus wie ein
                // erfolgreicher - Ton aus, keine Meldung, kein Wecker. Wer schlummert, verschlief.
                //
                // Steht KEIN neuer Weckruf, klingelt der aktuelle weiter: dieser Dienst haelt die
                // einzige Wecker-Notification, ueber deren Stop-Knopf der Nutzer jederzeit
                // herauskommt. Ein lauter Wecker ist der kleinere Schaden als ein lautlos
                // verschwundener. Der Hinweis daneben sagt, WARUM nicht geschlummert wurde.
                if (ergebnis == SnoozeErgebnis.GEPLANT) {
                    stopAlarmAndService(startId)
                } else {
                    Logger.w(
                        LogTags.ALARM,
                        "⚠️ Schlummern nicht ausgefuehrt ($ergebnis) - der Wecker laeuft weiter, " +
                            "damit der Nutzer es merkt (id=$alarmId)"
                    )
                    posteSchlummerHinweis(this, ergebnis)
                }
            }

            ACTION_STOP_ALARM -> {
                Logger.i(LogTags.ALARM, "🛑 AlarmSoundService STOP_ALARM")
                stopAlarmAndService(startId)
            }

            else -> {
                Logger.w(LogTags.ALARM, "⚠️ AlarmSoundService received unknown action: ${intent?.action}")
                // Kein Wecker aktiv und nichts zu tun -> nicht als Zombie weiterlaufen lassen.
                if (!_alarmActive.value) stopSelf(startId)
            }
        }

        // START_REDELIVER_INTENT statt START_STICKY: nach einem System-Kill liefert Android denselben
        // START_ALARM-Intent erneut aus, statt mit intent == null zu starten (siehe Klassenkommentar).
        return START_REDELIVER_INTENT
    }

    /**
     * Postet die Wecker-Notification und geht in den Vordergrund.
     *
     * Eigene Funktion, weil sie an ZWEI Zeitpunkten laufen kann: sofort (Normalfall) oder nach
     * dem Vorweck-Vorlauf ([vorlaufFuerWeckbildschirm]). Der Inhalt ist in beiden Faellen
     * derselbe - insbesondere die Reihenfolge `startForeground` -> Sichtbarkeits-Diagnose.
     */
    private fun starteVordergrundUndWecken(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
            Logger.d(LogTags.ALARM, "✅ Foreground service started with alarm notification")
        } catch (e: Exception) {
            // Fangen, nicht durchreichen: ein Wurf aus einem verzoegerten Handler-Callback
            // beendet den PROZESS - und damit den klingelnden Wecker. Ton und Vibration laufen
            // bereits; ohne Notification fehlt die Oberflaeche, aber der Wecker weckt weiter.
            Logger.e(LogTags.ALARM, "❌ startForeground fuer die Wecker-Notification fehlgeschlagen", e)
            return
        }

        // DIAGNOSE, die im Release-Log landen MUSS (WARN): sind Benachrichtigungen
        // blockiert, laeuft dieser Dienst weiter - Ton und Vibration kommen -, aber seine
        // Notification wird unterdrueckt UND der Full-Screen-Intent abgelehnt. Der Nutzer
        // hat dann KEINE Oberflaeche, um den Wecker zu stoppen oder zu schlummern; der
        // einzige Ausweg ist "App beenden" in den Systemeinstellungen. Am Emulator im
        // echten Zustand gesehen (11.08.2026), und ohne diese Zeile war der Fall im Log
        // nicht von einem funktionierenden Wecker zu unterscheiden. Der Status-Tab hat
        // dafuer eine eigene Karte; hier geht es um die nachtraegliche Auswertbarkeit.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Logger.w(
                LogTags.ALARM,
                "⚠️ WECKER OHNE OBERFLAECHE: Benachrichtigungen sind fuer diese App " +
                    "blockiert - Ton laeuft, aber Weck-Bildschirm und Stopp-/Schlummer-" +
                    "Knoepfe erscheinen NICHT. Nur ueber die Systemeinstellungen zu beheben."
            )
        }
    }

    /**
     * Wie lange vor der Wecker-Notification soll der Bildschirm selbst geweckt werden - oder 0.
     *
     * Die Entscheidung selbst steht in [VorweckEntscheidung] (dort auch der vollstaendige Hergang
     * samt Messwerten); hier werden nur die drei Eingaben besorgt. Jeder Fehler dabei fuehrt zu 0,
     * also zum unveraenderten Verhalten - ein nicht lesbarer Zaehler darf keinen Wecker verzoegern.
     */
    private fun vorlaufFuerWeckbildschirm(): Long = try {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        VorweckEntscheidung.vorlaufMillis(
            geraetIstBetroffen = WeckbildschirmVerdraengungPrefs.jeVerdraengt(this),
            bildschirmAn = power.isInteractive,
            gesperrt = keyguard.isKeyguardLocked
        )
    } catch (e: Exception) {
        Logger.w(LogTags.ALARM, "Vorweck-Bedingung nicht pruefbar - Wecker laeuft unveraendert", e)
        0L
    }

    /**
     * Weckt den Bildschirm, BEVOR die Wecker-Notification gepostet wird.
     *
     * `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` ist derselbe Griff, den die
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity] schon benutzt; dass er
     * dieser App auch ohne `TURN_SCREEN_ON` erlaubt ist, steht im Systemlog des FP6
     * ("Allowing device wake-up without android.permission.TURN_SCREEN_ON for ...").
     *
     * Der Lock laeuft nach [VORWECK_LOCK_TIMEOUT_MS] von selbst aus und wird zusaetzlich beim
     * Beenden des Weckers freigegeben - er soll nur die Luecke bis zum eigenen Lock des
     * Weckbildschirms ueberbruecken, nicht laenger.
     */
    private fun weckeBildschirmVorab() {
        try {
            gibVorweckLockFrei()
            val power = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            vorweckLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                VORWECK_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(VORWECK_LOCK_TIMEOUT_MS)
            }
            Logger.w(
                LogTags.ALARM,
                "🌅 Vorwecken: Bildschirm wird ${VorweckEntscheidung.VORLAUF_MS} ms vor der Wecker-" +
                    "Notification geweckt (Verdraengung war zuvor gemessen)"
            )
        } catch (e: Exception) {
            // Folgenlos fuer den Wecker: ohne Vorwecken laeuft er wie bisher.
            Logger.e(LogTags.ALARM, "Vorwecken fehlgeschlagen - Wecker laeuft unveraendert", e)
        }
    }

    private fun gibVorweckLockFrei() {
        try {
            vorweckLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Vorweck-Lock nicht freigebbar", e)
        }
        vorweckLock = null
    }
    
    /**
     * Beendet den Wecker vollstaendig: Ton, Vibration, Audio-Fokus, Foreground-Notification und den
     * Service selbst. Gemeinsamer Endpunkt von STOP und SNOOZE (der Snooze plant vorher zusaetzlich
     * den naechsten Wecker).
     *
     * `_alarmActive = false` signalisiert der [AlarmFullScreenActivity], sich zu schliessen, falls sie
     * laeuft - sonst muesste der Nutzer zweimal stoppen (Leiste UND Vollbild).
     *
     * [startId] ist Pflicht, `stopSelf(startId)` statt blankem `stopSelf()` - dieselbe Ueberlegung
     * wie beim AlarmMaintenanceService: `stopSelf()` (= stopSelf(-1)) beendet den Service auch dann,
     * wenn nach dem ausloesenden Start bereits ein NEUER START_ALARM eingegangen ist. Feuern zwei
     * Alarme dicht hintereinander (dieselbe Schicht aus zwei ausgewaehlten Kalendern, oder Snooze
     * trifft auf regulaeren Alarm), raeumte das anschliessende onDestroy() Ton, Vibration und
     * Notification des GERADE gestarteten zweiten Weckers lautlos mit ab. Mit startId honoriert
     * Android den Stop nur, wenn kein neuerer Start dazwischen kam.
     */
    private fun stopAlarmAndService(startId: Int) {
        _alarmActive.value = false
        stopAlarmSound()
        stopVibration()
        abandonAudioFocus() // laesst pausierte Medien (Podcast/Musik) wieder anlaufen
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        Logger.d(LogTags.ALARM, "✅ AlarmSoundService stopped and cleaned up (startId=$startId)")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(LogTags.ALARM, "🛑 AlarmSoundService destroying - guaranteed cleanup")

        // Guaranteed cleanup on service destruction
        _alarmActive.value = false
        vorweckHandler.removeCallbacksAndMessages(null)
        gibVorweckLockFrei()
        stopAlarmSound()
        stopVibration()
        abandonAudioFocus()
    }

    /**
     * Fordert den Audio-Fokus an, damit fremde Player (Podcast, Musik) fuer die Dauer des
     * Weckers PAUSIEREN statt weiterzulaufen.
     *
     * AUDIOFOCUS_GAIN_TRANSIENT (nicht ..._MAY_DUCK): der andere Player erhaelt
     * AUDIOFOCUS_LOSS_TRANSIENT und pausiert. Nach [abandonAudioFocus] bekommt er
     * AUDIOFOCUS_GAIN und laeuft von selbst weiter - genau das erwartete Verhalten,
     * wenn der Nutzer den Wecker per Snooze wegdrueckt.
     *
     * Kein Fokus-Listener: der Wecker gibt den Fokus bewusst NIE freiwillig ab. Ein Anruf
     * unterbricht ihn ohnehin auf Systemebene, und alles andere darf einen Wecker nicht
     * leiser machen.
     */
    private fun requestAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()

            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.business(LogTags.ALARM, "🔇 Audio-Fokus erhalten - fremde Medien pausieren")
            } else {
                // Kein Abbruch: der Wecker klingelt trotzdem, nur eben ueber laufende Medien.
                Logger.w(LogTags.ALARM, "⚠️ Audio-Fokus verweigert (result=$result) - Wecker klingelt trotzdem")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Audio-Fokus konnte nicht angefordert werden", e)
        }
    }

    /**
     * Gibt den Audio-Fokus frei. Pausierte Medien laufen dadurch automatisch weiter.
     */
    private fun abandonAudioFocus() {
        try {
            val request = audioFocusRequest ?: return
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
            Logger.d(LogTags.ALARM, "🔊 Audio-Fokus freigegeben - pausierte Medien laufen weiter")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Fehler beim Freigeben des Audio-Fokus", e)
        }
    }
    
    // onTaskRemoved() bewusst NICHT ueberschrieben: stopWithTask="false" (Manifest) haelt den
    // Foreground-Service beim Wegwischen des Tasks aus den Recents am Leben - der Wecker soll
    // weiterklingeln, bis der Nutzer ihn bewusst per Dismiss/Snooze beendet. Ein frueheres
    // stopSelf() an dieser Stelle hebelte genau diese Garantie aus.

    /**
     * Starts alarm sound using MediaPlayer.
     *
     * Releases any pre-existing player first (idempotent), then stamps the new
     * player with a generation token.  The OnPreparedListener checks both the
     * shutdown flag AND the token so that a stale async callback from a previous
     * player can never start playback even if isShuttingDown was reset in the
     * meantime (e.g. stop → snooze → start race).
     *
     * DIRECT BOOT: Der Service ist directBootAware, laeuft also ggf. VOR der ersten Entsperrung.
     * Die RingtoneManager-URIs sind dann `content://media/...`-Verweise auf den MediaProvider und
     * koennen unaufloesbar sein. Deshalb werden ALLE Kandidaten der Reihe nach versucht (frueher
     * fing die `?:`-Kette nur den null-Fall ab, nicht ein scheiterndes setDataSource) und ein
     * vollstaendiges Scheitern laut geloggt - sonst blieb der Wecker stumm, ohne Spur im Log.
     */
    private fun startAlarmSound() {
        if (isShuttingDown) {
            Logger.w(LogTags.ALARM, "🚫 Service shutting down, not starting sound")
            return
        }

        // Release any previous player before creating a new one.
        // This prevents duplicate playback when START_ALARM is received twice.
        alarmMediaPlayer?.let { prev ->
            try { if (prev.isPlaying) prev.stop() } catch (_: Exception) {}
            try { prev.release() } catch (_: Exception) {}
        }
        alarmMediaPlayer = null

        // Capture this player's generation. Any OnPreparedListener from an older
        // player will see a mismatched generation and release itself instead of starting.
        val myGeneration = ++playerGeneration

        val candidates = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ).distinct()

        if (candidates.isEmpty()) {
            Logger.w(LogTags.ALARM, "⚠️ No alarm URIs available for MediaPlayer - Wecker bleibt stumm, nur Vibration")
            return
        }

        for (alarmUri in candidates) {
            // Lokale Referenz VOR dem Konfigurieren: `alarmMediaPlayer = MediaPlayer().apply {...}`
            // weist das Feld erst zu, wenn der apply-Block durchgelaufen ist - scheitert
            // setDataSource(), waere der frisch erzeugte Player unerreichbar und nie freigegeben
            // (ein geleakter MediaPlayer pro Fehlversuch).
            var candidatePlayer: MediaPlayer? = null
            try {
                Logger.d(LogTags.ALARM, "🎵 Starting MediaPlayer with URI: $alarmUri")

                candidatePlayer = MediaPlayer()
                alarmMediaPlayer = candidatePlayer
                candidatePlayer.apply {
                    setDataSource(this@AlarmSoundService, alarmUri)

                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )

                    isLooping = true

                    setOnPreparedListener { player ->
                        // Guard: only start if this is still the active player
                        if (!isShuttingDown && myGeneration == playerGeneration) {
                            try {
                                player.start()
                                Logger.business(LogTags.ALARM, "✅ MediaPlayer started successfully in service")
                            } catch (e: Exception) {
                                Logger.e(LogTags.ALARM, "❌ MediaPlayer start failed", e)
                            }
                        } else {
                            Logger.d(
                                LogTags.ALARM,
                                "🚫 Player gen=$myGeneration superseded (current=$playerGeneration, shutting=$isShuttingDown) — releasing"
                            )
                            try { player.release() } catch (_: Exception) {}
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Logger.e(LogTags.ALARM, "❌ MediaPlayer error: what=$what, extra=$extra")
                        false
                    }

                    prepareAsync()
                    Logger.d(LogTags.ALARM, "🔄 MediaPlayer preparing asynchronously")
                }
                return

            } catch (e: Exception) {
                // Naechsten Kandidaten versuchen: eine unaufloesbare MediaStore-URI (Direct Boot!)
                // darf nicht das Ende des Weckers sein.
                Logger.w(LogTags.ALARM, "⚠️ Weckton-Quelle nicht nutzbar: $alarmUri", e)
                candidatePlayer?.let { try { it.release() } catch (_: Exception) {} }
                alarmMediaPlayer = null
            }
        }

        Logger.e(
            LogTags.ALARM,
            "❌ KEINE Weckton-Quelle konnte geoeffnet werden (${candidates.size} versucht) - " +
                "der Wecker weckt nur per Vibration und Vollbild. Typischer Fall: Direct Boot " +
                "vor der ersten Entsperrung, MediaStore-URI nicht aufloesbar."
        )
    }

    /**
     * Stops alarm sound and releases MediaPlayer.
     *
     * Sets isShuttingDown first, then increments playerGeneration so that any
     * still-pending OnPreparedListener callback is invalidated on both guards.
     */
    private fun stopAlarmSound() {
        isShuttingDown = true
        playerGeneration++ // invalidate any pending OnPreparedListener callbacks

        try {
            alarmMediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                        Logger.d(LogTags.ALARM, "🔇 MediaPlayer stopped")
                    }
                } catch (e: Exception) {
                    Logger.w(LogTags.ALARM, "⚠️ Error stopping MediaPlayer", e)
                }

                try {
                    player.release()
                    Logger.d(LogTags.ALARM, "♻️ MediaPlayer released")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Error releasing MediaPlayer", e)
                }
            }

            alarmMediaPlayer = null
            Logger.i(LogTags.ALARM, "✅ MediaPlayer stopped and released successfully")

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Critical error stopping MediaPlayer", e)
        }
    }
    
    /**
     * Starts vibration pattern for alarm
     */
    private fun startVibration() {
        try {
            // Get vibrator using appropriate API for Android version
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31): Use VibratorManager
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager.defaultVibrator
            } else {
                // Pre-Android 12: Use legacy Vibrator service
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            // Enhanced vibration pattern for alarm (in milliseconds)
            // Pattern: [delay, vibrate, pause, vibrate, pause, ...]
            //
            // DAS HIER IST DAS EINZIGE ECHTE MUSTER - bitte keine Konstante daraus machen, ohne
            // sie auch zu benutzen. Bis v1.26.1 gab es ZWEI ungenutzte `ALARM_VIBRATION_PATTERN`
            // (in util/timing/TimingConstants.kt und util/theme/UIConstants.kt, unterschiedlich
            // lang) - beide las niemand, weil der Dienst schon immer dieses Muster inline
            // aufbaute. Wer eine der Konstanten "korrigiert" haette, haette am Wecker nichts
            // veraendert und es erst am Geraet gemerkt.
            val alarmVibrationPattern = longArrayOf(
                0,    // Start immediately
                1000, // Vibrate for 1 second
                300,  // Pause 300ms
                800,  // Vibrate for 800ms
                300,  // Pause 300ms
                1000, // Vibrate for 1 second
                500,  // Pause 500ms
                500   // Short vibration
            )
            
            vibrator?.let { vib ->
                if (vib.hasVibrator()) {
                    // Create waveform effect that repeats from index 1
                    val vibrationEffect = VibrationEffect.createWaveform(
                        alarmVibrationPattern,
                        1 // Repeat from index 1 (loops the pattern)
                    )
                    vib.vibrate(vibrationEffect)
                    Logger.business(LogTags.ALARM, "✅ Vibration started in service")
                } else {
                    Logger.d(LogTags.ALARM, "📴 Device has no vibrator capability")
                }
            } ?: run {
                Logger.w(LogTags.ALARM, "⚠️ Could not obtain vibrator service")
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start vibration in service", e)
        }
    }
    
    /**
     * Stops vibration
     */
    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
            Logger.d(LogTags.ALARM, "✅ Vibration stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error stopping vibration", e)
        }
    }
    
    /**
     * Legt den Wecker-Kanal an. Idempotent, darf beliebig oft laufen.
     */
    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Den Altbestand raeumen, BEVOR der neue Kanal entsteht: auf Geraeten, die vor v1.9.7
        // einmal geweckt haben, liegt unter der alten ID ein Kanal auf IMPORTANCE_LOW, den keine
        // Neuanlage mehr anheben kann (Android aendert die Importance eines bestehenden Kanals nur
        // nach unten). Er wird nicht mehr bespielt, wuerde ohne dieses Loeschen aber weiter als
        // zweiter, stummer "Schicht-Wecker" in den Systemeinstellungen stehen und den Nutzer bei
        // jeder Reparatur in die falsche Kategorie schicken.
        try {
            notificationManager.deleteNotificationChannel(ALTER_CHANNEL_ID)
        } catch (e: Exception) {
            // Nie kritisch: der neue Kanal entsteht gleich darunter unabhaengig davon.
            Logger.w(LogTags.ALARM, "⚠️ Alter Wecker-Kanal nicht loeschbar: ${e.message}")
        }

        // IMPORTANCE_HIGH ist Pflicht: Ein Full-Screen-Intent wird vom System ignoriert, wenn der
        // Channel darunter liegt. Der Channel bleibt aber bewusst STUMM und vibrationsfrei -
        // Ton und Vibration kommen ausschliesslich vom MediaPlayer bzw. startVibration().
        // Frueher setzte der AlarmReceiver auf seinem eigenen Channel zusaetzlich einen
        // Klingelton: das ergab zwei gleichzeitig laufende Weckertoene.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true) // Wecker muss auch bei "Nicht stören" durchkommen
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(channel)

        Logger.d(LogTags.ALARM, "📢 Notification channel created: $CHANNEL_ID")
    }

    /**
     * Baut die EINZIGE Alarm-Notification: Full-Screen-Intent, Stop- und Snooze-Button.
     *
     * Der Stop-Button ist der immer erreichbare Notausgang: er beendet den Weckton direkt ueber
     * den Service, auch wenn die Full-Screen-Activity gar nicht erst hochkam (restriktive OEMs)
     * oder zerstoert wurde (Anruf, Speicherdruck).
     */
    private fun createAlarmNotification(
        shiftName: String,
        shiftStartTime: String,
        alarmId: Int,
        snoozeMinutes: Int
    ): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AlarmSoundService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Snooze-Button: der Notausgang zum Schlummern, wenn das Vollbild nicht sichtbar hochkommt.
        // Traegt Schicht + alarmId als Extras, weil die Service-Instanz, die den Snooze verarbeitet,
        // sie sonst nicht mehr kennt. Eigener requestCode (1) neben dem Stop-Slot (0). Der
        // Schichtbeginn muss ebenfalls mit, sonst zeigt die Notification nach dem Schlummern
        // "Deine Schicht beginnt um " ohne Uhrzeit.
        val snoozeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmSoundService::class.java).apply {
                action = ACTION_SNOOZE_ALARM
                putExtra(EXTRA_SHIFT_NAME, shiftName)
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SHIFT_START_TIME, shiftStartTime)
                putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val fullScreenIntent = PendingIntent.getActivity(
            this,
            alarmId,
            Intent(this, AlarmFullScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SHIFT_NAME, shiftName)
                putExtra(EXTRA_SHIFT_START_TIME, shiftStartTime)
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
                setPackage(packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Leere Uhrzeit sauber abfangen statt "Deine Schicht beginnt um " anzuzeigen.
        val contentText = if (shiftStartTime.isBlank()) {
            getString(R.string.alarm_notification_text_no_time)
        } else {
            getString(R.string.alarm_notification_text, shiftStartTime)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.alarm_notification_title, shiftName))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(
                android.R.drawable.ic_menu_recent_history,
                // Die Beschriftung traegt DIESELBE Variable, die gleich in scheduleSnooze() geht -
                // vorher stand fest "5 Min spaeter" auf dem Knopf, waehrend die eingestellte Dauer
                // 3, 10 oder 15 Minuten betragen konnte.
                getString(R.string.alarm_notification_snooze, snoozeMinutes),
                snoozeIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.alarm_notification_stop),
                stopIntent
            )
            .build()
        // Bewusst KEIN setTimeoutAfter(): die Notification gehoert zu einem Foreground-Service
        // und darf nicht verfallen, solange der Ton laeuft - sonst verlaere der Nutzer den
        // Stop-Button unter den Fingern.
    }
}
