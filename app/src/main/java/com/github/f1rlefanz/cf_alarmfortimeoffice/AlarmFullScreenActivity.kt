package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Einweg-Sperre: genau EINE der beiden Wecker-Handlungen (Dismiss ODER Snooze) darf laufen.
 *
 * REAL BELEGT (Log 05.08.2026, 05:30:07): "🛑 User dismissed alarm" um .596 und "😴 User snoozed
 * alarm for 5 minutes" um .620 — 24ms auseinander, beide Handler liefen vollständig durch. Für
 * einen Menschen sind 24ms unerreichbar; die zwei Knöpfe liegen bildschirmfüllend direkt
 * übereinander (12dp Abstand am unteren Rand), und Compose gibt jedem gleichzeitigen Zeiger seinen
 * eigenen Klick — eine Handkante/ein Daumenballen beim blinden Greifen im Halbschlaf trifft beide.
 * Folge damals: der Nutzer drückte "Alarm stoppen" und bekam trotzdem einen Schlummer-Wecker 5
 * Minuten später. Umgekehrt räumt ein nachlaufendes Dismiss den gerade geplanten Snooze wieder ab —
 * dann wird gar nicht mehr geweckt.
 *
 * Kein Debounce nach Zeit, sondern eine echte Einweg-Sperre: der erste bewusste Griff gewinnt, jeder
 * weitere ist per Definition ein Versehen.
 *
 * Bewusst als eigene, Android-freie Klasse NEBEN der Activity (nicht als privates Feld darin): so
 * ist der Vertrag ohne Instrumentierung testbar ([com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenHandoffTest]) —
 * eine echte Gleichzeitigkeit ließ sich per adb nicht erzeugen, deshalb war der Fix vorher nur
 * durch seinen Kommentar abgesichert.
 *
 * [AtomicBoolean.compareAndSet] statt eines einfachen `var`: die Klick-Handler laufen zwar beide auf
 * dem Hauptthread, aber genau das war die Annahme, die den Bug erst zu einem Rätsel gemacht hat —
 * eine atomare Prüf-und-Setz-Operation ist hier kostenlos und schließt auch den Fall aus, dass die
 * Auslösung je über einen anderen Thread kommt.
 *
 * Der Notausgang bleibt unberührt: die Notification-Knöpfe gehen direkt an den
 * [AlarmSoundService], nicht durch diese Activity — und `stopAndClose()` fragt die Sperre bewusst
 * nicht.
 */
internal class OneShotAlarmHandoff {

    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true nur beim ERSTEN Aufruf; jeder weitere Aufruf liefert false. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** Wurde die Sperre schon beansprucht? Reine Abfrage, beansprucht selbst nichts. */
    val isClaimed: Boolean get() = claimed.get()
}

/**
 * Vollbild-Wecker über dem Sperrbildschirm.
 *
 * ROLLENVERTEILUNG (v3.0):
 * - [AlarmSoundService] besitzt Ton, Vibration, Audio-Fokus UND die einzige Alarm-Notification.
 * - Diese Activity ist reine UI: anzeigen, Dismiss/Snooze auslösen, sich selbst schließen.
 *
 * Die Activity wird ausschließlich über den Full-Screen-Intent der Service-Notification
 * gestartet. Das ist auf Android 10+ der einzige erlaubte Weg, aus dem Hintergrund eine
 * Activity zu zeigen — ein direktes startActivity() aus dem AlarmReceiver wird verworfen.
 *
 * ERWARTUNGSMANAGEMENT: Ist das Gerät entsperrt und in Benutzung, zeigt Android laut Doku
 * bewusst nur eine Heads-up-Notification statt des Vollbilds ("While the user is using the
 * device, the system UI might display a heads-up notification instead of launching your
 * full-screen intent"). Das Vollbild erscheint automatisch beim gesperrten/dunklen Gerät,
 * also im echten Weckfall. Ein Test mit entsperrtem Handy in der Hand bildet das nicht ab.
 */
class AlarmFullScreenActivity : AppCompatActivity() {

