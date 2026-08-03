package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baut/aktualisiert/verwirft die Dimmer-Korrektur-Notification (Feature C): Heller/Dunkler/
 * Pause↔Fortsetzen fuer das gerade aktive Dimm-Fenster. [DimScheduleUseCase.applyCurrentState]
 * ist der EINZIGE Aufrufer, der [show]/[cancel] entscheidet - dieser Notifier selbst kennt keine
 * Fenster-Logik, er rendert nur den ihm übergebenen Zustand.
 *
 * Channel [CHANNEL_ID] bewusst [NotificationManager.IMPORTANCE_LOW] - anders als der
 * Wecker-Channel (siehe `AlarmSoundService`) keine Dringlichkeit, nur ein Korrektur-Werkzeug fuer
 * den Fall, dass die automatische Verdunkelung gerade zu stark/schwach ist.
 *
 * `setOngoing(true)`: die Notification soll nicht versehentlich wegwischbar sein, solange sie
 * noch wirkt - sonst verliert der Nutzer den Korrektur-Zugriff, obwohl der Dimmer weiterhin läuft.
 * [cancel] raeumt sie exakt dann weg, wenn kein Dimm-Fenster mehr aktiv ist ODER der Nutzer-Toggle
 * ausgeschaltet ist (noch ohne Settings-Screen-Anbindung, siehe [DimOverlayPrefs]).
 */
@Singleton
class DimCorrectionNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "dim_correction"
        private const val NOTIFICATION_ID = 2101
    }

    fun show(strength: Int, warmth: Int, paused: Boolean) {
        createNotificationChannelIfNeeded()

        val brighterIntent = actionIntent(DimNotificationService.ACTION_BRIGHTER, 0)
        val darkerIntent = actionIntent(DimNotificationService.ACTION_DARKER, 1)
        val pauseResumeIntent = if (paused) {
            actionIntent(DimNotificationService.ACTION_RESUME, 2)
        } else {
            actionIntent(DimNotificationService.ACTION_PAUSE, 2)
        }

        val statusText = if (paused) {
            "Dimmer pausiert"
        } else {
            "Verdunkelung: $strength %"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Schicht-Dimmer")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.arrow_up_float, "Heller", brighterIntent)
            .addAction(android.R.drawable.arrow_down_float, "Dunkler", darkerIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "Fortsetzen" else "Pause",
                pauseResumeIntent
            )
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, DimNotificationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Idempotent, sicher bei jedem [show]-Aufruf erneut aufzurufen. */
    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dimmer-Korrektur",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Heller/Dunkler/Pause fuer den aktiven Schicht-Dimmer"
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Logger.d(LogTags.DIMMER, "Dimmer-Korrektur-Channel angelegt/aktualisiert: $CHANNEL_ID")
    }
}
