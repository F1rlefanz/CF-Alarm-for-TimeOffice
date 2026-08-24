package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
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
 * Zustand + Zeit-/Namens-Schwelle + Sammlung mehrerer Aenderungen), entscheidet ausschliesslich
 * diese Klasse.
 *
 * ## Warum gesammelt wird (Pruefrunde 8, Befund 5)
 *
 * Bis v1.29.2 postete jede der drei Meldungsarten ihren Einzeltext unter derselben festen
 * [NOTIFICATION_ID] - und `notify()` mit gleicher ID ERSETZT die stehende Meldung. Die Aufrufer in
 * `AlarmUseCase.syncAlarms()` sind aber Schleifen ueber den GESAMTEN Bestand: ein Dienstplan-Block,
 * der drei Dienste streicht und zwei neue bringt, erzeugt sechs Aufrufe in Millisekunden, von denen
 * der Nutzer genau den letzten sah. Die uebrigen fuenf waren spurlos weg - schlimmer noch: die
 * stehengebliebene Meldung behauptete implizit, es habe genau diese eine Aenderung gegeben. Fuer
 * einen Schichtarbeiter ist die Sammelaenderung aber genau der Anlass, bei dem er die Meldung
 * braucht (drei gestrichene Dienste = drei geloeschte Wecker, von denen er zwei nie erfuhr).
 *
 * Gewaehlt wurde die **sammelnde** Variante (eine Meldung, InboxStyle, Zaehler im Titel) statt
 * pro-Alarm abgeleiteter IDs. Gruende:
 *  - Bei einem grossen Dienstplanwechsel hinterlaesst die ID-Variante ein Dutzend Einzelmeldungen
 *    in der Leiste; sie braeuchte zusaetzlich Gruppierung + Summary, die es in dieser App bisher
 *    nirgends gibt (`grep setGroup` findet nur Hue-Treffer).
 *  - Sie bliebe bei der einen, bereits vergebenen ID 2202. Kein neuer ID-Bereich, also auch keine
 *    Kollisionsgefahr mit 1001/1002 (Wartung), 2002 (Wecker), 2003 (Notausgang), 2101 (Dimm-
 *    Korrektur), 2203 (Kalender), 9999 (Skip-Bestaetigung).
 *  - Das mehrfache Alarmieren entfaellt gleich mit ([setOnlyAlertOnce]); vorher summte das Geraet
 *    n-mal fuer eine Meldung.
 *
 * Der Kanal bleibt unangetastet (`IMPORTANCE_DEFAULT`) - die Projektregel "wer die Wichtigkeit
 * eines Kanals aendert, braucht eine NEUE Kanal-ID" ist hier gar nicht beruehrt.
 */
