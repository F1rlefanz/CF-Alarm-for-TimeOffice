package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import android.util.Log
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Umbau E5 (#173): an welchen Tagen „Ohne Kalendereintrag“ wie <Typ> einen Wecker bekommt.
 * Gegliedert nach den fuenf Festlegungen des Issues.
 *
 * Fester Rahmen: jetzt = Mo 19.10.2026 04:00, Abruf bis Mo 26.10. 04:00 - in Frage kommen also
 * die Tage 19. bis 25.10. (der Tag, der den Horizont enthaelt, ist nicht gelesen).
 */
class TageOhneEintragTest {

    private val jetzt = LocalDateTime.of(2026, 10, 19, 4, 0)
    private val horizont = LocalDateTime.of(2026, 10, 26, 4, 0)
    private fun tag(t: Int): LocalDate = LocalDate.of(2026, 10, t)

    private fun typ(
        id: String,
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        weckzeit: LocalTime = LocalTime.of(5, 30),
        weckerAn: Boolean = true,
        aktiv: Boolean = true
    ) = ShiftDefinition(
        id = id, name = id, keywords = listOf(id), alarmTime = weckzeit, isEnabled = aktiv, weckerAn = weckerAn
    ).mitKategorie(kategorie)

    private val frueh = typ("F")

    private fun config(vararg typen: ShiftDefinition, wie: String? = frueh.id) =
        ShiftConfig(definitions = listOf(frueh) + typen, ohneKalendereintragWie = wie)

    private fun termin(
        id: String,
        beginn: LocalDateTime,
        ende: LocalDateTime = beginn.plusHours(8),
        ganztaegig: Boolean = false,
        kalender: String = "dienstplan"
    ) = CalendarEvent(id = id, title = id, startTime = beginn, endTime = ende, calendarId = kalender, isAllDay = ganztaegig)

    /** Ganztaegig wie aus dem Repository: 00:00 des ersten bis 23:59 des letzten Tages. */
    private fun ganztaegig(id: String, von: Int, bis: Int = von) =
        termin(id, tag(von).atStartOfDay(), tag(bis).atTime(23, 59), ganztaegig = true)

    /** Ein Fruehdienst an Tag [t] - haelt die Liste nicht-leer, ohne die uebrigen Tage zu beruehren. */
    private fun dienst(t: Int) = termin("F-$t", tag(t).atTime(6, 0))

    private fun tage(
        events: List<CalendarEvent>,
        config: ShiftConfig = config(),
        ignoriert: List<CalendarEvent> = emptyList(),
        freieTage: Set<LocalDate> = emptySet(),
        jetzt: LocalDateTime = this.jetzt,
        horizontEnde: LocalDateTime = horizont
    ): List<LocalDate> = tageOhneEintrag(events, ignoriert, config, freieTage, jetzt, horizontEnde).map { it.datum }

    private class Mitschnitt : Timber.Tree() {
        val warnungen = mutableListOf<Pair<String?, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.WARN) warnungen += tag to message
        }
    }

    private val mitschnitt = Mitschnitt()

    @Before
    fun setUp() = Timber.plant(mitschnitt)

    @After
    fun tearDown() = Timber.uproot(mitschnitt)

    // --- Positivkontrolle: ohne jeden Eintrag ausser einem Dienst sind alle anderen Tage leer ---

    @Test
    fun `jeder Tag ohne Termin bekommt die Weckzeit des Verweistyps`() {
        val ergebnis = tageOhneEintrag(listOf(dienst(21)), emptyList(), config(), emptySet(), jetzt, horizont)

        assertEquals(listOf(19, 20, 22, 23, 24, 25).map(::tag), ergebnis.map { it.datum })
        assertTrue(ergebnis.all { it.weckzeit == it.datum.atTime(5, 30) && it.typ == frueh })
    }

    // --- Punkt 1: leer heisst, KEIN Termin ueberdeckt den Tag, auch nicht teilweise ---

    @Test
    fun `P1 Nachtdienst bis 07 00 macht den Folgetag nicht leer`() {
        val nacht = termin("N", tag(20).atTime(19, 0), tag(21).atTime(7, 0))

        val ergebnis = tage(listOf(nacht))

        assertEquals(listOf(19, 22, 23, 24, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P1 ganztaegiger und mehrtaegiger Termin decken jeden ihrer Tage ab`() {
        val ergebnis = tage(listOf(ganztaegig("SEM", 20), ganztaegig("URLAUB", 22, 24)))

        assertEquals(listOf(19, 21, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P1 ein Termin, der vor heute beginnt und noch laeuft, deckt heute ab`() {
        val ergebnis = tage(listOf(termin("N", tag(18).atTime(19, 0), tag(19).atTime(7, 0))))

        assertEquals(listOf(20, 21, 22, 23, 24, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P1 ein Ende genau um Mitternacht deckt den Endtag nicht ab`() {
        val spaet = termin("S", tag(20).atTime(16, 0), tag(21).atStartOfDay())

        assertEquals(listOf(19, 21, 22, 23, 24, 25).map(::tag), tage(listOf(spaet)))
    }

    @Test
    fun `P1 ein Tag nur mit ignorierten Terminen ist leer`() {
        val tb = termin("TB", tag(20).atTime(9, 0))
        val ignoriertAmDienst = termin("TB2", tag(21).atTime(9, 0))

        val ergebnis = tage(
            listOf(tb, ignoriertAmDienst, dienst(21)),
            ignoriert = listOf(tb, ignoriertAmDienst)
        )

        // 20. hat nur Ignoriertes -> leer; 21. hat daneben einen Dienst -> nicht leer.
        assertEquals(listOf(19, 20, 22, 23, 24, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P1 ein ignorierter Termin nimmt einen gleichnamigen Termin eines anderen Kalenders nicht mit`() {
        val ignoriert = termin("X", tag(20).atTime(9, 0), kalender = "privat")
        val gleicheIdAnderswo = termin("X", tag(22).atTime(9, 0), kalender = "dienstplan")

        val ergebnis = tage(listOf(ignoriert, gleicheIdAnderswo), ignoriert = listOf(ignoriert))

        assertEquals(listOf(19, 20, 21, 23, 24, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P1 ein noch nicht eingeordneter Code ist ein Eintrag`() {
        val unbekannt = termin("XY", tag(22).atTime(8, 0))

        assertEquals(listOf(19, 20, 21, 23, 24, 25).map(::tag), tage(listOf(unbekannt)))
    }

    // --- Punkt 2: nur Tage von heute bis VOR den Horizont-Tag; leere Liste = kein Tag ---

    @Test
    fun `P2 eine leere Eventliste ergibt keinen einzigen Tag`() {
        assertEquals(emptyList<LocalDate>(), tage(emptyList()))
    }

    @Test
    fun `P2 der Tag, der den Horizont enthaelt, ist nicht dabei - der Tag davor schon`() {
        val ergebnis = tage(listOf(dienst(19)), horizontEnde = tag(23).atTime(10, 0))

        assertEquals(listOf(20, 21, 22).map(::tag), ergebnis)
    }

    @Test
    fun `P2 ein Horizont genau um Mitternacht laesst den Vortag vollstaendig gelesen`() {
        val ergebnis = tage(listOf(dienst(19)), horizontEnde = tag(23).atStartOfDay())

        assertEquals(listOf(20, 21, 22).map(::tag), ergebnis)
    }

    @Test
    fun `P2 heute ist dabei, gestern nicht`() {
        val ergebnis = tage(listOf(termin("alt", tag(17).atTime(6, 0))))

        assertEquals(tag(19), ergebnis.first())
    }

    @Test
    fun `P2 Horizont am heutigen Tag ergibt keinen Tag`() {
        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(20)), horizontEnde = jetzt.plusHours(3)))
    }

    // --- Punkt 3: Krank-Tage und freigegebene Tage bekommen keinen Wecker ---

    @Test
    fun `P3 ein freigegebener Tag bekommt keinen Wecker`() {
        val ergebnis = tage(listOf(dienst(21)), freieTage = setOf(tag(20), tag(23)))

        assertEquals(listOf(19, 22, 24, 25).map(::tag), ergebnis)
    }

    @Test
    fun `P3 ein Tag mit Krank-Termin ist kein leerer Tag`() {
        // Die Erkennung meldet ihn als Krank-Termin, nicht als ignoriert - er bleibt ein Eintrag.
        val krank = ganztaegig("K", 22)

        assertEquals(listOf(19, 20, 21, 23, 24, 25).map(::tag), tage(listOf(krank)))
    }

    // --- Punkt 4: kein Verweis, Verweis ins Leere, Typ ohne Wecker -> kein Tag ---

    @Test
    fun `P4 ohne Verweis gibt es keinen Tag und keine Warnung`() {
        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(wie = null)))
        assertEquals(emptyList<Pair<String?, String>>(), mitschnitt.warnungen)
    }

    @Test
    fun `P4 ein Verweis ins Leere ergibt keinen Tag und eine Warnung`() {
        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(wie = "geloescht")))

        assertEquals(1, mitschnitt.warnungen.size)
        assertEquals(LogTags.SHIFT, mitschnitt.warnungen.single().first)
        assertTrue(mitschnitt.warnungen.single().second.contains("geloescht"))
    }

    @Test
    fun `P4 ein Verweistyp mit Wecker aus ergibt keinen Tag`() {
        val ohneWecker = typ("FREI", ShiftKategorie.FREI, weckerAn = false)

        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(ohneWecker, wie = "FREI")))
    }

    @Test
    fun `P4 ein ausgeschalteter Verweistyp ergibt keinen Tag`() {
        val aus = typ("S", aktiv = false)

        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(aus, wie = "S")))
    }

    @Test
    fun `P4 ein Verweistyp der Kategorie Ignorieren ergibt keinen Tag`() {
        val ignorieren = typ("TB", ShiftKategorie.IGNORIEREN)

        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(ignorieren, wie = "TB")))
    }

    @Test
    fun `P4 ein Verweistyp der Kategorie Krank ergibt keinen Tag - ein Krank-Eintrag stellt nie einen Wecker`() {
        val krank = typ("K", ShiftKategorie.KRANK)

        assertEquals(emptyList<LocalDate>(), tage(listOf(dienst(21)), config = config(krank, wie = "K")))
    }

    @Test
    fun `P4 jede andere Kategorie mit Wecker an stellt den Wecker`() {
        listOf(
            ShiftKategorie.FREI, ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG, ShiftKategorie.RUFBEREITSCHAFT
        ).forEach { kategorie ->
            val ziel = typ("Z", kategorie)

            assertEquals(
                "Kategorie $kategorie",
                listOf(19, 20, 22, 23, 24, 25).map(::tag),
                tage(listOf(dienst(21)), config = config(ziel, wie = "Z"))
            )
        }
    }

    // --- Punkt 5: Weckzeit des Verweistyps an diesem Datum, ohne Rueckrechnung; Vergangenes faellt weg ---

    @Test
    fun `P5 die Weckzeit ist die des Typs am leeren Tag, auch bei einem Nachtdienst-Typ`() {
        // Ein Typ mit Weckzeit 17:00 (Nachtdienst): kein Vortag, kein Schichtbeginn - es gibt ja keinen Termin.
        val nacht = typ("N", weckzeit = LocalTime.of(17, 0))

        val ergebnis = tageOhneEintrag(listOf(dienst(21)), emptyList(), config(nacht, wie = "N"), emptySet(), jetzt, horizont)

        assertEquals(tag(19).atTime(17, 0), ergebnis.first().weckzeit)
        assertTrue(ergebnis.all { it.weckzeit.toLocalDate() == it.datum && it.typ == nacht })
    }

    @Test
    fun `P5 eine heute schon verstrichene Weckzeit faellt weg, eine spaetere bleibt`() {
        val nachDemWecker = LocalDateTime.of(2026, 10, 19, 6, 0)
        val spaet = typ("S", weckzeit = LocalTime.of(11, 0))

        assertEquals(tag(20), tage(listOf(dienst(21)), jetzt = nachDemWecker).first())
        assertEquals(tag(19), tage(listOf(dienst(21)), config = config(spaet, wie = "S"), jetzt = nachDemWecker).first())
    }

    @Test
    fun `P5 eine Weckzeit genau jetzt bleibt - wie isBefore im Sync`() {
        val genauJetzt = tag(19).atTime(5, 30)

        assertEquals(tag(19), tage(listOf(dienst(21)), jetzt = genauJetzt).first())
    }
}
