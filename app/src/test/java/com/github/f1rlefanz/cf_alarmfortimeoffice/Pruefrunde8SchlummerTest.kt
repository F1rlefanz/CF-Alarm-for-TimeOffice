package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RueckbauErgebnis
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SchlummerEntscheidung
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.SnoozeErgebnis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefrunde 8, drei Befunde am Schlummer-Pfad — alle fixiert an [SchlummerEntscheidung], dem
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
 * BEFUND "die Ueberschrift widerspricht ihrem Text": ueber allen Fehlschlagstexten stand EIN
 * fester Titel "Kein Schlummer-Wecker gestellt" — auch ueber dem unklaren Ausgang, der bewusst
 * nur sagt, dass es moeglicherweise doch klingelt. Am Sperrbildschirm ist der Titel eingeklappt
 * die einzige Zeile, die vollstaendig gelesen wird, waehrend der Fliesstext gekuerzt ist. Die
 * Ueberschrift gehoert deshalb zum Ergebnis, nicht zur Renderstelle.
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
        rueckbau: RueckbauErgebnis = RueckbauErgebnis.ABGERAEUMT,
        rueckbauWirft: Throwable? = null,
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
            baueZurueck = {
                p.schritte.add("rueckbau")
                rueckbauWirft?.let { throw it }
                rueckbau
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
        // KEIN Rueckbau: es steht nichts scharf, was abzuraeumen waere. Ein Cancel auf gut Glueck
        // waere hier nicht falsch, aber die Unterscheidung ist es, die den Nutzertext ehrlich
        // haelt - "nichts scharf" darf nicht denselben Weg nehmen wie "vielleicht doch scharf".
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
    fun `scheitert das Vormerken, wird der bereits armierte Alarm abgeraeumt`() {
        // SO STAND ES HIER ZUERST (und war zu wenig): "Der Alarm waere zwar scharf, aber weder
        // abbrechbar noch reboot-fest. Lieber klingelt der Wecker weiter, als dem Nutzer einen
        // Zustand zuzusagen, den die App nicht in der Hand hat." - geprueft wurde nur
        // `FEHLGESCHLAGEN` und die Schrittfolge `plane, merke`.
        //
        // WIDERLEGT von der adversarialen Review der Pruefrunde 8: Der Test war mit dem Schaden
        // vereinbar. Der Alarm blieb scharf, waehrend BEIDE Aufrufer dem Nutzer daraufhin
        // ausdruecklich "Es ist KEIN weiterer Weckruf geplant - stelle dir bitte selbst einen"
        // sagten. Er feuert spaeter unerwartet, und weil genau der Merker fehlt, erreicht ihn
        // weder die Master-Pause noch `deleteAllAlarms` noch das Abmelden. Ein aufgegebener
        // Versuch darf nichts Scharfes hinterlassen.
        val (ergebnis, p) = laufe(pausiert = false, merkeWirft = RuntimeException("Prefs kaputt"))

        assertEquals(SnoozeErgebnis.FEHLGESCHLAGEN, ergebnis)
        assertEquals(
            "Der bereits armierte Alarm muss VOR der Fehlermeldung abgeraeumt werden - sonst " +
                "steht ein unabbrechbarer Wecker, waehrend der Nutzertext das Gegenteil sagt",
            listOf("plane", "merke", "rueckbau"),
            p.schritte
        )
    }

    @Test
    fun `misslingt auch der Rueckbau, ist das Ergebnis unklar statt falsch`() {
        // Dann steht moeglicherweise doch ein Weckruf. "KEIN weiterer Weckruf geplant" waere jetzt
        // eine Luege in die andere Richtung - der Nutzer muss die Unsicherheit erfahren.
        val (ergebnis, p) = laufe(
            pausiert = false,
            merkeWirft = RuntimeException("Prefs kaputt"),
            rueckbau = RueckbauErgebnis.MISSLUNGEN
        )

        assertEquals(SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR, ergebnis)
        assertEquals(listOf("plane", "merke", "rueckbau"), p.schritte)
        assertTrue(
            "Ein misslungener Rueckbau MUSS im Log stehen - er ist der einzige Hinweis auf einen " +
                "Wecker, den die App nicht mehr in der Hand hat",
            p.meldungen.contains(SchlummerEntscheidung.MELDUNG_RUECKBAU_FEHLER)
        )
    }

    @Test
    fun `ein bewusst behaltener Alarm wird nicht als abgeraeumt gemeldet und traegt eine eigene Zeile`() {
        // DER BEFUND DER NACHREVIEW: Der Rueckbau war zuerst fest `cancelSnooze(context, alarmId)`
        // - gebaut fuer `scheduleSnooze()`, wo der Merker-Eintrag gerade NICHT entstanden ist und
        // das darin enthaltene `forgetPendingSnooze()` deshalb ins Leere laeuft. Fuer
        // `restorePendingSnoozes()` stimmte diese Annahme nie: dort steht der Eintrag BEREITS (er
        // ist die Vorlage des Laufs). Wirft `rememberPendingSnooze()` dort - und seit der zweiten
        // Fixwelle wirft es -, cancelte der Rueckbau den soeben armierten Alarm UND loeschte den
        // vorhandenen Merkereintrag: ein schwebender Schlummer, der wiederhergestellt werden
        // sollte, war danach endgueltig weg, auch nach jedem weiteren Neustart. Vor der zweiten
        // Welle blieb er in diesem Fall armiert und klingelte.
        //
        // Der Rueckbau darf nur wegraeumen, was DERSELBE Vorgang angelegt hat - fuer den
        // Wiederherstellungslauf heisst das: nichts anfassen.
        val (ergebnis, p) = laufe(
            pausiert = false,
            merkeWirft = RuntimeException("Prefs kaputt"),
            rueckbau = RueckbauErgebnis.BEWUSST_BEHALTEN
        )

        assertEquals(
            "Der Alarm steht noch - GEPLANT waere gelogen, FEHLGESCHLAGEN ebenfalls",
            SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR,
            ergebnis
        )
        assertTrue(
            "Ein bewusst behaltener Alarm braucht seine eigene Log-Zeile",
            p.meldungen.contains(SchlummerEntscheidung.MELDUNG_ALARM_BLEIBT)
        )
        assertFalse(
            "Die Fehlerzeile behauptet 'durch nichts mehr abzuraeumen' - hier ist der Alarm ueber " +
                "den bestehenden Merker sehr wohl abbrechbar; sie schickte eine spaetere " +
                "Fehlersuche in die falsche Richtung",
            p.meldungen.contains(SchlummerEntscheidung.MELDUNG_RUECKBAU_FEHLER)
        )
    }

    @Test
    fun `die drei Rueckbau-Ausgaenge sind unterscheidbar`() {
        // Zwei davon auf denselben Wert oder dieselbe Meldung zusammenzuziehen ist genau der
        // Fehler, der den Wiederherstellungslauf den Merker gekostet hat.
        val (abgeraeumt, _) = laufe(
            pausiert = false,
            merkeWirft = RuntimeException("Prefs kaputt"),
            rueckbau = RueckbauErgebnis.ABGERAEUMT
        )
        assertEquals(
            "Nach erfolgreichem Rueckbau steht nichts Scharfes - dann darf der Nutzertext auch " +
                "sagen, dass KEIN Weckruf mehr kommt",
            SnoozeErgebnis.FEHLGESCHLAGEN,
            abgeraeumt
        )
        assertEquals(
            "Die zwei unklaren Ausgaenge teilen sich das Ergebnis, aber nicht die Log-Zeile",
            2,
            listOf(SchlummerEntscheidung.MELDUNG_RUECKBAU_FEHLER, SchlummerEntscheidung.MELDUNG_ALARM_BLEIBT)
                .distinct().size
        )
    }

    @Test
    fun `wirft der Rueckbau selbst, gilt er als misslungen`() {
        // Ein Wurf im Notausgang darf den Fehlerpfad nicht ersetzen: sonst kaeme statt einer
        // ehrlichen Meldung gar keine, und der Aufrufer bekaeme eine Exception, mit der er nichts
        // anfangen kann.
        val (ergebnis, p) = laufe(
            pausiert = false,
            merkeWirft = RuntimeException("Prefs kaputt"),
            rueckbauWirft = IllegalStateException("AlarmManager weg")
        )

        assertEquals(SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR, ergebnis)
        assertTrue(p.meldungen.contains(SchlummerEntscheidung.MELDUNG_RUECKBAU_FEHLER))
    }

    @Test
    fun `der unklare Ausgang verspricht dem Nutzer nichts`() {
        val text = SchlummerEntscheidung.hinweisText(SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR)!!

        assertTrue(
            "Der unklare Ausgang darf nicht denselben Text tragen wie der eindeutige Fehlschlag",
            text != SchlummerEntscheidung.hinweisText(SnoozeErgebnis.FEHLGESCHLAGEN)
        )
        assertFalse(
            "Der Text behauptet, es sei KEIN Weckruf geplant - genau das ist hier nicht sicher",
            text.contains("KEIN weiterer Weckruf")
        )
        assertTrue(
            "Der Text muss sagen, was zu tun ist: sich selbst einen Wecker stellen",
            text.contains("selbst")
        )
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
    fun `die Fehlschlaege sagen dem Nutzer Verschiedenes`() {
        // Sie verlangen verschiedene Antworten: bei Pause ist nichts kaputt (Pause fortsetzen),
        // beim Planungsfehler muss sich der Nutzer selbst einen Wecker stellen.
        val texte = SnoozeErgebnis.entries
            .filter { it != SnoozeErgebnis.GEPLANT }
            .map { SchlummerEntscheidung.hinweisText(it) }
        assertEquals(
            "Zwei Lagen mit demselben Text nehmen dem Nutzer die einzige Information, " +
                "die er hat: was er jetzt tun soll",
            texte.size,
            texte.distinct().size
        )
    }

    @Test
    fun `die Ueberschrift des unklaren Ausgangs behauptet nicht, es sei nichts gestellt`() {
        // OHNE DEN FIX FAELLT DAS: Es gab eine einzige Ueberschrift
        // "Kein Schlummer-Wecker gestellt", und sie stand auch ueber dem unklaren Ausgang - also
        // ueber dem Text, der bewusst nur sagt, dass es MOEGLICHERWEISE doch klingelt.
        // Am Sperrbildschirm ist die Ueberschrift eingeklappt die einzige Zeile, die vollstaendig
        // gelesen wird; sie widerlegte damit den Text, den sie ankuendigt.
        val titel = SchlummerEntscheidung.hinweisTitel(SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR)!!

        assertFalse(
            "Die Ueberschrift des unklaren Ausgangs darf nichts zusichern - weder dass ein " +
                "Weckruf steht noch dass keiner steht",
            titel == SchlummerEntscheidung.hinweisTitel(SnoozeErgebnis.FEHLGESCHLAGEN)
        )
        assertFalse(
            "\"gestellt\" in der Verneinung ist genau die Zusicherung, die hier nicht gilt",
            titel.contains("Kein", ignoreCase = true) && titel.contains("gestellt")
        )
    }

    @Test
    fun `jede Lage traegt eine eigene, kurze Ueberschrift`() {
        assertNull(
            "Ein erfolgreicher Schlummer erzeugt keine Meldung, also auch keine Ueberschrift",
            SchlummerEntscheidung.hinweisTitel(SnoozeErgebnis.GEPLANT)
        )

        val titel = SnoozeErgebnis.entries
            .filter { it != SnoozeErgebnis.GEPLANT }
            .map { ergebnis ->
                val t = SchlummerEntscheidung.hinweisTitel(ergebnis)
                assertNotNull("$ergebnis ohne Ueberschrift", t)
                assertTrue(t!!.isNotBlank())
                // Eingeklappt am Sperrbildschirm bleibt nur rund eine Zeile stehen. Eine
                // Ueberschrift, die dort abgeschnitten wird, kann ihre Aussage verdrehen.
                assertTrue(
                    "Ueberschrift zu lang fuer den eingeklappten Zustand: '$t' (${t.length})",
                    t.length <= 40
                )
                t
            }

        assertEquals(
            "Drei Lagen mit derselben Ueberschrift sind wieder der Zustand vor dem Fix",
            titel.size,
            titel.distinct().size
        )
    }

    @Test
    fun `Titel und Text kommen als ein Wert aus derselben Lage`() {
        // Die Renderstellen (Vollbild und Benachrichtigung) holen beides ueber hinweis(); zwei
        // getrennte Abfragen koennten wieder auseinanderlaufen - das WAR der Befund.
        assertNull(SchlummerEntscheidung.hinweis(SnoozeErgebnis.GEPLANT))

        SnoozeErgebnis.entries
            .filter { it != SnoozeErgebnis.GEPLANT }
            .forEach { ergebnis ->
                val meldung = SchlummerEntscheidung.hinweis(ergebnis)!!
                assertEquals(SchlummerEntscheidung.hinweisTitel(ergebnis), meldung.titel)
                assertEquals(SchlummerEntscheidung.hinweisText(ergebnis), meldung.text)
            }
    }
}
