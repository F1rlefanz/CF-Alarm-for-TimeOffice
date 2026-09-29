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
import androidx.compose.runtime.mutableIntStateOf
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SchlummerEntscheidung
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SchlummerMeldung
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SnoozeErgebnis
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WeckbildschirmVerdraengungPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Einweg-Sperre: genau EINE der beiden Wecker-Handlungen (Dismiss ODER Snooze) darf laufen -
 * der erste bewusste Griff gewinnt, jeder weitere ist per Definition ein Versehen. Kein Debounce
 * nach Zeit, sondern eine echte Einweg-Sperre.
 *
 * Android-frei neben der Activity, damit der Vertrag ohne Instrumentierung testbar ist
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenHandoffTest]) - echte
 * Gleichzeitigkeit ließ sich per adb nicht erzeugen. [AtomicBoolean.compareAndSet] statt `var`
 * kostet nichts und schließt auch eine Auslösung über einen anderen Thread aus.
 *
 * Der Notausgang bleibt unberührt: die Notification-Knöpfe gehen direkt an den
 * [AlarmSoundService], und `stopAndClose()` fragt die Sperre bewusst nicht.
 * Hergang: Skill cfalarm-wecker-und-boot, reference/wecker-boot-und-wartung.md.
 */
internal class OneShotAlarmHandoff {

    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true nur beim ERSTEN Aufruf; jeder weitere Aufruf liefert false. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** Wurde die Sperre schon beansprucht? Reine Abfrage, beansprucht selbst nichts. */
    val isClaimed: Boolean get() = claimed.get()
}

/**
 * Die Entscheidung "ist das ein ANDERER Weckvorgang?" — Android-frei und damit ohne
 * Instrumentierung testbar (wie [OneShotAlarmHandoff]). Gebraucht in
 * [AlarmFullScreenActivity.uebernimmAlarmAusIntent]; Begruendung im KDoc von
 * [AlarmFullScreenActivity.onNewIntent].
 */
internal object Weckvorgang {

    /**
     * Der Wert, den `getIntExtra` liefert, wenn die Kennung fehlt. Deckungsgleich mit dem
     * Fallback, den auch [AlarmReceiver] und [AlarmSoundService] verwenden.
     */
    const val ID_UNBEKANNT = -1

    /**
     * true NUR, wenn beide Kennungen bekannt sind UND sich unterscheiden.
     *
     * Die Richtung des Zweifels ist bewusst gewaehlt: fehlt eine der beiden Kennungen, gilt
     * "derselbe Vorgang" — also NICHT zuruecksetzen. Halten wir einen neuen Wecker faelschlich
     * fuer denselben, sieht der Nutzer eine Warnung ueber einem laut klingelnden Wecker und kann
     * ihn weiterhin stoppen. Halten wir denselben Wecker faelschlich fuer einen neuen,
     * verschwindet die Warnung "Es ist KEIN weiterer Weckruf geplant", und er legt sich ohne
     * gestellten Wecker hin. Nur der zweite Irrtum kostet einen Wecker — im Zweifel also die
     * Warnung stehen lassen.
     */
    fun istAnderer(bisher: Int, neu: Int): Boolean =
        bisher != ID_UNBEKANNT && neu != ID_UNBEKANNT && bisher != neu
}

/**
 * Vollbild-Wecker über dem Sperrbildschirm.
 *
 * ROLLENVERTEILUNG:
 * - [AlarmSoundService] besitzt Ton, Vibration, Audio-Fokus UND die einzige Alarm-Notification.
 * - Diese Activity ist reine UI: anzeigen, Dismiss/Snooze auslösen, sich selbst schließen.
 *
 * Die Activity wird ausschließlich über den Full-Screen-Intent der Service-Notification
 * gestartet. Das ist auf Android 10+ der einzige erlaubte Weg, aus dem Hintergrund eine
 * Activity zu zeigen — ein direktes startActivity() aus dem AlarmReceiver wird verworfen.
 *
 * ERWARTUNGSMANAGEMENT: Ist das Gerät entsperrt und in Benutzung, zeigt Android bewusst nur eine
 * Heads-up-Notification statt des Vollbilds - ein Test mit entsperrtem Handy beweist nichts.
 * Hergang: Skill cfalarm-wecker-und-boot, reference/wecker-boot-und-wartung.md.
 */
