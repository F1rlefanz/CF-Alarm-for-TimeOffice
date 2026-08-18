package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet, dass ein ausgewaehlter Kalender DAUERHAFT nicht abrufbar ist.
 *
 * WARUM ES DAS GIBT: Faellt einer von mehreren Kalendern dauerhaft aus (geloescht, Freigabe
 * entzogen, Feed-Quelle abgeschaltet), ist jede Eventliste unvollstaendig. Die
 * Vollstaendigkeits-Sperren verhindern dann zu Recht das Loeschen von Alarmen - sie verhindern
 * damit aber auch, dass jemals wieder einer angelegt wird. Die bestehenden Wecker klingeln der
 * Reihe nach und laufen aus, nichts waechst nach.
 *
 * Die Status-Karte im Status-Tab zeigt das - aber nur, wenn der Nutzer die App oeffnet. Und der
 * Fehlerfall ist gerade, dass er das wochenlang nicht tut, weil die App ja "einfach laeuft". Nur
 * eine Benachrichtigung erreicht ihn dort.
 *
 * ENTPRELLT ueber [CalendarUnavailablePrefs]: ein einzelner Aussetzer (Funkloch waehrend des
 * Abrufs) erzeugt ebenfalls `failedCalendarIds`, ist aber kein Grund fuer eine Meldung. Gemeldet
 * wird erst, wenn derselbe Kalender ZWEI aufeinanderfolgende Wartungslaeufe scheitert - im
 * 6h-Takt also fruehestens nach etwa sechs Stunden. Die Entscheidung selbst ist eine reine
 * Funktion ([entscheideBenachrichtigung]) und ohne Android testbar.
 *
 * Eigener Channel wie bei [ShiftChangeNotifier], bewusst NICHT der Wartungs-Channel des
 * Vordergrunddienstes: andere Dringlichkeit, und der Nutzer soll ihn getrennt abschalten koennen.
 *
 * [zeige] ist `open`, damit Tests eine Fake-Unterklasse bilden koennen, die nur mitzaehlt -
 * dasselbe Muster wie bei [ShiftChangeNotifier].
 */
@Singleton
open class CalendarUnavailableNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: CalendarUnavailablePrefs
) {
    companion object {
        private const val CHANNEL_ID = "calendar_unavailable_alerts"

        /** Belegt sind 1001 (Wartung), 2002 (Wecker), 2101 (Dimm-Korrektur), 2202 (Schichtwechsel). */
        private const val NOTIFICATION_ID = 2203

        /**
         * PURE, TESTBAR: Was ist nach diesem Lauf zu tun?
         *
         * Die Regel in einem Satz: gemeldet wird eine Kalender-ID genau dann, wenn sie in DIESEM
         * und im VORHERIGEN Lauf gescheitert ist und fuer sie noch nicht gemeldet wurde.
         *
         * Die Bedingung "auch im vorherigen Lauf" ist der Unterschied zwischen einer nuetzlichen
         * Warnung und einer, die weggewischt wird: ein einzelner fehlgeschlagener Abruf ist im
         * Mobilfunk der Normalfall. Und "noch nicht gemeldet" verhindert, dass dieselbe Stoerung
         * alle sechs Stunden erneut klingelt.
         *
         * Erholt sich ein Kalender, faellt seine ID aus [zuletztGescheitert] UND aus
         * [bereitsGemeldet] - eine spaetere erneute Stoerung meldet sich also wieder. Ohne dieses
         * Aufraeumen waere die Warnung ein Einmal-Ereignis auf Lebenszeit der Installation.
         */
        fun entscheideBenachrichtigung(
            jetztGescheitert: Set<String>,
            zuletztGescheitert: Set<String>,
            bereitsGemeldet: Set<String>
        ): Benachrichtigungsentscheidung {
            val beharrlich = jetztGescheitert intersect zuletztGescheitert
            val neuZuMelden = beharrlich - bereitsGemeldet
            return Benachrichtigungsentscheidung(
                zuMelden = neuZuMelden,
                neuerZuletztGescheitert = jetztGescheitert,
                // Nur wer JETZT noch scheitert, bleibt gemeldet. Wer sich erholt hat, wird
                // vergessen - sonst bliebe er fuer immer stumm geschaltet.
                neuerBereitsGemeldet = (bereitsGemeldet + neuZuMelden) intersect jetztGescheitert
            )
        }
    }

    /** Ergebnis von [entscheideBenachrichtigung]: was zu melden ist und was zu merken. */
    data class Benachrichtigungsentscheidung(
        val zuMelden: Set<String>,
        val neuerZuletztGescheitert: Set<String>,
        val neuerBereitsGemeldet: Set<String>
    )

    /**
     * Nach jedem Kalenderabruf im Hintergrund aufzurufen - AUCH mit einer leeren Menge, denn
     * genau daran erkennt die Entprellung, dass sich ein Kalender erholt hat.
     */
    open suspend fun onFetchOutcome(gescheiterteKalenderIds: Set<String>) {
        val zustand = prefs.zustandNow()
        val entscheidung = entscheideBenachrichtigung(
            jetztGescheitert = gescheiterteKalenderIds,
            zuletztGescheitert = zustand.zuletztGescheitert,
            bereitsGemeldet = zustand.bereitsGemeldet
        )

        prefs.setZustand(
            zuletztGescheitert = entscheidung.neuerZuletztGescheitert,
            bereitsGemeldet = entscheidung.neuerBereitsGemeldet
        )

        if (entscheidung.zuMelden.isEmpty()) return
        if (!prefs.enabledNow()) {
            Logger.d(LogTags.CALENDAR, "Kalender-Warnung unterdrueckt (vom Nutzer abgeschaltet)")
            return
        }

        val anzahl = entscheidung.zuMelden.size
        zeige(
            title = if (anzahl == 1) "Ein Kalender ist nicht mehr abrufbar" else "$anzahl Kalender sind nicht mehr abrufbar",
            text = "Solange legt CF-Alarm keine neuen Wecker an; die bereits gestellten bleiben. " +
                "Im Status-Tab unter \"Kalender\" steht, welcher betroffen ist — dort lässt er " +
                "sich auch aus der Auswahl entfernen."
        )
    }

    protected open fun zeige(title: String, text: String) {
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

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        Logger.business(LogTags.CALENDAR, "📵 Kalender-Warnung gezeigt: $title")
    }

    /** Idempotent, sicher bei jedem [zeige]-Aufruf erneut aufzurufen. */
    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kalender nicht abrufbar",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Warnung, wenn ein ausgewählter Kalender dauerhaft nicht mehr abrufbar " +
                "ist und deshalb keine neuen Wecker mehr entstehen"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
