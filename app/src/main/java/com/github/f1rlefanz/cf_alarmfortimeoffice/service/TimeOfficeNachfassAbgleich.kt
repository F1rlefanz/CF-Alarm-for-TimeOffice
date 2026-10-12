package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.ExakteAlarme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Zwei einmalige Kalender-Abgleiche, nachdem der Nutzer ueber den Knopf "TimeOffice öffnen" (Karte
 * "TimeOffice-Zuverlässigkeit" im Status-Tab) TimeOffice geoeffnet hat.
 *
 * ANLASS (Befund 12.10.2026, am Fairphone belegt): TimeOffice traegt einen geaenderten Dienstplan
 * erst in den Google-Kalender ein, wenn man die App OEFFNET - seine Meldung "Dienstplanänderung"
 * allein schreibt nichts. Am Tag des Befunds: Meldung 09:24, TimeOffice geoeffnet 15:50:28,
 * Android laedt 15:51:06 hoch, bei Google angelegt 15:52. CF-Alarm liest den Kalender ueber die
 * Google-API, sieht die Aenderung also erst NACH diesem Hochladen. Wer TimeOffice aus CF-Alarm
 * heraus oeffnet, bekommt deshalb zwei Abgleiche hinterher: nach [ERSTER_ABSTAND_MS] (der
 * gemessene Fall, gut zwei Minuten bis Google, plus Fingerabdruck-Abfrage) und nach
 * [ZWEITER_ABSTAND_MS] (langsames Hochladen, laenger in TimeOffice geblieben). Ein stilles
 * Anstossen von TimeOffice im Hintergrund gibt es nicht: die App hat nach aussen nur ihre
 * Startseite, und Android verbietet das Oeffnen einer fremden App aus dem Hintergrund.
 *
 * WAS DAS HIER TUT: zwei exakte Alarme auf [TimeOfficeNachfassReceiver], und der startet den
 * ganz normalen Wartungslauf mit `forceSync = true`. Kein zweiter Sync-Pfad, keine zweite
 * Wartungsimplementierung - dieselbe Haltung wie `RufbereitschaftAbfrage`,
 * `WartungNetzNachholer` und `TimezoneChangeReceiver`.
 *
 * KEIN ZWEITER PLANER DER 6h-KETTE (Invariante aus CLAUDE.md): beide Alarme sind EINMALIG,
 * planen sich nicht selbst nach und tragen EIGENE Request-Codes ([REQUEST_CODE_ERSTER],
 * [REQUEST_CODE_ZWEITER]) - derselbe Grund wie bei `WartungsKettenPlanung.NACHHOL_REQUEST_CODE`:
 * auf Code 0 ersetzten sie den geplanten naechsten Wartungslauf. Dass jeder dieser Laeufe in
 * seinem `finally` die 6h-Kette auf jetzt + 6 h stellt, ist der gewohnte Gang nach jedem Lauf
 * (wie bei der Rufbereitschafts-Abfrage): eben wurde nachgesehen.
 *
 * Ein ZWEITER Druck auf den Knopf stellt dieselben zwei Slots neu (`FLAG_UPDATE_CURRENT` auf
 * festem Code, `AlarmManager` ersetzt einen Alarm mit gleichem PendingIntent) - die Abgleiche
 * ruecken nach hinten, sie stapeln sich nicht.
 *
 * MASTER-PAUSE: waehrend der Pause wird nichts geplant ([plane] fragt den Device-Protected-
 * Spiegel, synchron wie `BootReceiver` und der Wachhund im `AlarmMaintenanceBroadcastReceiver`).
 * Beginnt die Pause erst NACH dem Druck, feuern die beiden Alarme trotzdem - `pause()` kennt sie
 * nicht. Das ist harmlos: der Wartungslauf prueft die Master-Pause als erste inhaltliche Pruefung
 * und steigt aus, sein `finally` kappt die Kette wie bei jedem Lauf in der Pause.
 *
 * WAS ES NICHT LEISTET: Ohne die Berechtigung fuer exakte Alarme (nur API 31/32 entziehbar) wird
 * ungenau gestellt, und ein ungenauer Alarm traegt beim Feuern keine Freigabe fuer den
 * Vordergrund-Start - `AlarmMaintenanceService.start()` faengt die Ablehnung, holt aber nur
 * exakt nach. Dann entfallen diese beiden Abgleiche; die Karte "Alarme & Erinnerungen" steht in
 * dem Fall ohnehin rot. Der Abgleich beim Zurueckkehren in die App bleibt davon unberuehrt.
 * Kein WorkManager: ein Vordergrund-Start aus einem Worker wird ab Android 12 abgelehnt.
 */