class AlarmFullScreenActivity : AppCompatActivity() {

    companion object {
        private const val WAKE_LOCK_TAG = "CFAlarm:FullScreenActivity"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Wurde der Weckbildschirm in DIESEM Weckvorgang schon einmal verdraengt?
     *
     * Instanzlokal und bewusst nicht persistiert: die Frage lautet "lief dieser eine Wecker
     * sauber durch?". Nur wenn er das tat, faellt der Zaehler in
     * [WeckbildschirmVerdraengungPrefs] auf 0 zurueck - sonst wuerde das Beenden nach einer
     * Verdraengung den Hinweis jedes Mal wieder loeschen.
     */
    private var wurdeVerdraengt = false

    /**
     * Schichtname und Schichtbeginn als Compose-State, NICHT als lokale `val` in onCreate.
     *
     * Das ist der Unterschied zwischen "der Weck-Bildschirm zeigt den Alarm, der gerade klingelt"
     * und "er zeigt den, der als Erstes geklingelt hat": bei `launchMode="singleTask"` kommt eine
     * zweite Zustellung als onNewIntent an derselben Instanz an, und ohne State gibt es nichts,
     * was rekomponieren koennte. Wer das hier wieder zu einem `val` in onCreate macht, baut den
     * Fehler zurueck - siehe [uebernimmAlarmAusIntent].
     */
    private var shiftName by mutableStateOf("")
    private var shiftStartTime by mutableStateOf("")

    /**
     * Die Schlummer-Dauer dieses Weckers - Beschriftung UND Wirkung lesen sie aus DIESEM Feld.
     * Zwei Quellen fuer denselben Wert sind hier keine Redundanz, sondern eine Luege mit
     * Verzoegerung.
     */
    private var snoozeMinutes by mutableIntStateOf(AlarmManagerService.SNOOZE_MINUTES.toInt())

    /**
     * Einweg-Sperre gegen Doppelauslösung von Dismiss/Snooze — siehe [OneShotAlarmHandoff].
     * Dient zusätzlich dem alarmActive-Observer als "wurde hier schon bewusst gehandelt?".
     * `var`: die Sperre gehoert dem WECKER, nicht der Instanz (siehe [uebernimmAlarmAusIntent]).
     */
    private var alarmHandoff = OneShotAlarmHandoff()

    /**
     * Kennung des Weckvorgangs, den diese Instanz gerade bedient ([Weckvorgang.ID_UNBEKANNT],
     * solange keine gelesen werden konnte) — trennt "anderer Wecker" von "derselbe erneut
     * zugestellt", siehe [onNewIntent].
     */
    private var aktuelleAlarmId = Weckvorgang.ID_UNBEKANNT

    /**
     * Grund, warum das Schlummern KEINEN neuen Weckruf gestellt hat — `null` im Normalfall. Ist
     * er gesetzt, bleibt der Wecker laut und der Bildschirm offen, und statt des Schlummer-Knopfes
     * steht hier der Grund.
     */
    private var schlummerHinweis by mutableStateOf<SchlummerMeldung?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity starting (Compose v3.0)")

        // Fenster-Flags VOR setContent: showWhenLocked/turnScreenOn müssen greifen, bevor
        // das Fenster sichtbar wird.
        setupLockScreenFlags()

        // Der Wake-Lock wird NICHT hier erworben, sondern in onStart - siehe dort.
        setupBackButtonHandling()

        uebernimmAlarmAusIntent()

        setContent {
            CFAlarmForTimeOfficeTheme {
                AlarmScreen(
                    shiftName = shiftName,
                    shiftStartTime = shiftStartTime,
                    snoozeMinutes = snoozeMinutes,
                    schlummerHinweis = schlummerHinweis,
                    onDismiss = ::weckerBeenden,
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
     * Bei launchMode="singleTask" liefert eine weitere Zustellung onNewIntent an derselben Instanz;
     * ohne setIntent() laese snoozeAlarm() Schicht/ID/Snooze-Dauer aus dem VORHERIGEN Alarm.
     *
     * Weckerbezogener Zustand liegt gebuendelt in [uebernimmAlarmAusIntent] (neue Felder DORT), in
     * zwei Arten: AUS DEM INTENT ABGELEITET (Schichtname, Schichtbeginn, Schlummer-Dauer) wird bei
     * JEDER Zustellung neu gelesen; HIER ERARBEITET ([schlummerHinweis], [alarmHandoff]) wird NUR
     * bei einem ANDEREN Weckvorgang verworfen. Denn der [AlarmSoundService] haengt denselben
     * PendingIntent auch als `setContentIntent()` an - ein Tipp darauf ist DERSELBE Wecker. Fehlt
     * die Kennung, gilt derselbe Vorgang, siehe [Weckvorgang.istAnderer].
     * Wake-Lock und Fenster-Flags gehoeren dem Fenster, nicht dem Wecker.
     * Hergang: Skill cfalarm-wecker-und-boot, reference/wecker-boot-und-wartung.md ("Schlummern (Runde 8)").
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // setIntent() allein reicht NICHT: hingen Schicht und Schichtbeginn an lokalen `val`s aus
        // onCreate, zeigte das Vollbild bei einer Wiederzustellung weiter den VORHERIGEN Alarm.
        uebernimmAlarmAusIntent()

        // Derselbe Grund fuer den Wake-Lock: er laeuft nach WAKE_LOCK_TIMEOUT aus. Eine
        // Wiederzustellung an eine noch RESUMED laufende Instanz (Snooze-Refire, waehrend das
        // Vollbild sichtbar ist) durchlaeuft onStart NICHT - ohne diesen Aufruf bliebe es beim
        // alten, ggf. schon abgelaufenen Lock. acquireWakeLock() gibt einen vorhandenen selbst
        // zuerst frei, ein doppelter Erwerb ist damit ausgeschlossen.
        acquireWakeLock()

        Logger.i(LogTags.ALARM, "🔎 FSI-DIAG onNewIntent (singleTask-Wiederzustellung): ${visibilitySnapshot()}")
    }

    /**
     * Setzt den gesamten ALARM-BEZOGENEN Instanzzustand aus dem AKTUELLEN `intent` - aus onCreate
     * UND onNewIntent, der einzige Ort dafuer. Die Fallunterscheidung steht im KDoc von [onNewIntent].
     */
    private fun uebernimmAlarmAusIntent() {
        // ZUERST die Kennung, denn sie entscheidet ueber den zweiten Teil dieser Funktion.
        val neueAlarmId = intent.getIntExtra(
            AlarmSoundService.EXTRA_ALARM_ID,
            Weckvorgang.ID_UNBEKANNT
        )
        val andererWeckvorgang = Weckvorgang.istAnderer(aktuelleAlarmId, neueAlarmId)
        // Eine unlesbare Kennung ueberschreibt die zuletzt bekannte NICHT: sonst gilt die naechste
        // Zustellung mit Kennung wieder als "unvergleichbar", und wir verlieren die Unterscheidung
        // fuer immer.
        if (neueAlarmId != Weckvorgang.ID_UNBEKANNT) {
            aktuelleAlarmId = neueAlarmId
        }

        shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME)
            ?: getString(R.string.alarm_unknown_shift)
        shiftStartTime = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME).orEmpty()
        // Fallback-Default matters: aeltere/Direct-Boot-Pfade koennten das Extra nicht mitfuehren.
        // Er steht hier nur EINMAL, damit Knopfbeschriftung und geplanter Schlummer auch im
        // Fallback denselben Wert zeigen.
        snoozeMinutes = intent.getIntExtra(
            AlarmSoundService.EXTRA_SNOOZE_MINUTES,
            AlarmManagerService.SNOOZE_MINUTES.toInt()
        )

        if (andererWeckvorgang) {
            // Der Fehlertext des VORHERIGEN Weckers darf nicht ueber dem neuen stehen bleiben: er
            // meldete sonst einen gescheiterten Schlummer fuer einen Wecker, bei dem noch
            // niemand geschlummert hat - und blendet dabei den Schlummer-Knopf aus, weil dessen
            // Anzeige an genau diesem Feld haengt.
            schlummerHinweis = null

            // Eine NEUE Einweg-Sperre fuer einen NEUEN Wecker. Die alte kann bereits beansprucht
            // sein (gescheiterter Schlummerversuch am vorherigen Wecker, siehe [snoozeAlarm]); mit
            // ihr wuerden Schlummern und Stoppen fuer den frisch zugestellten Wecker wirkungslos
            // abprallen. Ihr Zweck - "der erste bewusste Griff gewinnt" - bezieht sich immer auf
            // EINEN Weckvorgang; ein anderer Wecker ist ein anderer Vorgang.
            alarmHandoff = OneShotAlarmHandoff()

            Logger.i(
                LogTags.ALARM,
                "🔁 Anderer Weckvorgang zugestellt (id=$neueAlarmId) - Schlummer-Hinweis und " +
                    "Einweg-Sperre zurueckgesetzt"
            )
        } else if (schlummerHinweis != null) {
            // Ein Zustand, der das Schlummern anhaelt, muss sichtbar sein - auf dem Bildschirm
            // steht er ohnehin, hier kommt er ins Release-Log (WARN), damit ein spaeterer
            // "warum ging der Schlummer-Knopf nicht?"-Bericht beantwortbar bleibt.
            Logger.w(
                LogTags.ALARM,
                "⚠️ Wiederzustellung desselben Weckvorgangs (id=$neueAlarmId, bekannt=" +
                    "$aktuelleAlarmId) - gescheiterter Schlummer bleibt stehen, Sperre bleibt " +
                    "beansprucht"
            )
        }
    }

    /**
     * Der Wake-Lock wird HIER erworben, nicht in onCreate - das ist die Gegenseite zum Release in
     * [onStop].
     *
     * WARUM: onStop gibt ihn frei (Bildschirm aus per Power-Taste, Anruf, fremdes Fenster), aber
     * erworben wurde er bis v1.29.0 ausschliesslich in onCreate und onNewIntent. Nach einem
     * Bildschirm-aus/-an lief das Vollbild also ohne jeden Wake-Lock weiter - genau der Zustand,
     * gegen den [acquireWakeLock] ueberhaupt existiert (FLAG_KEEP_SCREEN_ON blieb zwar, reicht auf
     * echten Geraeten aber nachweislich nicht, siehe dort).
     *
     * onCreate -> onStart folgt immer, der Ersterwerb geht dadurch nicht verloren. Gated auf einen
     * tatsaechlich laufenden Wecker: ohne ihn schliesst sich diese Activity ohnehin sofort
     * ([observeAlarmState]), und ein bildschirmhaltender Lock waere dann falsch. Dass der Zustand
     * beim Start ueber den Full-Screen-Intent bereits `true` ist, sichert AlarmSoundService zu
     * (alarmActive VOR startForeground).
     */
    override fun onStart() {
        super.onStart()
        if (AlarmSoundService.alarmActive.value) {
            acquireWakeLock()
        }
        Logger.d(LogTags.ALARM, "▶️ AlarmFullScreenActivity STARTED: ${visibilitySnapshot()}")
    }

    override fun onStop() {
        super.onStop()

        // Wecker hier NICHT stoppen: onStop feuert auch bei Bildschirm-Aus (Power-Taste im
        // Halbschlaf), eingehendem Anruf, App-Wechsel oder Rotation. Der Ton laeuft im
        // Foreground-Service weiter und wird ausschliesslich durch bewusstes Dismiss/Snooze beendet.

        // DIAGNOSE: Verschwindet das Vollbild, waehrend der Wecker weiterklingelt, trennt der
        // Snapshot die Ursachen, die sich sonst NICHT unterscheiden lassen: interactive=false
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

        // Merker fuer den Hinweis in der Status-Karte (WeckbildschirmVerdraengungPrefs).
        //
        // NUR der Fall "fremdes Fenster liegt darueber" zaehlt, nicht "Bildschirm ist ausgegangen" -
        // die Unterscheidung ist dieselbe, die der Kommentar oben beschreibt: `isInteractive` trennt
        // sie, und nur sie. Ohne diese Bedingung wuerde ein Druck auf die Power-Taste waehrend des
        // Klingelns als Verdraengung gezaehlt, und die App wuerde dem Nutzer irgendwann raten,
        // seine Gesichtsentsperrung zu entfernen, weil er den Wecker weggedrueckt hat.
        val bildschirmNochAn = try {
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "isInteractive nicht lesbar - zaehlt NICHT als Verdraengung", e)
            false
        }
        // isChangingConfigurations SCHLIESST AUS: Rotation und Dunkelmodus-Wechsel stoppen die
        // Activity bei wachem Bildschirm, und ein Hinweis, den eine Bildschirmdrehung ausloest,
        // waere schlicht falsch.
        if (stoppedWhileRinging && bildschirmNochAn && !isChangingConfigurations) {
            // HOECHSTENS EINMAL pro Weckvorgang, und das ist keine Feinheit: am 29.08.2026 gemessen
            // wurde derselbe Wecker ZWEIMAL verdraengt (14:52:01 und 14:52:11) - die Activity kommt
            // zwischendurch zurueck und wird erneut weggedraengt. Ohne diese Sperre zaehlt der
            // Merker Ereignisse statt Weckvorgaenge, und die Schwelle von zwei Weckern in Folge
            // waere schon nach einem einzigen erreicht.
            if (!wurdeVerdraengt) {
                wurdeVerdraengt = true
                WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(this)
            }
        } else if (isFinishing && !wurdeVerdraengt) {
            // Dieser Weckvorgang lief sauber durch - der Hinweis darf wieder verschwinden.
            WeckbildschirmVerdraengungPrefs.meldeSauberenLauf(this)
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

    /**
     * Erwirbt den bildschirmhaltenden Wake-Lock - und gibt einen vorhandenen zuvor frei.
     *
     * Das Freigeben gehoert HIER hinein, nicht zu den Aufrufern: `newWakeLock()` legt jedes Mal
     * ein NEUES Objekt an, ein blosses Nach-Erwerben wuerde das alte verlieren, ohne es je zu
     * releasen. Weil die Freigabe in dieser Funktion steckt, ist jeder Aufruf gefahrlos
     * wiederholbar und ein doppelter Erwerb unmoeglich.
     */
    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
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
     * Snoozed den Wecker: plant ueber den gemeinsamen [AlarmManagerService.scheduleSnooze] einen
     * neuen Alarm und stoppt den Ton ERST, wenn dieser Wecker wirklich steht - denselben Weg nutzt
     * der Snooze-Button der Benachrichtigung. Die Planungslogik (snoozeAlarmAction, requestCode,
     * setAlarmClock) liegt bewusst nur dort, damit es EINE Wahrheit bleibt.
     *
     * REIHENFOLGE: erst planen, dann stoppen - wie der Schwesterpfad im [AlarmSoundService]. Die
     * MediaPlayer-Race ist damit nicht zurueck: [stopAlarmSoundService] ist ein Intent an den
     * Dienst, kein direkter Zugriff auf den Player, und [AlarmManagerService.scheduleSnooze] ist
     * synchron und kurz.
     *
     * ERGEBNIS AUSWERTEN: Steht kein neuer Weckruf, darf sich dieser Bildschirm NICHT so
     * schliessen, als sei alles gut. Dann bleibt der Wecker laut, der Bildschirm offen und traegt
     * den Grund - siehe [schlummerHinweis].
     */
    private fun snoozeAlarm() {
        if (!alarmHandoff.claim()) {
            Logger.w(LogTags.ALARM, "🚫 Snooze ignoriert — Wecker wurde in dieser Activity schon behandelt")
            return
        }
        // Bewusst KEIN erneuter Intent-Read: der Wert steht in [snoozeMinutes], und genau der
        // steht auch auf dem Knopf, den der Nutzer gerade gedrueckt hat.
        Logger.i(LogTags.ALARM, "😴 User snoozed alarm for $snoozeMinutes minutes")

        val ergebnis = try {
            val shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME) ?: "Snooze"
            val alarmId = intent.getIntExtra(AlarmSoundService.EXTRA_ALARM_ID, -1)
            val shiftStartTime = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME).orEmpty()

            AlarmManagerService.scheduleSnooze(
                this, alarmId, shiftName, shiftStartTime,
                minutes = snoozeMinutes.toLong()
            )
        } catch (e: Exception) {
            // scheduleSnooze schluckt seine eigenen Fehler; hier landet nur, was DAVOR schiefgeht
            // (Intent-Read). Der Zweig bleibt trotzdem: er darf nie wieder still zu einem
            // "sieht aus wie Erfolg" werden.
            Logger.e(LogTags.ALARM, "❌ Failed to snooze alarm", e)
            SnoozeErgebnis.FEHLGESCHLAGEN
        }

        if (ergebnis == SnoozeErgebnis.GEPLANT) {
            stopAlarmSoundService()
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)
            finish()
            return
        }

