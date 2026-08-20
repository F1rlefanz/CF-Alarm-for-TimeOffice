package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.anzeigeZeilen
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.sammle
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.titelFuer
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.zeileEntfernt
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.zeileGeaendert
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier.Companion.zeileNeu
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruefrunde 8, Befund 5: mehrere Schichtaenderungen eines Sync-Laufs teilten sich eine
 * Notification-ID (2202) - `notify()` ERSETZT die stehende Meldung, also sah der Nutzer von n
 * Aenderungen genau eine, quer ueber die Arten hinweg ("Neue Schicht erkannt" loeschte "Schicht
 * entfernt").
 *
 * WARUM DAS GETESTET GEHOERT: Diese Meldung ist der einzige Weg, auf dem der Nutzer erfaehrt, dass
 * die App eine TimeOffice-Aenderung mitbekommen hat - und der Anlass, bei dem er sie wirklich
 * braucht, ist gerade NICHT die einzelne Schicht, sondern der Block (ein Krankheitsfall verschiebt
 * drei Dienste, ein Wochenende faellt weg). Drei gestrichene Dienste sind drei geloeschte Wecker;
 * von zweien erfuhr er nie. Die verdraengten Meldungen landeten in keinem Verlauf.
 *
 * Geprueft wird die reine Sammel-Logik ([sammle], [titelFuer], [anzeigeZeilen]) - sie traegt die
 * Zusicherung "der Nutzer sieht ALLE Aenderungen oder eine wahrheitsgemaesse Zusammenfassung".
 * Das Posten selbst (NotificationManager) ist reines Android-Rahmenwerk und hier nicht testbar.
 */
class Pruefrunde8SchichtaenderungSammlungTest {

    private val t0 = 1_700_000_000_000L

    private fun alarm(name: String, zeit: Long = t0) = AlarmInfo(
        id = 1,
        shiftId = "s",
        shiftName = name,
        triggerTime = zeit,
        formattedTime = "irrelevant"
    )

    // --- Der Kern des Befunds: nichts geht mehr verloren ---

    @Test
    fun `sechs Aenderungen eines Sync-Laufs ergeben eine Meldung, die alle sechs kennt`() {
        // Genau der Ablauf aus dem Befund: die Loeschschleife meldet drei Streichungen, danach
        // meldet die Erstell-/Aenderungsschleife eine Aenderung und zwei neue Schichten. Vor dem
        // Fix stand am Ende EINE Meldung mit dem zuletzt verarbeiteten Fall da.
        val zeilen = listOf(
            "Entfernt: Frueh, Wecker gelöscht",
            "Entfernt: Spaet, Wecker gelöscht",
            "Entfernt: Nacht, Wecker gelöscht",
            "Geändert: Frueh 05:00 → 05:30, Wecker angepasst",
            "Neu: AD1 ab 12:30, Wecker gestellt",
            "Neu: AD2 ab 13:00, Wecker gestellt"
        )

        var sammlung: ShiftChangeNotifier.Sammlung? = null
        zeilen.forEachIndexed { i, zeile ->
            // Millisekunden-Abstand: ein Sync-Lauf haelt den alarmSyncMutex.
            sammlung = sammle(sammlung, t0 + i, zeile)
        }

        val ergebnis = sammlung!!
        assertEquals("Alle sechs Aenderungen muessen gezaehlt sein", 6, ergebnis.gesamt)
        assertEquals(
            "Bei sechs Zeilen (= MAX_ZEILEN) darf nichts gekappt werden",
            zeilen,
            ergebnis.zeilen
        )
        assertEquals(
            "Der Titel muss die Sammelaenderung als solche ausweisen",
            "6 Dienstplan-Änderungen",
            titelFuer(ergebnis.gesamt, "Neue Schicht erkannt")
        )
        assertEquals(
            "Ohne Ueberlauf steht die Liste unveraendert da",
            zeilen,
            anzeigeZeilen(ergebnis.zeilen, ergebnis.gesamt)
        )
    }

    @Test
    fun `eine Loeschung wird von einer spaeteren Neuanlage nicht mehr verdraengt`() {
        // Der ausdruecklich benannte Fall: quer ueber die Meldungsarten hinweg.
        val nachLoeschung = sammle(null, t0, zeileEntfernt(alarm("Nachtdienst")))
        val nachNeuanlage = sammle(nachLoeschung, t0 + 5, zeileNeu(alarm("Fruehdienst")))

        assertEquals(2, nachNeuanlage.gesamt)
        assertTrue(
            "Die Streichung muss in der Meldung stehen bleiben",
            nachNeuanlage.zeilen.any { it.contains("Nachtdienst") }
        )
        assertTrue(
            "Die neue Schicht ebenso",
            nachNeuanlage.zeilen.any { it.contains("Fruehdienst") }
        )
    }

    // --- Wahrheitsgehalt bei sehr vielen Aenderungen ---

    @Test
    fun `bei mehr Aenderungen als Zeilen wird der Rest beziffert, nicht verschwiegen`() {
        var sammlung: ShiftChangeNotifier.Sammlung? = null
        repeat(9) { i -> sammlung = sammle(sammlung, t0 + i, "Aenderung $i") }
        val ergebnis = sammlung!!

        assertEquals("Der Zaehler kennt alle neun", 9, ergebnis.gesamt)
        assertEquals(
            "Mitgefuehrt werden nur MAX_ZEILEN Zeilen",
            ShiftChangeNotifier.MAX_ZEILEN,
            ergebnis.zeilen.size
        )
        assertEquals(
            "Und zwar die juengsten - die sind fuer den Nutzer die naechstliegenden",
            listOf("Aenderung 3", "Aenderung 4", "Aenderung 5", "Aenderung 6", "Aenderung 7", "Aenderung 8"),
            ergebnis.zeilen
        )

        val angezeigt = anzeigeZeilen(ergebnis.zeilen, ergebnis.gesamt)
        assertEquals(
            "Die verdeckten drei stehen als Zahl obenan - die Meldung darf nie weniger behaupten, " +
                "als tatsaechlich passiert ist",
            "… und 3 weitere Änderungen",
            angezeigt.first()
        )
        assertEquals(7, angezeigt.size)
    }

    @Test
    fun `ein zu kleiner Zaehler aus den Extras darf die Zeilen nicht untertreiben`() {
        // Die Sammlung reist in den Notification-Extras mit (Prozesstod-Wiederaufnahme). Kommt von
        // dort ein Zaehler, der kleiner ist als die Zahl der mitgereisten Zeilen, gilt die
        // groessere, belegbare Zahl - untertreiben waere dieselbe Luege wie das alte Ueberschreiben.
        val kaputt = ShiftChangeNotifier.Sammlung(
            zeilen = listOf("a", "b", "c"),
            gesamt = 1,
            beginn = t0,
            begonnenNeu = false
        )

        val ergebnis = sammle(kaputt, t0 + 10, "d")

        assertEquals(4, ergebnis.gesamt)
    }

    // --- Wann eine neue Sammlung beginnt ---

    @Test
    fun `ohne stehende Sammlung beginnt eine neue`() {
        val ergebnis = sammle(null, t0, "Neu: Frueh ab 05:00, Wecker gestellt")

        assertEquals(1, ergebnis.gesamt)
        assertEquals(t0, ergebnis.beginn)
        assertTrue("Eine frische Sammlung muss hoerbar sein duerfen", ergebnis.begonnenNeu)
        assertEquals(
            "Die Einzelmeldung behaelt ihren konkreten Titel",
            "Neue Schicht erkannt",
            titelFuer(ergebnis.gesamt, "Neue Schicht erkannt")
        )
    }

    @Test
    fun `innerhalb des Sammelfensters wird angehaengt und nicht erneut alarmiert`() {
        val erste = sammle(null, t0, "eins")
        val zweite = sammle(erste, t0 + (ShiftChangeNotifier.SAMMEL_FENSTER_MINUTEN - 1) * 60_000L, "zwei")

        assertEquals(2, zweite.gesamt)
        assertEquals("Der Beginn bleibt der der Sammlung", t0, zweite.beginn)
        assertFalse(
            "Sonst naehme die App die stehende Meldung weg und alarmierte mitten im Sync erneut",
            zweite.begonnenNeu
        )
    }

    @Test
    fun `nach dem Sammelfenster beginnt eine neue, hoerbare Sammlung`() {
        // Der regulaere Wartungslauf kommt erst in sechs Stunden wieder. Eine dann eintreffende,
        // eigenstaendige Dienstplan-Aenderung darf nicht still an eine alte Meldung anwachsen -
        // sonst haette der Nutzer eine Aenderung, die ihn nie erreicht hat.
        val alt = sammle(null, t0, "eins")
        val neu = sammle(alt, t0 + 6 * 60 * 60_000L, "zwei")

        assertEquals("Die alte Sammlung ist abgeschlossen", 1, neu.gesamt)
        assertEquals(listOf("zwei"), neu.zeilen)
        assertTrue("Und die neue muss wieder alarmieren duerfen", neu.begonnenNeu)
    }

    @Test
    fun `eine zurueckgestellte Uhr laesst die Sammlung nicht ewig offen`() {
        // Zeitumstellung/Zeitzonenwechsel: ein Beginn in der Zukunft wuerde das Fenster sonst nie
        // ablaufen lassen, und jede weitere Aenderung bliebe stumm.
        val ausDerZukunft = ShiftChangeNotifier.Sammlung(
            zeilen = listOf("eins"),
            gesamt = 1,
            beginn = t0 + 60 * 60_000L,
            begonnenNeu = false
        )

        val ergebnis = sammle(ausDerZukunft, t0, "zwei")

        assertTrue(ergebnis.begonnenNeu)
        assertEquals(t0, ergebnis.beginn)
    }

    // --- Die Zeilen benennen Art und Schicht ---

    @Test
    fun `jede Zeile nennt Art und Schichtnamen, damit die Liste lesbar bleibt`() {
        // In der Sammelliste fehlt der Titel als Kontext - ohne Praefix stuende dort dreimal
        // dasselbe Muster, ohne dass erkennbar waere, was gestrichen und was neu ist.
        assertTrue(zeileNeu(alarm("Fruehdienst")).startsWith("Neu: "))
        assertTrue(zeileNeu(alarm("Fruehdienst")).contains("Fruehdienst"))

        assertTrue(zeileEntfernt(alarm("Nachtdienst")).startsWith("Entfernt: "))
        assertTrue(zeileEntfernt(alarm("Nachtdienst")).contains("Nachtdienst"))

        val umbenannt = zeileGeaendert(alarm("Frueh"), alarm("AD1"))
        assertTrue(umbenannt.startsWith("Geändert: "))
        assertTrue("Bei einer Umbenennung muessen beide Namen erscheinen", umbenannt.contains("Frueh"))
        assertTrue(umbenannt.contains("AD1"))

        val verschoben = zeileGeaendert(alarm("Frueh", t0), alarm("Frueh", t0 + 30 * 60_000L))
        assertTrue(verschoben.startsWith("Geändert: "))
        assertTrue(verschoben.contains("Frueh"))
    }
}
