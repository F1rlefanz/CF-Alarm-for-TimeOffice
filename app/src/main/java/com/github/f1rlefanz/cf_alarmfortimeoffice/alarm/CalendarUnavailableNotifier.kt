package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability
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
 * Reihe nach und laufen aus, nichts waechst nach. Fallen ALLE aus - meist ist es genau einer, der
 * Dienstplan-Feed -, gibt es gar keine Eventliste mehr, und die Folge ist dieselbe; seit
 * 30.09.2026 meldet die Wartung auch diesen Fall (vorher sah die Warnung nur Teilausfaelle).
 * WIE der Abruf ausging, bestimmt nur den Text ([Ausfall], [meldung]).
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
 * dasselbe Muster wie bei [ShiftChangeNotifier]. Sie gibt zurueck, ob die Meldung wirklich
 * abgesetzt wurde; daran haengt der "bereits gemeldet"-Merker.
 */
@Singleton
open class CalendarUnavailableNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: CalendarUnavailablePrefs
) {
    companion object {
        private const val CHANNEL_ID = "calendar_unavailable_alerts"

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

        private const val FOLGE =
            "Solange legt CF-Alarm keine neuen Wecker an; die bereits gestellten bleiben."

        /**
         * PURE, TESTBAR: Titel und Text der Meldung fuer [anzahl] zu meldende Kalender.
         *
         * Jeder Text nennt WAS, die FOLGE und WOHIN - und das WOHIN muss in der App genau das
         * zeigen, was der Text verspricht (CalendarUnavailableNotifierTextTest):
         *  - [Ausfall.EINZELNE]: die Karte "Kalender" bietet "Aus Auswahl entfernen" an; danach
         *    bleibt mindestens ein Kalender, das Entfernen ist harmlos. KEIN Versprechen, dort
         *    stehe WELCHER: den Namen kennt die Karte nur, solange Google den Kalender noch fuehrt -
         *    ein geloeschter, der typische dauerhafte Anlass, steht dort nur als Anzahl.
         *  - [Ausfall.ALLE_NICHT_GEFUNDEN]: Titel und erster Satz wortgleich mit der Karte der
         *    Uebersicht (`KALENDER_NICHT_GEFUNDEN_TITEL`/`_TEXT`, aus `alarm/` nicht lesbar). KEIN
         *    Entfernen-Rat: beim letzten Kalender waere das eine Abwahl, die alle Wecker der
         *    naechsten zwei Wochen raeumt - die App fragt dort nach und bietet zuerst einen anderen
         *    Kalender an.
         *  - [Ausfall.ALLE_NICHT_ABRUFBAR]: die Ursache ist gerade NICHT bekannt, also weder
         *    "nicht gefunden" noch "melde dich an". In der App steht dann die Anmeldung, kein
         *    Entfernen-Knopf.
         *
         * Beim Totalausfall haengt der Text nicht an [anzahl]: in DIESEM Lauf scheiterten alle
         * ausgewaehlten Kalender, "keinen" stimmt also immer - auch wenn die Entprellung erst
         * einen Teil davon meldet.
         */
        fun meldung(ausfall: Ausfall, anzahl: Int): Meldung = when (ausfall) {
            Ausfall.EINZELNE -> Meldung(
                titel = if (anzahl == 1) {
                    "Ein Kalender ist nicht mehr abrufbar"
                } else {
                    "$anzahl Kalender sind nicht mehr abrufbar"
                },
                text = "$FOLGE Im System-Status unter \"Kalender\" " +
                    (if (anzahl == 1) "lässt er sich" else "lassen sie sich") +
                    " aus der Auswahl entfernen."
            )

            Ausfall.ALLE_NICHT_GEFUNDEN -> Meldung(
                titel = "Kalender nicht gefunden",
                text = "Google findet keinen ausgewählten Kalender mehr — gelöscht oder nicht mehr " +
                    "freigegeben? $FOLGE Näheres im System-Status unter \"Kalender\"."
            )

            Ausfall.ALLE_NICHT_ABRUFBAR -> Meldung(
                titel = "Kalender nicht abrufbar",
                text = "CF-Alarm konnte bei mehreren Versuchen in Folge keinen ausgewählten Kalender " +
                    "abrufen. $FOLGE Näheres im System-Status unter \"Kalender\"."
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
     * Wie der Abruf ausging - bestimmt NUR den Text der Meldung ([meldung]), nie die Entprellung:
     * die zaehlt je Kennung, gleich aus welchem Grund sie scheiterte.
     */
    enum class Ausfall {
        /**
         * Der Abruf lieferte ein Ergebnis; gescheitert sind hoechstens EINIGE Kalender (auch keiner
         * - dann ist die Menge leer). Die Ursache je Kalender ist hier nicht bekannt.
         */
        EINZELNE,

        /** Alle ausgewaehlten Kalender scheiterten, und Google kennt sie nicht (mehr): 404, 403 ohne Scope-Mangel. */
        ALLE_NICHT_GEFUNDEN,

        /**
         * Alle scheiterten aus einem anderen Grund als der Verbindung - abgelehnte Anmeldung,
         * abgeschnittene Terminliste, Unbekanntes. Ein Funkloch wird gar nicht erst gemeldet.
         */
        ALLE_NICHT_ABRUFBAR
    }

    data class Meldung(val titel: String, val text: String)

    /**
     * Nach jedem Kalenderabruf im Hintergrund aufzurufen - AUCH mit einer leeren Menge, denn
     * genau daran erkennt die Entprellung, dass sich ein Kalender erholt hat.
     */
    open suspend fun onFetchOutcome(gescheiterteKalenderIds: Set<String>, ausfall: Ausfall) {
        val zustand = prefs.zustandNow()
        val entscheidung = entscheideBenachrichtigung(
            jetztGescheitert = gescheiterteKalenderIds,
            zuletztGescheitert = zustand.zuletztGescheitert,
            bereitsGemeldet = zustand.bereitsGemeldet
        )

        // Der "habe ich schon gesagt"-Merker darf NUR wachsen, wenn tatsaechlich etwas gesagt wurde.
        // Wuchs er bei abgeschaltetem Toggle oder bei blockiertem Kanal, fiel die ID nie wieder
        // heraus - nach dem Wiedereinschalten kam die Warnung NIE.
        // Deshalb wird JETZT ERST gemeldet und DANACH gemerkt - nur der bestaetigte Post zaehlt.
        val darfMelden = entscheidung.zuMelden.isNotEmpty() && prefs.enabledNow()
        val meldung = meldung(ausfall, entscheidung.zuMelden.size)
        val wurdeGemeldet = darfMelden && zeige(title = meldung.titel, text = meldung.text)

        // Der Beharrlichkeits-Merker wird dagegen IMMER fortgeschrieben - die Entprellung braucht
        // jeden Lauf, auch die stillen, um "dauerhaft" von "Aussetzer" zu unterscheiden und um zu
        // erkennen, dass sich ein Kalender erholt hat. Er darf sich durch die Zustell-Frage NICHT
        // mitaendern.
        val neuBereitsGemeldet = if (wurdeGemeldet) {
            entscheidung.neuerBereitsGemeldet
        } else {
            // Nur aufraeumen, nichts hinzufuegen: erholte Kalender fallen raus, aber kein
            // ungemeldeter kommt hinein - beim naechsten Anlass wird erneut versucht.
            zustand.bereitsGemeldet intersect gescheiterteKalenderIds
        }
        prefs.setZustand(
            zuletztGescheitert = entscheidung.neuerZuletztGescheitert,
            bereitsGemeldet = neuBereitsGemeldet
        )

        // ERHOLT: Stand eine Meldung, und scheitert keiner der gemeldeten Kalender mehr (wieder
        // abrufbar oder aus der Auswahl genommen), wird sie wieder eingesammelt - eine Warnung
        // ueber einer wieder funktionierenden App ist dieselbe Unwahrheit wie eine falsche. Die
        // Wartung sammelt ihre Stoerungsmeldung nach derselben Regel ein (quittiereTokenErfolg).
        // NUR bei vorher Gemeldetem: was nie dastand, gibt es nicht zurueckzunehmen.
        if (zustand.bereitsGemeldet.isNotEmpty() && neuBereitsGemeldet.isEmpty()) {
            nimmZurueck()
        }

        if (entscheidung.zuMelden.isNotEmpty() && !darfMelden) {
            Logger.d(LogTags.CALENDAR, "Kalender-Warnung unterdrueckt (vom Nutzer abgeschaltet)")
        }
    }

    /**
     * @return `true`, wenn die Meldung nachweislich abgesetzt wurde. Nur dann darf der
     *   "bereits gemeldet"-Merker wachsen (siehe [onFetchOutcome]).
     */
    protected open fun zeige(title: String, text: String): Boolean {
        createNotificationChannelIfNeeded()

        // Erst NACH dem Anlegen pruefen: vorher gaebe es den Kanal beim allerersten Lauf noch
        // gar nicht. Ein bereits vom Nutzer abgeschalteter Kanal bleibt abgeschaltet, auch wenn
        // createNotificationChannel() erneut laeuft - die Pruefung sieht also die Wahrheit.
        val zustellbarkeit = NotificationDeliverability.bestimme(context, CHANNEL_ID)
        if (!zustellbarkeit.erreicht) {
            Logger.w(
                LogTags.CALENDAR,
                "⚠️ Kalender-Warnung nicht zustellbar ($zustellbarkeit) - sie wird beim naechsten " +
                    "Wartungslauf erneut versucht"
            )
            return false
        }

        // Der Tipp fuehrt in den System-Status und laedt den Kalender neu (MainActivity,
        // EINSTIEG_KALENDER_WARNUNG) - der Text schickt genau dorthin. Der blosse Start-Intent
        // holte eine laufende App mit ihrem LETZTEN Stand nach vorn: anderer Tab, und die Karte
        // zeigte den Abruf von vor dem Ausfall. SINGLE_TOP, damit CLEAR_TOP die laufende
        // MainActivity (standard) nicht neu anlegt, sondern ihr onNewIntent gibt.
        //
        // Eigener Request-Code: PendingIntents vergleichen sich OHNE Extras, und die
        // Dimmer-Meldung haelt unter Code 3 einen gleich gebauten Intent (DimCorrectionNotifier).
        // Mit demselben Code ueberschriebe FLAG_UPDATE_CURRENT deren Ziel.
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(MainActivity.EXTRA_EINSTIEG, MainActivity.EINSTIEG_KALENDER_WARNUNG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // notify() kann werfen (z.B. Kontingent ueberschritten, defekter RemoteViews-Zustand).
        // Ein Wurf hier duerfte weder den Wartungslauf abbrechen noch als "gemeldet" gelten.
        return try {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
            Logger.business(LogTags.CALENDAR, "📵 Kalender-Warnung gezeigt: $title")
            true
        } catch (e: Exception) {
            Logger.w(LogTags.CALENDAR, "⚠️ Kalender-Warnung konnte nicht gepostet werden: ${e.message}")
            false
        }
    }

    /**
     * Raeumt die Warnung wieder ab (siehe [onFetchOutcome], "ERHOLT"). Die Kennung 2203 gehoert
     * allein dieser Warnung - anders als die 1002 der Wartung, die sich mehrere Meldungen teilen.
     * Ein Fehlschlag wird nur geloggt: die Wartung darf daran nicht scheitern.
     */
    protected open fun nimmZurueck() {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            Logger.business(LogTags.CALENDAR, "✅ Kalender-Warnung zurueckgenommen - der Abruf klappt wieder")
        } catch (e: Exception) {
            Logger.w(LogTags.CALENDAR, "⚠️ Kalender-Warnung konnte nicht zurueckgenommen werden: ${e.message}")
        }
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
