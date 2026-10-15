package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.TagFreigabeUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * #142: die reinen Teile des Zeitraum-Dialogs. Der wichtigste Test ist die Zeitzone: der
 * Material3-Picker liefert UTC-Mitternacht, und wer ueber die Systemzone umrechnet, gibt westlich
 * von UTC den VORTAG frei (der alte `DatePickerDialog` hatte genau diese Falle).
 */
class DienstFaelltAusDialogTest {

    private val montag = LocalDate.of(2026, 10, 12)
    private val freitag = LocalDate.of(2026, 10, 16)

    @Test
    fun `UTC-Mitternacht wird in Berlin, New York und Tokio derselbe Kalendertag`() {
        val utcMitternacht = inPickerMillis(freitag)
        val vorher = TimeZone.getDefault()
        try {
            listOf("Europe/Berlin", "America/New_York", "Asia/Tokyo").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, freitag, ausPickerMillis(utcMitternacht))
                assertEquals(zone, utcMitternacht, inPickerMillis(freitag))
            }
        } finally {
            TimeZone.setDefault(vorher)
        }
    }

    @Test
    fun `Hin- und Rueckweg ergeben denselben Tag`() {
        listOf(
            LocalDate.of(2026, 3, 29), // Zeitumstellung
            LocalDate.of(2026, 10, 25), // Zeitumstellung
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2028, 2, 29)
        ).forEach { tag ->
            assertEquals(tag, ausPickerMillis(inPickerMillis(tag)))
        }
    }

    @Test
    fun `Zusammenfassung fuer einen und mehrere Tage`() {
        assertEquals(
            "Mo 12.10.: kein Wecker aus dem Dienstplan, eigene Wecker bleiben.",
            zeitraumZusammenfassung(montag, null)
        )
        // Ende = Beginn ist derselbe eine Tag.
        assertEquals(zeitraumZusammenfassung(montag, null), zeitraumZusammenfassung(montag, montag))
        assertEquals(
            "Mo 12.10. – Fr 16.10. (5 Tage): kein Wecker aus dem Dienstplan, eigene Wecker bleiben.",
            zeitraumZusammenfassung(montag, freitag)
        )
    }

    @Test
    fun `ohne Beginn sagt die Zeile, was zu tun ist, und Freigeben ist gesperrt`() {
        assertFalse(zeitraumGueltig(null, null))
        assertEquals(
            "Wähle den ersten Tag, bei mehreren Tagen auch den letzten.",
            zeitraumZusammenfassung(null, null)
        )
        // Ende vor Beginn waere ein Zeitraum, den der UseCase abweist.
        assertFalse(zeitraumGueltig(freitag, montag))
    }

    @Test
    fun `mehr als 31 Tage sind ungueltig`() {
        val grenze = TagFreigabeUseCase.MAX_ZEITRAUM_TAGE.toLong()
        // Dieselbe Zaehlung wie der UseCase: Beginn und Ende zaehlen beide mit.
        assertTrue(zeitraumGueltig(montag, montag.plusDays(grenze - 1)))
        assertFalse(zeitraumGueltig(montag, montag.plusDays(grenze)))
        assertEquals("Höchstens 31 Tage auf einmal.", zeitraumZusammenfassung(montag, montag.plusDays(grenze)))
        assertTrue(zeitraumGueltig(montag, null))
    }
}
