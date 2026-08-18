package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zeigt eine sichtbare Benachrichtigung, sobald [com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase.syncAlarms]
 * eine neue/geaenderte/geloeschte Schicht erkennt (Feature B) - der Nutzer muss nicht mehr
 * raetseln, ob/wann die App eine TimeOffice-Aenderung mitbekommen hat.
 *
 * Eigener Channel [CHANNEL_ID], bewusst NICHT der bestehende Wartungs-Channel
 * (`AlarmMaintenanceService`) - andere Dringlichkeit (der Nutzer soll das aktiv sehen, kein
 * Hintergrund-Ongoing-Ton) und ein eigener, unabhaengiger Toggle
 * ([ShiftChangeNotificationPrefs]).
 *
 * [notifyCreated]/[notifyUpdated]/[notifyDeleted] sind `open`, damit Tests eine Fake-Unterklasse
 * bilden koennen, die nur die Aufrufzahl zaehlt (siehe `AlarmUseCaseDeltaSyncTest`) - dieselbe
 * Rolle, die dort sonst hand-geschriebene Fakes fuer Interfaces uebernehmen. Wann ueberhaupt
 * aufgerufen wird (z. B. "erster Sync ueberhaupt" fuer [notifyCreated]), entscheidet der Aufrufer
 * (`AlarmUseCase`); WAS innerhalb eines Aufrufs tatsaechlich als Notification erscheint (Toggle-
 * Zustand + Zeit-/Namens-Schwelle), entscheidet ausschliesslich diese Klasse.
 */
@Singleton
open class ShiftChangeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: ShiftChangeNotificationPrefs
) {
    companion object {
        private const val CHANNEL_ID = "shift_change_alerts"
        private const val NOTIFICATION_ID = 2202

        /**
         * Rundungsrauschen faellt raus: erst ab diesem Zeit-Delta ODER einer Namensaenderung gilt
         * eine Aenderung als notification-wuerdig. Reine Funktion, unabhaengig von Context/Prefs
         * testbar - siehe `AlarmUseCaseDeltaSyncTest`.
         */
        const val CHANGE_THRESHOLD_MINUTES = 10L

        fun exceedsThreshold(old: AlarmInfo, new: AlarmInfo): Boolean {
            if (old.shiftName != new.shiftName) return true
            val deltaMinutes = kotlin.math.abs(new.triggerTime - old.triggerTime) / 60_000L
            return deltaMinutes >= CHANGE_THRESHOLD_MINUTES
        }

        private fun timeOnly(millis: Long): String =
            DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
    }

    open suspend fun notifyCreated(new: AlarmInfo) {
        if (!prefs.enabledNow()) return
        show(
            title = "Neue Schicht erkannt",
            text = "${new.shiftName} ab ${timeOnly(new.triggerTime)}, Wecker gestellt"
        )
    }

    open suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) {
        if (!prefs.enabledNow()) return
        if (!exceedsThreshold(old, new)) return
        val text = if (old.shiftName != new.shiftName) {
            "${old.shiftName} → ${new.shiftName}, Wecker angepasst auf ${timeOnly(new.triggerTime)}"
        } else {
            "${new.shiftName}: ${timeOnly(old.triggerTime)} → ${timeOnly(new.triggerTime)}, Wecker angepasst"
        }
        show(title = "Schicht geändert", text = text)
    }

    open suspend fun notifyDeleted(old: AlarmInfo) {
        if (!prefs.enabledNow()) return
        show(
            title = "Schicht entfernt",
            text = "${old.shiftName}, Wecker gelöscht"
        )
    }

    private fun show(title: String, text: String) {
        createNotificationChannelIfNeeded()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()

        // Erst NACH dem Anlegen des Kanals pruefen (vorher gaebe es ihn beim ersten Lauf nicht):
        // schaltet der Nutzer diesen EINZELNEN Kanal ab, liefert areNotificationsEnabled()
        // weiterhin true - die Meldung verschwaende dann lautlos in notify(). Hier geht nur die
        // einzelne Meldung verloren (kein Merker wie beim CalendarUnavailableNotifier), aber im
        // Log war der Fall bisher nicht von "gar nicht erst aufgerufen" zu unterscheiden.
        // Ein WARN pro Anlass, kein Dauerspam: show() laeuft nur bei echten Schichtaenderungen.
        val zustellbarkeit = NotificationDeliverability.bestimme(context, CHANNEL_ID)
        if (!zustellbarkeit.erreicht) {
            Logger.w(
                LogTags.ALARM,
                "⚠️ Schicht-Aenderungs-Notification nicht zustellbar ($zustellbarkeit): \"$title\" erreicht den Nutzer nicht"
            )
            return
        }

        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            Logger.d(LogTags.ALARM, "Schicht-Aenderungs-Notification gezeigt: $title")
        } catch (e: Exception) {
            // Ein Wurf hier darf den laufenden Alarm-Sync nicht mitreissen - die Notification ist
            // Beiwerk, die Wecker sind die Hauptsache.
            Logger.w(LogTags.ALARM, "⚠️ Schicht-Aenderungs-Notification konnte nicht gepostet werden: ${e.message}")
        }
    }

    /** Idempotent, sicher bei jedem [show]-Aufruf erneut aufzurufen. */
    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Schicht-Änderungen",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Benachrichtigung bei erkannten Schicht-Änderungen (neu/geändert/entfernt)"
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