        // Kein verlaesslich gestellter Wecker: Ton bleibt an, Bildschirm bleibt offen, Grund wird
        // angezeigt. Das gilt auch fuer SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR - dort steht
        // moeglicherweise doch noch ein Weckruf, aber "moeglicherweise" ist kein Wecker, auf den
        // sich jemand legen darf. Der Hinweistext sagt genau das.
        // Zusaetzlich die Benachrichtigung, damit die Meldung auch dann noch da ist, wenn der
        // Nutzer den Wecker gleich beendet.
        Logger.w(
            LogTags.ALARM,
            "⚠️ Schlummern nicht ausgefuehrt ($ergebnis) - Vollbild bleibt offen, Wecker laeuft weiter"
        )
        AlarmSoundService.posteSchlummerHinweis(this, ergebnis)
        // Titel UND Text als ein Wert: die Ueberschrift gehoert zum Ergebnis.
        schlummerHinweis = SchlummerEntscheidung.hinweis(ergebnis)
    }

    /**
     * Der einzige Knopf, der im Fehlerzustand noch etwas tut.
     *
     * Die Einweg-Sperre [alarmHandoff] ist nach einem gescheiterten Schlummer bereits beansprucht -
     * [dismissAlarm] wuerde also wirkungslos abprallen und den Nutzer auf einem Bildschirm mit
     * lautem Wecker und zwei toten Knoepfen zuruecklassen. Deshalb geht der Fehlerzustand direkt
     * auf [stopAndClose], das die Sperre bewusst NICHT fragt (siehe dessen KDoc). Die Sperre bleibt
     * damit unangetastet: sie schuetzt weiterhin gegen die gleichzeitige Doppelauslösung, sperrt
     * aber niemanden aus.
     */
    private fun weckerBeenden() {
        if (schlummerHinweis != null) stopAndClose() else dismissAlarm()
    }
}

