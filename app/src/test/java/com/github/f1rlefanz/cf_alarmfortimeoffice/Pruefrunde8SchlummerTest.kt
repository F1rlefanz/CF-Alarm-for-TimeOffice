package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SchlummerEntscheidung
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SnoozeErgebnis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefrunde 8, zwei Befunde am Schlummer-Pfad — beide fixiert an [SchlummerEntscheidung], dem
 * gemeinsamen Kern von `AlarmManagerService.armSnooze()`.
 *
 * BEFUND "kein Master-Pause-Backstop": Schlummern war der EINZIGE Armierungspfad ohne
 * Pausen-Pruefung. Der Ablauf "Wecker klingelt -> Nutzer schaltet die Master-Pause ein -> Nutzer
 * drueckt schlummern" armierte einen Wecker mitten in der Pause, den danach nichts mehr abraeumte:
 * die 6h-Wartungskette ist beim Pausieren gekappt, und `syncAlarms()` laeuft nur, wenn der Nutzer
 * die App wieder oeffnet. Die Oberflaeche zeigte dabei "Hintergrunddienste pausiert".
 *
 * BEFUND "Fehlschlag sieht aus wie Erfolg": `armSnooze()` gab zwar `Boolean` zurueck, aber
 * `scheduleSnooze()` verwarf ihn, und beide Nutzerpfade beendeten den Wecker danach UNBEDINGT.
 * Ton aus, Bildschirm zu, kein Wecker, kein Merker, nur eine Zeile im Log — verschlafen ohne
 * jeden Hinweis.
 *
 * WARUM DIESE FORM: Die Android-Haelfte (PendingIntent, AlarmManager, Notification) laesst sich im
 * JVM-Unit-Test nicht pruefen; die ENTSCHEIDUNG dagegen schon. Vorbild ist
 * [Pruefrunde6ExactAlarmNotausgangTest], das denselben Schnitt an
 * `AlarmReceiver.starteWeckerMitNotausgang` nutzt. Ohne den Fix existiert
 * [SchlummerEntscheidung] gar nicht — jeder Test hier faellt.
 */
class Pruefrunde8SchlummerTest {

    /** Haelt die Reihenfolge fest, nicht nur das Ob. */
    private class Protokoll {
        val schritte = mutableListOf<String>()
        val meldungen = mutableListOf<String>()
    }

    private fun laufe(
        pausiert: Boolean,
        planeWirft: Throwable? = null,
        merkeWirft: Throwable? = null,
        p: Protokoll = Protokoll()
    ): Pair<SnoozeErgebnis, Protokoll> {
        val ergebnis = SchlummerEntscheidung.armiere(
            pausiert = pausiert,
            plane = {
                p.schritte.add("plane")
                planeWirft?.let { throw it }
            },
            merke = {
                p.schritte.add("merke")
                merkeWirft?.let { throw it }
            },
            melde = { text, _ -> p.meldungen.add(text) }
        )
        return ergebnis to p
    }

    @Test
    fun `waehrend der Master-Pause wird weder geplant noch vorgemerkt`() {
        val (ergebnis, p) = laufe(pausiert = true)

        assertEquals(SnoozeErgebnis.ABGELEHNT_PAUSE, ergebnis)
        assertEquals(
            "Ein hier armierter Wecker klingelte mitten in der Pause und waere durch nichts mehr " +
                "abzuraeumen - die 6h-Kette ist beim Pausieren gekappt",
            emptyList<String>(),
            p.schritte
        )
        assertEquals(
            "Die Ablehnung MUSS im Log stehen: ein stilles Nichts ist genau der Fehler",
            listOf(SchlummerEntscheidung.MELDUNG_PAUSIERT),
            p.meldungen
        )
    }

    @Test
    fun `ohne Pause wird erst geplant und erst danach vorgemerkt`() {
        val (ergebnis, p) = laufe(pausiert = false)

        assertEquals(SnoozeErgebnis.GEPLANT, ergebnis)
        // Die Reihenfolge ist tragend: der Merker ist die einzige Spur, ueber die ein schwebender
        // Schlummer abgebrochen oder nach einem Neustart wiederhergestellt wird. Ein Eintrag ohne
        // Alarm behauptet im Boot-Log einen Wecker, den es nicht gibt.
        assertEquals(listOf("plane", "merke"), p.schritte)
        assertTrue("Der Erfolgsfall meldet nichts", p.meldungen.isEmpty())
    }

    @Test
    fun `scheitert die Planung, gibt es keinen Merker und kein GEPLANT`() {
        val (ergebnis, p) = laufe(
            pausiert = false,
            planeWirft = SecurityException("Exact-Alarm-Berechtigung entzogen")
        )

        assertEquals(
            "Ein Fehlschlag, der als Erfolg zurueckkommt, ist genau der stille Ausfall aus " +
                "Pruefrunde 8",
            SnoozeErgebnis.FEHLGESCHLAGEN,
            ergebnis
        )
        assertEquals(listOf("plane"), p.schritte)
        assertEquals(1, p.meldungen.size)
    }

    @Test
    fun `auch eine gewoehnliche Exception der Planung wird gefangen`() {
        // Versionsunabhaengiger zweiter Weg in denselben Fehlerfall: die AlarmManager-Obergrenze
        // von 500 Alarmen pro App, DeadSystemException/RemoteException-Wrapper.
        val (ergebnis, p) = laufe(pausiert = false, planeWirft = IllegalStateException("zu viele Alarme"))

        assertEquals(SnoozeErgebnis.FEHLGESCHLAGEN, ergebnis)
        assertEquals(listOf("plane"), p.schritte)
    }

    @Test
    fun `scheitert das Vormerken, gilt der Schlummer als nicht gestellt`() {
        // Der Alarm waere zwar scharf, aber weder abbrechbar noch reboot-fest. Lieber klingelt der
        // Wecker weiter, als dem Nutzer einen Zustand zuzusagen, den die App nicht in der Hand hat.
        val (ergebnis, p) = laufe(pausiert = false, merkeWirft = RuntimeException("Prefs kaputt"))

        assertEquals(SnoozeErgebnis.FEHLGESCHLAGEN, ergebnis)
        assertEquals(listOf("plane", "merke"), p.schritte)
    }

    @Test
    fun `jeder Fehlschlag traegt einen Nutzertext, der Erfolg nicht`() {
        assertNull(
            "Ein erfolgreicher Schlummer darf keine Fehlermeldung erzeugen",
            SchlummerEntscheidung.hinweisText(SnoozeErgebnis.GEPLANT)
        )
        SnoozeErgebnis.entries
            .filter { it != SnoozeErgebnis.GEPLANT }
            .forEach { ergebnis ->
                val text = SchlummerEntscheidung.hinweisText(ergebnis)
                assertNotNull(
                    "$ergebnis ohne Nutzertext waere wieder der stille Ausfall: der Bildschirm " +
                        "schliesst sich, als sei alles gut",
                    text
                )
                assertTrue(text!!.isNotBlank())
            }
    }

    @Test
    fun `die beiden Fehlschlaege sagen dem Nutzer Verschiedenes`() {
        // Sie verlangen verschiedene Antworten: bei Pause ist nichts kaputt (Pause fortsetzen),
        // beim Planungsfehler muss sich der Nutzer selbst einen Wecker stellen.
        assertTrue(
            SchlummerEntscheidung.hinweisText(SnoozeErgebnis.ABGELEHNT_PAUSE) !=
                SchlummerEntscheidung.hinweisText(SnoozeErgebnis.FEHLGESCHLAGEN)
        )
    }
}