object TimeOfficeNachfassAbgleich {

    /**
     * NICHT 0/8801/8802 (6h-Wartung), 8803 (Rufbereitschafts-Abfrage), 7710 (Dimmer) oder 7712
     * (DND) - eigene Slots, siehe Klassenkommentar.
     */
    const val REQUEST_CODE_ERSTER = 8804
    const val REQUEST_CODE_ZWEITER = 8805

    val ERSTER_ABSTAND_MS: Long = TimeUnit.MINUTES.toMillis(3)
    val ZWEITER_ABSTAND_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /** Ein einmaliger Abgleich: auf welchem Slot er steht und wann er feuert. */
    internal data class Termin(val requestCode: Int, val zeitpunkt: Long)

    /**
     * REIN UND TESTBAR: die beiden Abgleiche nach einem Druck um [jetzt], frueherer zuerst.
     *
     * Fester Slot je Abstand - das macht den wiederholten Druck zu einem Ersetzen statt einem
     * Stapeln (siehe Klassenkommentar).
     */
    internal fun termine(jetzt: Long): List<Termin> = listOf(
        Termin(REQUEST_CODE_ERSTER, jetzt + ERSTER_ABSTAND_MS),
        Termin(REQUEST_CODE_ZWEITER, jetzt + ZWEITER_ABSTAND_MS)
    )

    /**
     * Stellt beide Abgleiche ab jetzt - ausser waehrend der Master-Pause. Wirft nie: der Aufrufer
     * ist ein Knopf-Handler, und ein Fehler hier darf weder ihn noch das Oeffnen von TimeOffice
     * stoeren.
     */
    fun plane(context: Context) {
        try {
            if (pausiert(context)) {
                Logger.business(
                    LogTags.MAINTENANCE,
                    "⏸️ TimeOffice-Nachfass: Master-Pause aktiv - kein Abgleich geplant"
                )
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (termin in termine(System.currentTimeMillis())) {
                val exakt = ExakteAlarme.stelle(
                    alarmManager,
                    termin.zeitpunkt,
                    pendingIntent(context, termin.requestCode)
                )
                if (!exakt) {
                    Logger.w(
                        LogTags.MAINTENANCE,
                        "Keine Berechtigung fuer exakte Alarme - TimeOffice-Nachfass ${Date(termin.zeitpunkt)} " +
                            "laeuft ungenau und darf den Wartungslauf beim Feuern nicht starten"
                    )
                }
                Logger.business(
                    LogTags.MAINTENANCE,
                    "🔁 TimeOffice-Nachfass: Abgleich ${Date(termin.zeitpunkt)} geplant (exakt=$exakt)"
                )
            }
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ TimeOffice-Nachfass konnte nicht geplant werden", e)
        }
    }

    /**
     * Unterbleibt die Planung wegen der Master-Pause? Der Device-Protected-Spiegel - synchron
     * lesbar, also aus einem Knopf-Handler heraus ohne Coroutine. Bei einem Lesefehler "nicht
     * pausiert": ein ueberfluessiger Abgleich ist harmlos (der Wartungslauf hat seinen eigenen
     * Pausen-Backstop).
     *
     * Auch die Karte fragt GENAU DIESE Funktion, bevor sie "Danach gleicht CF-Alarm … ab"
     * verspricht - so koennen Text und Planung nicht auseinanderlaufen.
     */
    internal fun pausiert(context: Context): Boolean = try {
        EntryPointAccessors
            .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
            .directBootAlarmStore()
            .isPausedNow()
    } catch (e: Exception) {
        Logger.w(LogTags.MAINTENANCE, "Pausen-Spiegel nicht lesbar - TimeOffice-Nachfass wird trotzdem geplant", e)
        false
    }

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TimeOfficeNachfassReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