/**
 * Der Wecker-Screen im Corporate Design.
 *
 * Nutzt bewusst die Theme-Rollen statt hartkodierter Farben.
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
    snoozeMinutes: Int,
    schlummerHinweis: SchlummerMeldung?,
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

                // Der Grund steht dort, wo der Nutzer gerade hinsieht - nicht nur in einer
                // Benachrichtigung, die er im Halbschlaf nicht aufzieht. Ohne diese Zeile war ein
                // gescheitertes Schlummern von einem erfolgreichen nicht zu unterscheiden.
                if (schlummerHinweis != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = schlummerHinweis.titel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = schlummerHinweis.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
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

                // Der Schlummer-Knopf verschwindet, sobald ein Schlummer-Versuch KEINEN Weckruf
                // gestellt hat: ein zweiter Druck liefe in die bereits beanspruchte Einweg-Sperre
                // und taete sichtbar nichts - ein Knopf, der nichts tut, ist an diesem Bildschirm
                // schlimmer als kein Knopf. Uebrig bleibt "Alarm stoppen", und der wirkt (er geht
                // im Fehlerzustand ueber stopAndClose an der Sperre vorbei).
                if (schlummerHinweis == null) {
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            // Die Zahl kommt aus derselben Variablen, die snoozeAlarm() in
                            // scheduleSnooze() reicht - der Knopf kann nicht mehr etwas anderes
                            // behaupten, als er tut.
                            text = stringResource(R.string.alarm_snooze_button, snoozeMinutes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
