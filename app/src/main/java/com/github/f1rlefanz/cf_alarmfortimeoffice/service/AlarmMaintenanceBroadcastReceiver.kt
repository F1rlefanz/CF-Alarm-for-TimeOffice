package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors

/**
 * AlarmMaintenanceBroadcastReceiver
 *
 * Receives Exact Alarm triggers and starts AlarmMaintenanceService
 *
 * ARCHITECTURE:
 * - Triggered by AlarmManager every 6 hours
 * - Starts AlarmMaintenanceService as foreground service
 * - Minimal logic - just a bridge between AlarmManager and Service
 */
class AlarmMaintenanceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // forceSync WEITERREICHEN, nicht verschlucken: der Nachhol-Alarm aus
        // AlarmMaintenanceService.start() (wenn ein Vordergrund-Start aus dem Hintergrund abgelehnt
        // wurde) traegt das Flag im Intent. Ohne dieses Durchreichen liefe der nachgeholte Lauf
        // OHNE Erzwingen zurueck, ohne den Kalender anzufassen - genau der wirkungslose Lauf, den
        // TimezoneChangeReceiver vermeiden will. Die regulaere 6h-Kette setzt kein Extra und
        // bekommt weiterhin false.
        val forceSync = intent.getBooleanExtra(AlarmMaintenanceService.EXTRA_FORCE_SYNC, false)
        val nachholVersuche = intent.getIntExtra(AlarmMaintenanceService.EXTRA_NACHHOLVERSUCHE, 0)
        val istWachhund = intent.getBooleanExtra(AlarmMaintenanceService.EXTRA_WACHHUND, false)

        Logger.business(
            LogTags.MAINTENANCE,
            "⏰ Exact Alarm triggered - starting maintenance (forceSync=$forceSync, " +
                "wachhund=$istWachhund, nachholversuche=$nachholVersuche)"
        )

        // DER WACHHUND ZIEHT DIE KETTE ZUERST WIEDER AUF - und zwar bevor irgendein Dienststart
        // versucht wird.
        //
        // WELCHER ABLAUF SONST KAPUTT BLEIBT: Der Wachhund existiert fuer genau einen Zustand -
        // der Nutzer hat auf API 31/32 "Alarme & Erinnerungen" entzogen, Android hat dabei den
        // EINEN Alarm der 6h-Kette mitgeloescht. In diesem Zustand darf die App im Hintergrund
        // aber gar keinen Vordergrunddienst starten: die Ausnahme gilt nur fuer das Feuern eines
        // EXAKTEN Alarms, und der Wachhund ist notgedrungen inexakt gestellt (sonst waere er
        // mitgeloescht worden). Sein startForegroundService() wird also abgewiesen - und damit
        // lief frueher auch scheduleNext() nie, das sonst erst im `finally` des Dienstlaufs
        // kommt. Der Wachhund-Slot war einmal verbraucht, die Kette blieb tot: genau der
        // Zustand, gegen den er gebaut wurde.
        //
        // scheduleNext() ist reine AlarmManager-Arbeit und aus dem Hintergrund immer erlaubt. Es
        // ist AUCH KEIN zweiter Planer (CLAUDE.md): es ist derselbe und einzige Planer, auf
        // denselben zwei Slots, die er ueberschreibt.
        if (istWachhund) {
            try {
                // Gegenprobe gegen die Master-Pause, BEVOR neu aufgezogen wird: `pause()` kappt
                // beide Slots, der Wachhund duerfte hier also gar nicht ankommen. Feuert er
                // trotzdem (er war im selben Moment schon unterwegs), waere ein Neuaufziehen
                // genau das, was cancelNext() verhindern soll - "die pausierte Wartung kaeme 30
                // Minuten spaeter von selbst zurueck". Gelesen wird der Device-Protected-Spiegel,
                // weil er synchron und ohne CE-Storage auskommt (gleiche Begruendung wie im
                // Wartungs-Anker des BootReceivers); Default bei Lesefehler ist "nicht pausiert".
                val pausenSpiegel = EntryPointAccessors
                    .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                    .directBootAlarmStore()
                if (pausenSpiegel.isPausedNow()) {
                    Logger.business(
                        LogTags.MAINTENANCE,
                        "⏸️ WARTUNG: Wachhund gefeuert, aber Master-Pause aktiv - Kette bleibt gekappt"
                    )
                    return
                }
                AlarmMaintenanceService.scheduleNext(context)
                Logger.w(
                    LogTags.MAINTENANCE,
                    "⚠️ WARTUNG: Wiederanlauf-Wachhund hat gefeuert - der regulaere Alarm der " +
                        "Kette war weg (Berechtigungsentzug?). Kette neu aufgezogen."
                )
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE, "❌ WARTUNG: Kette liess sich nicht neu aufziehen", e)
            }
        }

        try {
            AlarmMaintenanceService.start(
                context,
                forceSync = forceSync,
                nachholVersuche = nachholVersuche
            )
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "Failed to start maintenance service", e)
        }
    }
}