@Singleton
open class ShiftChangeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: ShiftChangeNotificationPrefs
) {
    /**
     * Der Stand der laufenden Sammlung: die (gekappten) Einzelzeilen, wie viele Aenderungen
     * insgesamt dazugehoeren ([gesamt] kann groesser sein als `zeilen.size`) und wann die Sammlung
     * begann.
     *
     * [begonnenNeu] sagt dem Aufrufer, ob dieser Aufruf eine FRISCHE Sammlung eroeffnet hat - dann
     * wird die stehende Meldung vorher weggenommen, damit die neue trotz [setOnlyAlertOnce] wieder
     * hoerbar ist.
     */
    data class Sammlung(
        val zeilen: List<String>,
        val gesamt: Int,
        val beginn: Long,
        val begonnenNeu: Boolean
    )

    companion object {
        private const val CHANNEL_ID = "shift_change_alerts"
        private const val NOTIFICATION_ID = 2202

        /**
         * Schluessel, unter denen die Sammlung IN der geposteten Notification mitreist. Damit
         * ueberlebt sie den Prozesstod: stirbt der Prozess mitten im Sync (Wartungsdienst wird
         * abgeraeumt, Speicherdruck), findet der naechste Lauf die bereits gemeldeten Zeilen in der
         * noch stehenden Meldung wieder und haengt an, statt sie durch eine Ein-Zeilen-Meldung zu
         * ersetzen und damit die frueheren Aenderungen zu verschweigen.
         */
        private const val EXTRA_ZEILEN = "cf_schichtaenderung_zeilen"
        private const val EXTRA_GESAMT = "cf_schichtaenderung_gesamt"
        private const val EXTRA_BEGINN = "cf_schichtaenderung_beginn"

        /**
         * So viele Einzelzeilen werden mitgefuehrt und angezeigt; was darueber hinaus faellt, wird
         * als "… und N weitere Aenderungen" beziffert, nicht verschwiegen. Die Systemvorlage fuer
         * InboxStyle zeigt ohnehin nur eine Handvoll Zeilen an.
         */
        const val MAX_ZEILEN = 6

        /**
         * Wie lange Aenderungen in EINE Meldung wandern. Ein Sync-Lauf feuert seine n Aufrufe
         * innerhalb von Millisekunden ab (alle unter demselben `alarmSyncMutex`), auch ein manuell
         * angestossener zweiter Lauf liegt Minuten spaeter - beides faellt bequem in dieses
         * Fenster. Der regulaere Wartungslauf kommt dagegen erst in sechs Stunden wieder: eine
         * spaetere, eigenstaendige Dienstplan-Aenderung eroeffnet also eine neue Sammlung und
         * meldet sich hoerbar, statt still an eine halbtags alte Meldung anzuwachsen.
         */
        const val SAMMEL_FENSTER_MINUTEN = 15L
        private const val SAMMEL_FENSTER_MS = SAMMEL_FENSTER_MINUTEN * 60_000L

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

        /**
         * Haengt [neueZeile] an die laufende Sammlung an - oder eroeffnet eine neue, wenn es keine
         * gibt bzw. die vorhandene aelter als [SAMMEL_FENSTER_MINUTEN] ist.
         *
         * Reine Funktion (kein Context, keine Prefs, Uhrzeit als Parameter), damit die eigentliche
         * Zusicherung - "n Aenderungen erzeugen eine Meldung, die alle n kennt" - ohne Geraet
         * pruefbar ist.
         *
         * `jetzt < beginn` (Uhr zurueckgestellt, Zeitzonenwechsel) eroeffnet ebenfalls neu: sonst
         * bliebe eine Sammlung mit einem Beginn in der Zukunft beliebig lange offen.
         */
        fun sammle(stehend: Sammlung?, jetzt: Long, neueZeile: String): Sammlung {
            val fortsetzbar = stehend != null &&
                jetzt >= stehend.beginn &&
                jetzt - stehend.beginn < SAMMEL_FENSTER_MS

            if (!fortsetzbar) {
                return Sammlung(
                    zeilen = listOf(neueZeile),
                    gesamt = 1,
                    beginn = jetzt,
                    begonnenNeu = true
                )
            }

            val bisher = stehend
            // maxOf: der Zaehler aus den Notification-Extras koennte kleiner sein als die Zahl der
            // dort mitgereisten Zeilen (fremd beschriebene/abgeschnittene Extras). Dann gilt die
            // groessere, belegbare Zahl - untertreiben waere hier dieselbe Luege wie das alte
            // Ueberschreiben.
            val gesamtVorher = maxOf(bisher.gesamt, bisher.zeilen.size)
            return Sammlung(
                zeilen = (bisher.zeilen + neueZeile).takeLast(MAX_ZEILEN),
                gesamt = gesamtVorher + 1,
                beginn = bisher.beginn,
                begonnenNeu = false
            )
        }

        /**
         * Der Titel nennt die Zahl, sobald es mehr als eine Aenderung ist - die Einzelmeldung
         * behaelt ihren bisherigen, konkreten Titel.
         */
        fun titelFuer(gesamt: Int, einzelTitel: String): String =
            if (gesamt <= 1) einzelTitel else "$gesamt Dienstplan-Änderungen"

        /**
         * Die anzuzeigenden Zeilen. Mitgefuehrt werden die JUENGSTEN [MAX_ZEILEN]; was aelter ist,
         * wird oben als Zahl ausgewiesen. Der Nutzer sieht damit nie eine Meldung, die weniger
         * behauptet, als tatsaechlich passiert ist.
         */
        fun anzeigeZeilen(zeilen: List<String>, gesamt: Int): List<String> {
            val verdeckt = gesamt - zeilen.size
            return if (verdeckt > 0) listOf("… und $verdeckt weitere Änderungen") + zeilen else zeilen
        }

        /** Einzeilige Fassung fuer die Sammelliste - Praefix, damit die Art in der Liste erkennbar bleibt. */
        fun zeileNeu(new: AlarmInfo): String =
            "Neu: ${new.shiftName} ab ${timeOnly(new.triggerTime)}, Wecker gestellt"

        fun zeileGeaendert(old: AlarmInfo, new: AlarmInfo): String =
            if (old.shiftName != new.shiftName) {
                "Geändert: ${old.shiftName} → ${new.shiftName}, Wecker angepasst auf ${timeOnly(new.triggerTime)}"
            } else {
                "Geändert: ${new.shiftName} ${timeOnly(old.triggerTime)} → ${timeOnly(new.triggerTime)}, Wecker angepasst"
            }

        fun zeileEntfernt(old: AlarmInfo): String =
            "Entfernt: ${old.shiftName}, Wecker gelöscht"

        private fun timeOnly(millis: Long): String =
            DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
    }

    /**
     * Der Stand der laufenden Sammlung im Prozess. `@Volatile` reicht: die drei notify-Methoden
     * laufen ausschliesslich innerhalb von `syncAlarms()`, das unter `alarmSyncMutex` steht, also
     * seriell.
     *
     * Warum ein Feld und nicht bei JEDEM Aufruf aus `activeNotifications` gelesen wird: `notify()`
     * wird vom System asynchron auf einem eigenen Handler verarbeitet, ein unmittelbar folgendes
     * `getActiveNotifications()` sieht die eben gepostete Meldung also moeglicherweise noch nicht.
     * Genau das ist der Millisekunden-Abstand innerhalb eines Sync-Laufs. Das System wird deshalb
     * nur befragt, wenn hier noch nichts steht - also nach einem Prozessneustart.
     */
    @Volatile
    private var laufendeSammlung: Sammlung? = null

    open suspend fun notifyCreated(new: AlarmInfo) {
        if (!prefs.enabledNow()) return
        show(
            einzelTitel = "Neue Schicht erkannt",
            einzelText = "${new.shiftName} ab ${timeOnly(new.triggerTime)}, Wecker gestellt",
            sammelZeile = zeileNeu(new)
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
        show(
            einzelTitel = "Schicht geändert",
            einzelText = text,
            sammelZeile = zeileGeaendert(old, new)
        )
    }

    open suspend fun notifyDeleted(old: AlarmInfo) {
        if (!prefs.enabledNow()) return
        show(
            einzelTitel = "Schicht entfernt",
            einzelText = "${old.shiftName}, Wecker gelöscht",
            sammelZeile = zeileEntfernt(old)
        )
    }

    /**
     * Postet die Sammlung. Wirft nie: der Aufrufer steckt mitten in der Alarm-Erzeugung, und die
     * Notification ist Beiwerk - die Wecker sind die Hauptsache.
     */
    private fun show(einzelTitel: String, einzelText: String, sammelZeile: String) {
        try {
            createNotificationChannelIfNeeded()

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
                    "⚠️ Schicht-Aenderungs-Notification nicht zustellbar ($zustellbarkeit): \"$einzelTitel\" erreicht den Nutzer nicht"
                )
                return
            }

            val basis = laufendeSammlung ?: sammlungAusStehenderMeldung()
            val sammlung = sammle(basis, System.currentTimeMillis(), sammelZeile)

            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Eine NEUE Sammlung soll wieder hoerbar sein. Mit setOnlyAlertOnce(true) alarmiert eine
            // Aktualisierung der stehenden Meldung nicht erneut - ohne dieses Wegnehmen bliebe eine
            // Dienstplan-Aenderung, die Stunden nach einer alten Meldung eintrifft, stumm.
            if (sammlung.begonnenNeu) {
                notificationManager.cancel(NOTIFICATION_ID)
            }

            notificationManager.notify(
                NOTIFICATION_ID,
                baue(sammlung, einzelTitel, einzelText)
            )
            laufendeSammlung = sammlung
            Logger.d(
                LogTags.ALARM,
                "Schicht-Aenderungs-Notification gezeigt (${sammlung.gesamt} Aenderung(en) gesammelt): $einzelTitel"
            )
        } catch (e: Exception) {
            // Ein Wurf hier darf den laufenden Alarm-Sync nicht mitreissen.
            Logger.w(LogTags.ALARM, "⚠️ Schicht-Aenderungs-Notification konnte nicht gepostet werden: ${e.message}")
        }
    }

    private fun baue(
        sammlung: Sammlung,
        einzelTitel: String,
        einzelText: String
    ): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val titel = titelFuer(sammlung.gesamt, einzelTitel)
        // Bei genau einer Aenderung bleibt alles wie bisher (Titel + ausformulierter Text). Erst ab
        // der zweiten wird die Meldung zur Liste - der eingeklappte Text zeigt dann die juengste
        // Aenderung, der Titel die Gesamtzahl, aufgeklappt stehen alle Zeilen da.
        val kurztext = if (sammlung.gesamt <= 1) einzelText else sammlung.zeilen.last()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(titel)
            .setContentText(kurztext)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // Ohne das summte das Geraet bei einem Dienstplanwechsel n-mal fuer EINE sichtbare
            // Meldung. Der Gegen-Effekt (eine spaetere, eigenstaendige Aenderung bliebe stumm) ist
            // durch das Wegnehmen bei begonnenNeu abgedeckt.
            .setOnlyAlertOnce(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }

        if (sammlung.gesamt <= 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(einzelText))
        } else {
            val inbox = NotificationCompat.InboxStyle()
            anzeigeZeilen(sammlung.zeilen, sammlung.gesamt).forEach { inbox.addLine(it) }
            builder.setStyle(inbox)
        }

        // Die Sammlung reist in der Notification mit - siehe sammlungAusStehenderMeldung().
        builder.addExtras(
            Bundle().apply {
                putStringArray(EXTRA_ZEILEN, sammlung.zeilen.toTypedArray())
                putInt(EXTRA_GESAMT, sammlung.gesamt)
                putLong(EXTRA_BEGINN, sammlung.beginn)
            }
        )

        return builder.build()
    }

    /**
     * Holt die Sammlung aus der noch stehenden Meldung zurueck - der Weg ueber den Prozesstod
     * hinweg. Findet sich nichts (oder scheitert die Abfrage), wird schlicht neu begonnen; das ist
     * der bisherige Zustand und nicht schlechter als vorher.
     */
    private fun sammlungAusStehenderMeldung(): Sammlung? = try {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val stehend = notificationManager.activeNotifications
            ?.firstOrNull { it.id == NOTIFICATION_ID }
        stehend?.notification?.extras?.let { extras ->
            val zeilen = extras.getStringArray(EXTRA_ZEILEN)?.filterNotNull().orEmpty()
            val beginn = extras.getLong(EXTRA_BEGINN, 0L)
            if (zeilen.isEmpty() || beginn <= 0L) {
                null
            } else {
                Sammlung(
                    zeilen = zeilen,
                    gesamt = extras.getInt(EXTRA_GESAMT, zeilen.size),
                    beginn = beginn,
                    begonnenNeu = false
                )
            }
        }
    } catch (e: Exception) {
        Logger.w(LogTags.ALARM, "Stehende Schicht-Aenderungs-Meldung nicht lesbar: ${e.message}")
        null
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
