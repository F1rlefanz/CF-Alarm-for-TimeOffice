package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #142: Meldet, dass ein Krank-Termin im Kalender Tage freigegeben hat, an denen ein Wecker aus
 * dem Dienstplan stand (oder eine Schicht lag).
 *
 * WARUM ES DAS GIBT: Die Freigabe passiert im Hintergrund (6h-Wartung, Vordergrund-Sync), der
 * Wecker verschwindet also ohne Zutun des Nutzers. Ohne Meldung saehe das genauso aus wie ein
 * verlorener Wecker - und TimeOffice ersetzt die Schicht dabei durch "K", was sonst als
 * "Schicht entfernt" gemeldet wuerde (die unterdrueckt `AlarmUseCase` fuer Krank-Tage).
 *
 * Ausloeser ist der Diff des Kalender-Schluessels im Sync (`FreieTageStore.setzeKalenderKrankTage`),
 * ein Tag kommt also nur einmal als "neu" vorbei. Damit er nicht verloren geht, wenn der Lauf
 * danach scheitert oder endet, merkt der Store ihn vor (`vormerkenKrankMeldung`), und erst die
 * zugestellte Meldung quittiert ihn - ein Gedaechtnis fuer "noch zu melden", nicht fuer "schon
 * gemeldet".
 *
 * Konstruktor nimmt NUR den Context und hat keine Property-Initialisierer, die etwas lesen: die
 * Klasse haengt ueber `AlarmUseCase` am Direct-Boot-Graphen (BootReceiver).
 *
 * [zeige] ist `open` (Muster [CalendarUnavailableNotifier]), damit Tests nur mitzaehlen koennen.
 */
@Singleton
open class KrankmeldungNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "krankmeldung_alerts"

        // Eigene Kennung UND eigener Request-Code fuer den PendingIntent: PendingIntents vergleichen
        // sich OHNE Extras, mit einem fremden Code ueberschriebe FLAG_UPDATE_CURRENT dessen Ziel
        // (siehe CalendarUnavailableNotifier.zeige).
        private const val NOTIFICATION_ID = 2204

        /** Bis zu so vielen nicht zusammenhaengenden Tagen nennt der Titel jeden einzeln. */
        private const val MAX_AUFGEZAEHLTE_TAGE = 3

        private val TAG_MONAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")
        private val UHRZEIT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        // #142: eigene Kopie von `ui.components.kurzesDatum` ("Mo 20.10.") - `alarm/` darf nichts
        // aus `ui.` importieren (tools/invarianten/pruefe_code.py, Schichtrichtung): diese Klasse
        // laeuft im Hintergrund und im Direct-Boot-Prozess.
        private fun kurzesDatum(datum: LocalDate): String =
            datum.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix(".") +
                " " + datum.format(TAG_MONAT)

        /**
         * PURE, TESTBAR: Titel und Text der Meldung.
         *
         * @param tage aufsteigend, nicht leer - die NEU krankgemeldeten Tage.
         * @param weckzeiten Uhrzeiten der ENTFALLENDEN Wecker, EIN Eintrag je Wecker (der Text
         *   zaehlt die Liste - drei Fruehdienste um 04:45 sind drei Wecker); leer, wenn an den
         *   Tagen nur eine Schicht lag, aber (noch) kein Wecker im Bestand stand.
         */
        fun meldung(tage: List<LocalDate>, weckzeiten: List<LocalTime>): Meldung {
            val sortiert = tage.sorted()
            val zusammenhaengend = sortiert.zipWithNext().all { (a, b) -> b == a.plusDays(1) }
            val spanne = when {
                sortiert.size == 1 -> kurzesDatum(sortiert.first())
                zusammenhaengend -> "${kurzesDatum(sortiert.first())} – ${kurzesDatum(sortiert.last())}"
                sortiert.size <= MAX_AUFGEZAEHLTE_TAGE -> sortiert.joinToString(", ") { kurzesDatum(it) }
                else -> sortiert.take(MAX_AUFGEZAEHLTE_TAGE).joinToString(", ") { kurzesDatum(it) } +
                    " und weitere"
            }
            val text = when {
                weckzeiten.size == 1 ->
                    "Der Wecker um ${weckzeiten.first().format(UHRZEIT)} entfällt. Eigene Wecker bleiben."
                weckzeiten.size > 1 -> "${weckzeiten.size} Wecker entfallen. Eigene Wecker bleiben."
                sortiert.size == 1 -> "Kein Wecker aus dem Dienstplan an diesem Tag."
                else -> "Kein Wecker aus dem Dienstplan an diesen Tagen."
            }
            return Meldung(titel = "Krankmeldung erkannt: $spanne", text = text)
        }
    }

    data class Meldung(val titel: String, val text: String)

    /**
     * Zeigt die Meldung. Wirft nie in den Sync - der Aufrufer (`AlarmUseCase`) faengt trotzdem,
     * dieselbe Haltung wie beim [ShiftChangeNotifier].
     *
     * @return `true` nur, wenn die Meldung nachweislich abgesetzt wurde ([zeige]). Der Aufrufer
     *   quittiert die Vormerkung NUR dann - eine blockierte Zustellung holt der naechste Sync nach.
     */
    open fun melde(tage: List<LocalDate>, weckzeiten: List<LocalTime>): Boolean {
        val m = meldung(tage, weckzeiten)
        return zeige(m.titel, m.text)
    }

    /** @return `true`, wenn die Meldung nachweislich abgesetzt wurde. */
    protected open fun zeige(titel: String, text: String): Boolean {
        createNotificationChannelIfNeeded()

        // Erst NACH dem Anlegen pruefen - beim allerersten Mal gaebe es den Kanal sonst noch nicht.
        val zustellbarkeit = NotificationDeliverability.bestimme(context, CHANNEL_ID)
        if (!zustellbarkeit.erreicht) {
            Logger.w(LogTags.ALARM, "⚠️ Krankmeldung nicht zustellbar ($zustellbarkeit)")
            return false
        }

        // #142: der Tipp fuehrt in den Wecker-Tab - dort zeigt die Tagesliste die freien Tage mit
        // dem Etikett "Krank (Kalender)". Flags und MAIN/LAUNCHER: MainActivity.einstiegIntent.
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            MainActivity.einstiegIntent(context, MainActivity.EINSTIEG_KRANKMELDUNG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(titel)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // notify() kann werfen (Kontingent, defekter Zustand) - das darf den Sync nie erreichen.
        return try {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
            Logger.business(LogTags.ALARM, "🤒 Krankmeldung gezeigt: $titel")
            true
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "⚠️ Krankmeldung konnte nicht gepostet werden: ${e.message}")
            false
        }
    }

    /** Idempotent, sicher bei jedem [zeige]-Aufruf erneut aufzurufen. */
    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Krankmeldung aus dem Kalender",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Hinweis, wenn ein Krank-Termin im Kalender Wecker aus dem Dienstplan " +
                "entfallen lässt"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
