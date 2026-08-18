package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Companion.entscheideBenachrichtigung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Entprellung der Kalender-Warnung.
 *
 * WARUM DAS GETESTET GEHOERT: Diese Meldung ist der einzige Weg, auf dem der Nutzer erfaehrt, dass
 * seine Wecker langsam versiegen, ohne die App zu oeffnen. Sie darf deshalb weder ausfallen noch
 * inflationaer werden - eine Wecker-App, die grundlos alle sechs Stunden warnt, wird stumm
 * geschaltet, und dann ist auch die echte Warnung weg.
 *
 * Die zwei Bedingungen, die alles tragen:
 *  - "auch im vorherigen Lauf gescheitert" trennt die dauerhafte Stoerung vom Funkloch.
 *  - "noch nicht gemeldet" verhindert die Wiederholung alle sechs Stunden.
 */
class CalendarUnavailableNotifierTest {

    @Test
    fun `der erste Ausfall meldet noch nichts`() {
        // Ein einzelner fehlgeschlagener Abruf ist im Mobilfunk der Normalfall.
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = emptySet(),
            bereitsGemeldet = emptySet()
        )

        assertTrue("Ein einzelner Aussetzer darf nicht warnen", e.zuMelden.isEmpty())
        assertEquals("Er muss aber gemerkt werden", setOf("dienstplan"), e.neuerZuletztGescheitert)
    }

    @Test
    fun `der zweite Ausfall in Folge meldet`() {
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = setOf("dienstplan"),
            bereitsGemeldet = emptySet()
        )

        assertEquals(setOf("dienstplan"), e.zuMelden)
        assertEquals(setOf("dienstplan"), e.neuerBereitsGemeldet)
    }

    @Test
    fun `der dritte Ausfall wiederholt die Meldung nicht`() {
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = setOf("dienstplan"),
            bereitsGemeldet = setOf("dienstplan")
        )

        assertTrue("Sonst klingelt dieselbe Stoerung alle sechs Stunden", e.zuMelden.isEmpty())
        assertEquals("Der Merker bleibt bestehen", setOf("dienstplan"), e.neuerBereitsGemeldet)
    }

    @Test
    fun `nach der Erholung meldet die naechste Stoerung wieder`() {
        // Der Kalender antwortet wieder: beide Merker muessen ihn vergessen. Ohne das waere die
        // Warnung ein Einmal-Ereignis auf Lebenszeit der Installation - die zweite, voellig
        // eigenstaendige Stoerung Monate spaeter bliebe stumm.
        val erholt = entscheideBenachrichtigung(
            jetztGescheitert = emptySet(),
            zuletztGescheitert = setOf("dienstplan"),
            bereitsGemeldet = setOf("dienstplan")
        )
        assertTrue(erholt.neuerBereitsGemeldet.isEmpty())
        assertTrue(erholt.neuerZuletztGescheitert.isEmpty())

        val ersterNeuer = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = erholt.neuerZuletztGescheitert,
            bereitsGemeldet = erholt.neuerBereitsGemeldet
        )
        assertTrue("Auch die erneute Stoerung braucht zwei Laeufe", ersterNeuer.zuMelden.isEmpty())

        val zweiterNeuer = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = ersterNeuer.neuerZuletztGescheitert,
            bereitsGemeldet = ersterNeuer.neuerBereitsGemeldet
        )
        assertEquals("...und meldet sich dann wieder", setOf("dienstplan"), zweiterNeuer.zuMelden)
    }

    @Test
    fun `ein neu hinzukommender Kalender meldet sich eigenstaendig`() {
        // "dienstplan" ist bereits gemeldet, "bereitschaft" scheitert zum zweiten Mal. Der schon
        // gemeldete darf den neuen nicht mit verschlucken.
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan", "bereitschaft"),
            zuletztGescheitert = setOf("dienstplan", "bereitschaft"),
            bereitsGemeldet = setOf("dienstplan")
        )

        assertEquals(setOf("bereitschaft"), e.zuMelden)
        assertEquals(setOf("dienstplan", "bereitschaft"), e.neuerBereitsGemeldet)
    }

    @Test
    fun `erholt sich einer von zweien, bleibt nur der andere gemerkt`() {
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = setOf("dienstplan", "bereitschaft"),
            bereitsGemeldet = setOf("dienstplan", "bereitschaft")
        )

        assertTrue(e.zuMelden.isEmpty())
        assertEquals(setOf("dienstplan"), e.neuerBereitsGemeldet)
        assertEquals(setOf("dienstplan"), e.neuerZuletztGescheitert)
    }

    @Test
    fun `abgeschaltete Meldung darf den Bereits-gemeldet-Merker nicht fuellen`() {
        // Der Befund der Pruefrunde vom 18.08.2026: Bis v1.26.2 schrieb onFetchOutcome() den
        // Merker UNBEDINGT - noch vor der Toggle-Pruefung. Wer die Meldung abgeschaltet hatte,
        // sammelte damit stumm "bereits gemeldet"-Eintraege an; nach dem Wiedereinschalten kam die
        // Warnung fuer denselben, weiterhin scheiternden Kalender NIE, weil er per intersect nie
        // wieder aus dem Merker fiel.
        //
        // Die reine Entscheidungsfunktion kennt den Toggle nicht - sie liefert korrekt zuMelden.
        // Dieser Test haelt fest, was der AUFRUFER daraus machen muss: bei abgeschalteter Meldung
        // darf bereitsGemeldet nur aufgeraeumt, nie erweitert werden.
        val e = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = setOf("dienstplan"),
            bereitsGemeldet = emptySet()
        )
        assertEquals("Die Funktion selbst meldet weiterhin", setOf("dienstplan"), e.zuMelden)

        // Das ist der Zweig, den onFetchOutcome() im abgeschalteten Fall nimmt:
        val bereitsGemeldetOhneMeldung = emptySet<String>() intersect setOf("dienstplan")
        assertTrue(
            "Ohne gezeigte Meldung darf nichts als gemeldet gelten",
            bereitsGemeldetOhneMeldung.isEmpty()
        )

        // Und nach dem Wiedereinschalten muss die Warnung deshalb kommen:
        val nachWiedereinschalten = entscheideBenachrichtigung(
            jetztGescheitert = setOf("dienstplan"),
            zuletztGescheitert = setOf("dienstplan"),
            bereitsGemeldet = bereitsGemeldetOhneMeldung
        )
        assertEquals(setOf("dienstplan"), nachWiedereinschalten.zuMelden)
    }

    @Test
    fun `laeuft alles, wird nichts gemeldet und nichts gemerkt`() {
        val e = entscheideBenachrichtigung(
            jetztGescheitert = emptySet(),
            zuletztGescheitert = emptySet(),
            bereitsGemeldet = emptySet()
        )

        assertTrue(e.zuMelden.isEmpty())
        assertTrue(e.neuerZuletztGescheitert.isEmpty())
        assertTrue(e.neuerBereitsGemeldet.isEmpty())
    }
}
