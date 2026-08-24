package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reine Logik des Freigabe-Speichers: Aufraeumgrenze, Datums-Anker und der Spannen-Filter.
 * Der DataStore-Wrapper selbst wird nach Projektkonvention nicht getestet.
 */
class FreieTageStoreTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val heute = LocalDate.of(2026, 8, 24)

    private fun spanneAm(datum: LocalDate, weckStunde: Int = 5, name: String = "Fruehschicht"): ShiftSpan {
        val weckzeit = datum.atTime(weckStunde, 30).atZone(berlin).toInstant().toEpochMilli()
        return ShiftSpan(
            shiftName = name,
            startTime = weckzeit + 30 * 60_000L,
            endTime = weckzeit + 8 * 60 * 60_000L,
            alarmTriggerTime = weckzeit
        )
    }

    // --- Aufraeumen ---

    @Test
    fun `Der VORTAG bleibt erhalten - eine ueber Mitternacht laufende Nacht darf nicht zurueckkippen`() {
        // LOOKBACK_DAYS = 1: die Fenster-Schleifen von Dimmer und DND rechnen den Vortag mit.
        // Wuerde die Freigabe um 00:00 verschwinden, ginge mitten in der Nacht "Nicht stoeren"
        // wieder an und der Bildschirm wuerde gedimmt - im Schlaf, ohne jedes Zutun.
        val behalten = FreieTageStore.prune(setOf(heute.minusDays(1).toString()), heute)
        assertEquals(setOf(heute.minusDays(1).toString()), behalten)
    }

    @Test
    fun `Aeltere Freigaben fliegen raus`() {
        val ergebnis = FreieTageStore.prune(
            setOf(
                heute.minusDays(2).toString(),
                heute.minusDays(1).toString(),
                heute.toString(),
                heute.plusDays(3).toString()
            ),
            heute
        )
        assertEquals(
            setOf(heute.minusDays(1).toString(), heute.toString(), heute.plusDays(3).toString()),
            ergebnis
        )
    }

    @Test
    fun `Ein unlesbarer Eintrag gilt NICHT als freigegebener Tag`() {
        // Ein Wert, den niemand deuten kann, darf nicht dazu fuehren, dass irgendetwas
        // unterdrueckt wird - die Richtung ist immer "im Zweifel wecken".
        assertEquals(emptySet<String>(), FreieTageStore.prune(setOf("kein-datum", ""), heute))
        assertNull(FreieTageStore.parseOderNull("2026-13-45"))
    }

    // --- Tages-Anker ---

    @Test
    fun `Der Tag einer Spanne kommt aus der WECKZEIT, nicht aus dem Schichtbeginn`() {
        // Spaetdienst: Wecker am 24.08. um 23,30 Uhr, Dienstbeginn erst am 25.08. um 00,00 Uhr.
        // Der Anker MUSS der 24. sein - dieselbe Ableitung wie DimWindowResolver.slotsByDate und
        // AlarmUseCase.istTagFreigegeben. Drei Stellen, ein Anker.
        val weckzeit = heute.atTime(23, 30).atZone(berlin).toInstant().toEpochMilli()
        val spanne = ShiftSpan(
            shiftName = "Nachtdienst",
            startTime = heute.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli(),
            endTime = heute.plusDays(1).atTime(8, 0).atZone(berlin).toInstant().toEpochMilli(),
            alarmTriggerTime = weckzeit
        )
        assertEquals(heute, FreieTageStore.tagVon(spanne, berlin))
    }

    // --- Spannen-Filter ---

    @Test
    fun `Ein freigegebener Tag verliert seine Schichtspanne`() {
        val spannen = listOf(spanneAm(heute), spanneAm(heute.plusDays(1)))
        val uebrig = FreieTageStore.filtereSpannen(spannen, setOf(heute), berlin)
        assertEquals(1, uebrig.size)
        assertEquals(heute.plusDays(1), FreieTageStore.tagVon(uebrig.first(), berlin))
    }

    @Test
    fun `BEIDE Schichten eines freigegebenen Tages fallen weg`() {
        // Ein Kalendertag kann zwei Schichten haben; "Tag freigeben" meint den TAG, nicht eine
        // einzelne Schicht.
        val spannen = listOf(
            spanneAm(heute, weckStunde = 5, name = "Fruehschicht"),
            spanneAm(heute, weckStunde = 13, name = "Spaetschicht")
        )
        assertTrue(FreieTageStore.filtereSpannen(spannen, setOf(heute), berlin).isEmpty())
    }

    @Test
    fun `Ohne Freigaben bleibt die Liste unveraendert`() {
        val spannen = listOf(spanneAm(heute), spanneAm(heute.plusDays(1)))
        assertEquals(spannen, FreieTageStore.filtereSpannen(spannen, emptySet(), berlin))
    }

    @Test
    fun `Die Zeitzone entscheidet ueber den Tag`() {
        // Wecker um 00,30 Uhr Berliner Zeit = 22,30 Uhr des Vortages in UTC. Wer die Zone
        // ignoriert, gibt den falschen Tag frei.
        val weckzeit = heute.atTime(0, 30).atZone(berlin).toInstant().toEpochMilli()
        val spanne = ShiftSpan("Nachtdienst", weckzeit, weckzeit + 8 * 60 * 60_000L, weckzeit)
        assertEquals(heute, FreieTageStore.tagVon(spanne, berlin))
        assertEquals(heute.minusDays(1), FreieTageStore.tagVon(spanne, ZoneId.of("UTC")))
        assertFalse(FreieTageStore.filtereSpannen(listOf(spanne), setOf(heute), ZoneId.of("UTC")).isEmpty())
    }
}