    companion object {
        private const val WAKE_LOCK_TAG = "CFAlarm:FullScreenActivity"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Schichtname und Schichtbeginn als Compose-State, NICHT als lokale `val` in onCreate.
     *
     * Das ist der Unterschied zwischen "der Weck-Bildschirm zeigt den Alarm, der gerade klingelt"
     * und "er zeigt den, der als Erstes geklingelt hat": bei `launchMode="singleTask"` kommt eine
     * zweite Zustellung als onNewIntent an derselben Instanz an, und ohne State gibt es nichts,
     * was rekomponieren koennte. Wer das hier wieder zu einem `val` in onCreate macht, baut den
     * Fehler zurueck - siehe [readShiftFromIntent].
     */
    private var shiftName by mutableStateOf("")
    private var shiftStartTime by mutableStateOf("")

    /**
     * Einweg-Sperre gegen Doppelauslösung von Dismiss/Snooze — siehe [OneShotAlarmHandoff].
     * Dient zusätzlich dem alarmActive-Observer als "wurde hier schon bewusst gehandelt?".
     */
    private val alarmHandoff = OneShotAlarmHandoff()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity starting (Compose v3.0)")

        // Fenster-Flags VOR setContent: showWhenLocked/turnScreenOn müssen greifen, bevor
        // das Fenster sichtbar wird.
        setupLockScreenFlags()

        acquireWakeLock()
        setupBackButtonHandling()

        readShiftFromIntent()

        setContent {
            CFAlarmForTimeOfficeTheme {
                AlarmScreen(
                    shiftName = shiftName,
                    shiftStartTime = shiftStartTime,
                    onDismiss = ::dismissAlarm,
                    onSnooze = ::snoozeAlarm
                )
            }
        }

        // Systemleisten ausblenden ERST NACH setContent: window.insetsController ist vorher
        // null, weil die DecorView noch nicht existiert. Genau das war die NullPointerException
        // "Failed to configure modern insets", die bei jedem Alarm im Log stand.
        hideSystemBars()

        // Der Wecker kann auch über den "Wecker aus"-Button der Notification beendet werden.
        // Dann muss sich dieses Vollbild von selbst schließen — sonst müsste der Nutzer den
        // Wecker zweimal stoppen (einmal in der Leiste, einmal hier).
        observeAlarmState()

        Logger.i(LogTags.ALARM, "✅ AlarmFullScreenActivity initialized: $shiftName at $shiftStartTime")
        Logger.i(
            LogTags.ALARM,
            "🔎 FSI-DIAG onCreate: ${visibilitySnapshot()}, recreated=${savedInstanceState != null}, " +
                "taskId=$taskId, isTaskRoot=$isTaskRoot, canUseFsi=${canUseFullScreenIntentNow()}"
        )
    }

