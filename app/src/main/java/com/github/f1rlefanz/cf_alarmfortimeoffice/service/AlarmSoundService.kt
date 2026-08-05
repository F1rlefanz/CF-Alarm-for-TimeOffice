package com.github.f1rlefanz.cf_alarmfortimeoffice.service

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
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
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
 * - START_STICKY: Auto-restart if killed by system (Android behavior)
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

        // Notification Configuration
        // EINZIGE Alarm-Notification der App. Der AlarmReceiver postete frueher eine zweite
        // (ID 2001) mit eigenem Channel-Sound - das ergab zwei Klingeltoene und zwei Eintraege
        // in der Leiste. Ton + Sichtbarkeit haengen jetzt an genau diesem Service.
        const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "alarm_sound_service"

        /**
         * Laeuft gerade ein Wecker? Die [AlarmFullScreenActivity] beobachtet das und schliesst
         * sich selbst, sobald der Ton ueber den Notification-Button beendet wurde - sonst muesste
         * der Nutzer den Wecker zweimal stoppen (einmal in der Leiste, einmal im Vollbild).
         */
        private val _alarmActive = MutableStateFlow(false)
        val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()
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
                startForeground(NOTIFICATION_ID, notification)
                Logger.d(LogTags.ALARM, "✅ Foreground service started with alarm notification")

                // Start alarm sound and vibration
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
                AlarmManagerService.scheduleSnooze(
                    applicationContext, alarmId, shiftName, shiftStartTime,
                    minutes = snoozeMinutes.toLong()
                )
                stopAlarmAndService()
            }

            ACTION_STOP_ALARM -> {
                Logger.i(LogTags.ALARM, "🛑 AlarmSoundService STOP_ALARM")
                stopAlarmAndService()
            }

            else -> {
                Logger.w(LogTags.ALARM, "⚠️ AlarmSoundService received unknown action: ${intent?.action}")
            }
        }
        
        // START_STICKY: System will restart service if killed (with null intent)
        return START_STICKY
    }
    
    /**
     * Beendet den Wecker vollstaendig: Ton, Vibration, Audio-Fokus, Foreground-Notification und den
     * Service selbst. Gemeinsamer Endpunkt von STOP und SNOOZE (der Snooze plant vorher zusaetzlich
     * den naechsten Wecker).
     *
     * `_alarmActive = false` signalisiert der [AlarmFullScreenActivity], sich zu schliessen, falls sie
     * laeuft - sonst muesste der Nutzer zweimal stoppen (Leiste UND Vollbild).
     */
    private fun stopAlarmAndService() {
        _alarmActive.value = false
        stopAlarmSound()
        stopVibration()
        abandonAudioFocus() // laesst pausierte Medien (Podcast/Musik) wieder anlaufen
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Logger.d(LogTags.ALARM, "✅ AlarmSoundService stopped and cleaned up")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(LogTags.ALARM, "🛑 AlarmSoundService destroying - guaranteed cleanup")

        // Guaranteed cleanup on service destruction
        _alarmActive.value = false
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

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            if (alarmUri == null) {
                Logger.w(LogTags.ALARM, "⚠️ No alarm URIs available for MediaPlayer")
                return
            }

            Logger.d(LogTags.ALARM, "🎵 Starting MediaPlayer with URI: $alarmUri")

            alarmMediaPlayer = MediaPlayer().apply {
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

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start alarm sound in service", e)
        }
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
     * Creates notification channel for foreground service
     * This is idempotent - safe to call multiple times
     */
    private fun createNotificationChannel() {
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

        val notificationManager = getSystemService(NotificationManager::class.java)
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
                getString(R.string.alarm_notification_snooze),
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
