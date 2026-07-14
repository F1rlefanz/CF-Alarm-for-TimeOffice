package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        private const val SNOOZE_MINUTES = 5L
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Merkt sich, ob Dismiss/Snooze bereits den Service gestoppt haben. Verhindert, dass der
     * alarmActive-Observer danach noch ein zweites finish() auslöst.
     */
    private var userHandledAlarm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity starting (Compose v3.0)")

        // Fenster-Flags VOR setContent: showWhenLocked/turnScreenOn müssen greifen, bevor
        // das Fenster sichtbar wird.
        setupLockScreenFlags()

        acquireWakeLock()
        setupBackButtonHandling()

        val shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME)
            ?: getString(R.string.alarm_unknown_shift)
        val alarmTime = intent.getStringExtra(AlarmSoundService.EXTRA_ALARM_TIME).orEmpty()

        setContent {
            CFAlarmForTimeOfficeTheme {
                AlarmScreen(
                    shiftName = shiftName,
                    alarmTime = alarmTime,
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

        Logger.i(LogTags.ALARM, "✅ AlarmFullScreenActivity initialized: $shiftName at $alarmTime")
    }

    override fun onStop() {
        super.onStop()
        Logger.d(LogTags.ALARM, "⏹️ AlarmFullScreenActivity STOPPED")

        // Wecker hier NICHT stoppen: onStop feuert auch bei Bildschirm-Aus (Power-Taste im
        // Halbschlaf), eingehendem Anruf, App-Wechsel oder Rotation. Der Ton laeuft im
        // Foreground-Service weiter und wird ausschliesslich durch bewusstes Dismiss/Snooze beendet.

        releaseWakeLock()
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
     * Schließt das Vollbild, sobald der Service den Wecker beendet meldet.
     *
     * drop(1) überspringt den aktuellen Replay-Wert des StateFlow: der ist beim Öffnen
     * kurzzeitig noch false, wenn die Activity dem START_ALARM zuvorkommt — ohne drop
     * würde sich der Screen sofort wieder schließen.
     */
    private fun observeAlarmState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmSoundService.alarmActive
                    .drop(1)
                    .filter { active -> !active }
                    .collect {
                        if (!userHandledAlarm) {
                            Logger.i(LogTags.ALARM, "🔕 Wecker extern gestoppt — Vollbild schließt sich")
                            finish()
                        }
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
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT)
            }
            Logger.business(LogTags.ALARM, "✅ Wake lock acquired for full-screen activity")
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
        Logger.i(LogTags.ALARM, "🛑 User dismissed alarm")
        userHandledAlarm = true

        stopAlarmSoundService()

        // Gezielt die Alarm-Notification abräumen statt cancelAll(): cancelAll() löschte auch
        // fremde App-Notifications wie die Skip-Bestätigung mit weg.
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)

        finish()
    }

    /**
     * Snoozed den Wecker: stoppt den Ton und legt einen neuen Alarm in [SNOOZE_MINUTES].
     */
    private fun snoozeAlarm() {
        Logger.i(LogTags.ALARM, "😴 User snoozed alarm for $SNOOZE_MINUTES minutes")

        try {
            // CRITICAL: Stop sound service BEFORE scheduling snooze
            // This prevents race conditions with MediaPlayer
            userHandledAlarm = true
            stopAlarmSoundService()

            val snoozeTimeMillis = System.currentTimeMillis() + (SNOOZE_MINUTES * 60 * 1000L)

            val shiftName = intent.getStringExtra(AlarmSoundService.EXTRA_SHIFT_NAME) ?: "Snooze"
            val alarmId = intent.getIntExtra(AlarmSoundService.EXTRA_ALARM_ID, -1)

            val alarmIntent = Intent(this, AlarmReceiver::class.java).apply {
                // Schlüssel MÜSSEN zu AlarmReceiver.EXTRA_* passen. Vorher schrieb der Snooze
                // "alarm_time", während der Receiver "shift_time" las, und die alarm_id fehlte
                // ganz — der Snooze-Alarm lief dadurch mit ID -1 und ohne Uhrzeit.
                putExtra(AlarmReceiver.EXTRA_SHIFT_NAME, shiftName)
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                putExtra(
                    AlarmReceiver.EXTRA_ALARM_TIME,
                    LocalDateTime.now().plusMinutes(SNOOZE_MINUTES)
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                )
                setPackage(packageName)
                action = AlarmManagerService.enhancedAlarmAction(alarmId)
            }

            // requestCode = alarmId (statt einer Zufallszahl aus currentTimeMillis): so ersetzt
            // ein zweiter Snooze denselben PendingIntent, statt einen weiteren Wecker zu stapeln.
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                alarmId,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val alarmClockInfo = AlarmManager.AlarmClockInfo(snoozeTimeMillis, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

            Logger.business(LogTags.ALARM, "✅ Snooze alarm scheduled for $SNOOZE_MINUTES minutes")

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)
            finish()

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to snooze alarm", e)
            dismissAlarm()
        }
    }
}

/**
 * Der Wecker-Screen im Corporate Design.
 *
 * Nutzt bewusst die Theme-Rollen (primary/onPrimary/…) statt hartkodierter Farben — der
 * Screen hing vorher als einziger Screen der App noch auf Material-Default-Blau (#1976D2).
 */
@Composable
private fun AlarmScreen(
    shiftName: String,
    alarmTime: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.primary
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
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(72.dp)
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.alarm_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = shiftName,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center
                )

                if (alarmTime.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.alarm_shift_start, alarmTime),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
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
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary
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
                        contentColor = MaterialTheme.colorScheme.onPrimary
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