    /**
     * Bei launchMode="singleTask" liefert eine ZWEITE Zustellung desselben Full-Screen-Intents
     * onNewIntent statt onCreate — die Activity bleibt dieselbe Instanz. Ohne setIntent() bliebe
     * `intent` auf dem alten Stand, und snoozeAlarm() laese Schicht/ID/Snooze-Dauer aus dem
     * VORHERIGEN Alarm. Das ist real erreichbar: der Snooze-Wecker feuert erneut, waehrend die
     * Activity noch (gestoppt, aber nicht zerstoert) im Task liegt.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // setIntent() allein reichte NICHT, gefunden in der Pruefrunde vom 18.08.2026: Schicht und
        // Schichtbeginn wurden in onCreate als lokale `val` gelesen und in den setContent-Block
        // eingeschlossen. Ohne State gab es nichts, was rekomponieren koennte - das Vollbild zeigte
        // bei einer Wiederzustellung weiter Namen und Beginn des VORHERIGEN Alarms, waehrend Ton,
        // Snooze und Dismiss (die `intent` lesen) bereits zum neuen gehoerten. Wer im Halbschlaf
        // "Fruehschicht 06:00" liest, obwohl der Wecker fuer die Spaetschicht klingelt, legt sich
        // wieder hin. Kein stummer Wecker, aber eine falsche Aussage an der einen Stelle, an der
        // die App keine zweite Chance bekommt.
        readShiftFromIntent()

        // Derselbe Grund fuer den Wake-Lock: er wurde ausschliesslich in onCreate erworben und
        // laeuft nach WAKE_LOCK_TIMEOUT aus. Eine Wiederzustellung nach Ablauf (Snooze-Refire an
        // einer noch lebenden, gestoppten Instanz) haette den Bildschirm nicht mehr hell gehalten -
        // genau der Effekt, gegen den acquireWakeLock() ueberhaupt existiert. Erst freigeben, dann
        // neu erwerben: newWakeLock() legt jedes Mal ein neues Objekt an, ein blosses Nach-Erwerben
        // wuerde das alte verlieren, ohne es je zu releasen.
        releaseWakeLock()
        acquireWakeLock()

        Logger.i(LogTags.ALARM, "🔎 FSI-DIAG onNewIntent (singleTask-Wiederzustellung): ${visibilitySnapshot()}")
    }

    /**
     * Liest Schichtname und Schichtbeginn aus dem AKTUELLEN `intent` in den Compose-State.
     * Aufgerufen aus onCreate UND onNewIntent - beide Wege muessen dieselbe Anzeige ergeben.
     */
    private fun readShiftFromIntent() {
        shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME)
            ?: getString(R.string.alarm_unknown_shift)
        shiftStartTime = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME).orEmpty()
    }

    override fun onStart() {
        super.onStart()
        Logger.d(LogTags.ALARM, "▶️ AlarmFullScreenActivity STARTED: ${visibilitySnapshot()}")
    }

    override fun onStop() {
        super.onStop()

        // Wecker hier NICHT stoppen: onStop feuert auch bei Bildschirm-Aus (Power-Taste im
        // Halbschlaf), eingehendem Anruf, App-Wechsel oder Rotation. Der Ton laeuft im
        // Foreground-Service weiter und wird ausschliesslich durch bewusstes Dismiss/Snooze beendet.

        // DIAGNOSE (v1.23.0): Verschwindet das Vollbild, waehrend der Wecker weiterklingelt, ist das
        // der Fehlerfall vom 05.08.2026 (STOPPED 276ms nach initialized, Ton lief 11s weiter). Der
        // Snapshot trennt die Ursachen, die sich sonst NICHT unterscheiden lassen: interactive=false
        // => Bildschirm ist ausgegangen (Wake-Lock wirkungslos), interactive=true + focus=false =>
        // ein fremdes Fenster (Keyguard, Systemdialog, andere Activity) liegt darueber.
        // Bewusst WARN: muss auch im Release-Log auftauchen, dort landet nur WARN+.
        val stoppedWhileRinging = AlarmSoundService.alarmActive.value && !isFinishing
        val detail = "${visibilitySnapshot()}, isFinishing=$isFinishing, " +
            "changingConfig=$isChangingConfigurations, userHandled=${alarmHandoff.isClaimed}"
        if (stoppedWhileRinging) {
            Logger.w(
                LogTags.ALARM,
                "⚠️ AlarmFullScreenActivity STOPPED, obwohl der Wecker noch laeuft — $detail"
            )
        } else {
            Logger.d(LogTags.ALARM, "⏹️ AlarmFullScreenActivity STOPPED — $detail")
        }

        releaseWakeLock()
    }

    /**
     * Der einzige Weg, "ein fremdes Fenster liegt darueber" von "Bildschirm ist aus" zu
     * unterscheiden: Fokusverlust bei weiterhin eingeschaltetem Bildschirm.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && AlarmSoundService.alarmActive.value) {
            Logger.w(LogTags.ALARM, "⚠️ Vollbild verliert Fensterfokus bei laufendem Wecker — ${visibilitySnapshot()}")
        } else {
            Logger.d(LogTags.ALARM, "🔎 FSI-DIAG Fensterfokus=$hasFocus: ${visibilitySnapshot()}")
        }
    }

    /**
     * Ein Zustandsabbild der Sichtbarkeits-Voraussetzungen. Absichtlich in EINER Zeile und ohne
     * PII — es soll im Release-Log neben der WARN-Zeile stehen koennen.
     */
    private fun visibilitySnapshot(): String {
        val interactive = try {
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        } catch (e: Exception) {
            null
        }
        val keyguard = try {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        } catch (e: Exception) {
            null
        }
        // displayManager statt activity.display: display existiert erst ab API 30, minSdk ist 26.
        val displayState = try {
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.state
        } catch (e: Exception) {
            null
        }
        return "interactive=$interactive, display=${displayStateName(displayState)}, " +
            "keyguardLocked=${keyguard?.isKeyguardLocked}, deviceSecure=${keyguard?.isDeviceSecure}, " +
            "wakeLockHeld=${wakeLock?.isHeld}"
    }

    private fun displayStateName(state: Int?): String = when (state) {
        null -> "unknown"
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "state$state"
    }

    /**
     * Die Berechtigung wird bisher nur beim PLANEN geprueft (AlarmManagerService) und in der
     * Status-Karte. Kommt sie zwischen Planung und Weckzeit weg, sagt bisher kein Log, dass das
     * Vollbild deshalb ausblieb.
     */
    private fun canUseFullScreenIntentNow(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
            } catch (e: Exception) {
                null
            }
        } else {
            true
        }

    override fun onDestroy() {
        super.onDestroy()

        // Kein Service-Stop hier: Der Wecker soll weiterklingeln, wenn die Activity ohne bewusstes
        // Dismiss/Snooze zerstoert wird (Rotation, Prozess-Tod, Task-Swipe). Dismiss/Snooze stoppen
        // den Ton bereits explizit vor finish().

        releaseWakeLock()
        wakeLock = null
        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity destroyed")
    }

    /**
     * Schließt das Vollbild, sobald kein Wecker mehr läuft.
     *
     * Bewusst OHNE drop(1) auf dem Replay-Wert des StateFlow: "kein Wecker aktiv" ist immer ein
     * Grund zu schließen, egal ob der Zustand gerade eintritt oder schon galt. Das deckt drei
     * Fälle mit derselben Regel ab:
     *  - "Wecker aus" in der Notification, während das Vollbild sichtbar ist
     *  - "Wecker aus", während das Vollbild im Hintergrund liegt (repeatOnLifecycle sammelt
     *    beim Zurückkommen erneut und sieht den bereits gefallenen Zustand)
     *  - Prozesstod: das System stellt die Activity wieder her, obwohl kein Service mehr läuft
     *
     * Voraussetzung dafür ist, dass AlarmSoundService alarmActive VOR startForeground() setzt —
     * sonst könnte die vom Full-Screen-Intent gestartete Activity ein noch false lesen und sich
     * sofort wieder schließen.
     */
    private fun observeAlarmState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmSoundService.alarmActive
                    .filter { active -> !active }
                    .collect {
                        if (!alarmHandoff.isClaimed) {
                            Logger.i(LogTags.ALARM, "🔕 Kein Wecker mehr aktiv — Vollbild schließt sich")
                        }
                        finish()
                    }
            }
        }
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInheritShowWhenLocked(true)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Blendet Status- und Navigationsleiste aus. Muss NACH setContent laufen (siehe onCreate).
     * WindowCompat kapselt die API-Unterschiede, deshalb keine Versions-Verzweigung mehr.
     */
    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            Logger.d(LogTags.ALARM, "✅ Systemleisten ausgeblendet")
        } catch (e: Exception) {
            // Nicht kritisch: der Wecker funktioniert auch mit sichtbaren Leisten.
            Logger.w(LogTags.ALARM, "⚠️ Systemleisten konnten nicht ausgeblendet werden", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            // SCREEN_BRIGHT statt PARTIAL: Ein PARTIAL_WAKE_LOCK haelt nur die CPU wach, NICHT den
            // Bildschirm. setTurnScreenOn() weckt den Screen zwar initial an - aber ohne einen
            // screen-haltenden Wakelock dozte er auf einem echten Geraet ~0,5s spaeter zurueck, die
            // Activity bekam onStop, und das Vollbild war wieder weg (am Fairphone/Android 16 im Log
            // belegt: start 05:30:00.698 -> STOPPED 05:30:01.175, waehrend der Wecker weiterlief).
            // SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP haelt den Bildschirm bis zum Release
            // bzw. bis zum 10-Min-Timeout hell und weckt ihn beim Erwerb.
            //
            // Deprecated seit API 17 zugunsten von FLAG_KEEP_SCREEN_ON/setTurnScreenOn - die haben
            // wir bereits (setupLockScreenFlags), sie reichen auf echten Geraeten aber nachweislich
            // NICHT. Deshalb bewusst der alte, weiterhin funktionierende Weg. Kein Keyguard-Dismiss:
            // setShowWhenLocked macht Stop/Snooze schon ohne Entsperren nutzbar, requestDismissKeyguard
            // wuerde auf sicherem Sperrbildschirm nur unnoetig eine PIN-Abfrage zur Weckzeit erzwingen.
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT)
            }
            Logger.business(LogTags.ALARM, "✅ Screen wake lock acquired for full-screen activity")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Logger.d(LogTags.ALARM, "✅ Wake lock released")
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error releasing wake lock", e)
        }
    }

    private fun setupBackButtonHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Logger.d(LogTags.ALARM, "🚫 Back button pressed - ignoring")
            }
        })
    }

    private fun stopAlarmSoundService() {
        val serviceIntent = Intent(this, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_STOP_ALARM
        }
        startService(serviceIntent)
        Logger.i(LogTags.ALARM, "✅ AlarmSoundService stop requested")
    }

    private fun dismissAlarm() {
        if (!alarmHandoff.claim()) {
            Logger.w(LogTags.ALARM, "🚫 Dismiss ignoriert — Wecker wurde in dieser Activity schon behandelt")
            return
        }
        Logger.i(LogTags.ALARM, "🛑 User dismissed alarm")
        stopAndClose()
    }

    /**
     * Ton stoppen, Alarm-Notification abräumen, Vollbild schließen. Gemeinsamer Endpunkt von
     * Dismiss und des Snooze-Fehlerpfads — der darf NICHT über [dismissAlarm] laufen, weil die
     * Doppelauslösungs-Sperre dann schon zugeschlagen hätte und den Notausgang blockierte.
     *
     * Deshalb ruft diese Funktion selbst KEIN [OneShotAlarmHandoff.claim] — der Notausgang muss
     * auch nach bereits beanspruchter Sperre noch durchlaufen. Wer hier ein claim() ergänzt, macht
     * den Snooze-Fehlerpfad wirkungslos: der Wecker klingelte dann weiter, obwohl der Snooze
     * gescheitert ist.
     */
    private fun stopAndClose() {
        stopAlarmSoundService()

        // Gezielt die Alarm-Notification abräumen statt cancelAll(): cancelAll() löschte auch
        // fremde App-Notifications wie die Skip-Bestätigung mit weg.
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)

        finish()
    }

    /**
     * Snoozed den Wecker: stoppt den Ton und legt ueber den gemeinsamen
     * [AlarmManagerService.scheduleSnooze] einen neuen Alarm an - denselben Weg nutzt der
     * Snooze-Button der Benachrichtigung. Die Planungslogik (snoozeAlarmAction, requestCode,
     * setAlarmClock) liegt bewusst nur dort, damit es EINE Wahrheit bleibt.
     */
    private fun snoozeAlarm() {
        if (!alarmHandoff.claim()) {
            Logger.w(LogTags.ALARM, "🚫 Snooze ignoriert — Wecker wurde in dieser Activity schon behandelt")
            return
        }
        // Fallback-Default matters: aeltere/Direct-Boot-Pfade koennten das Extra nicht mitfuehren.
        val snoozeMinutes = intent.getIntExtra(
            AlarmSoundService.EXTRA_SNOOZE_MINUTES,
            AlarmManagerService.SNOOZE_MINUTES.toInt()
        )
        Logger.i(LogTags.ALARM, "😴 User snoozed alarm for $snoozeMinutes minutes")

        try {
            // Ton zuerst stoppen, dann Snooze planen (verhindert MediaPlayer-Races).
            stopAlarmSoundService()

            val shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME) ?: "Snooze"
            val alarmId = intent.getIntExtra(AlarmSoundService.EXTRA_ALARM_ID, -1)
            val shiftStartTime = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME).orEmpty()

            AlarmManagerService.scheduleSnooze(
                this, alarmId, shiftName, shiftStartTime,
                minutes = snoozeMinutes.toLong()
            )

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)
            finish()

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to snooze alarm", e)
            stopAndClose()
        }
    }
}

/**
 * Der Wecker-Screen im Corporate Design.
 *
 * Nutzt bewusst die Theme-Rollen statt hartkodierter Farben — der Screen hing frueher auf
 * Material-Default-Blau (#1976D2).
 *
 * FARBGEBUNG: heller Hintergrund (`surface`) mit roten Akzenten (`primary`), NICHT
 * vollflaechiges Rot. Ein rot geflutetes Vollbild las sich beim Wecken wie "die Welt geht
 * unter"; rot-auf-hell ist genauso eindeutig als Wecker erkennbar, aber ruhiger. Die grosse
 * Aktion "Alarm stoppen" bleibt als gefuellter roter Knopf klar die Haupt-Handlung.
 */
@Composable
private fun AlarmScreen(
    shiftName: String,
    shiftStartTime: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 48.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Alarm,
                    // dekorativ: direkt darunter steht R.string.alarm_title ("⏰ CF-ALARM"),
                    // dazu Schichtname und Schichtbeginn - der Screenreader liest den Anlass
                    // bereits im Klartext vor
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.alarm_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = shiftName,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                if (shiftStartTime.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.alarm_shift_start, shiftStartTime),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.alarm_dismiss_button),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.alarm_snooze_button),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}
