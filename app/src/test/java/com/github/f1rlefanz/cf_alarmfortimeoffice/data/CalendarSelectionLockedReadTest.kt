package com.github.f1rlefanz.cf_alarmfortimeoffice.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wann darf ein Read der Kalenderauswahl als Wahrheit in den StateFlow?
 *
 * WAS HIER ABGESICHERT WIRD (Befund 18.08.2026):
 * Der directBootAware BootReceiver injiziert das `CalendarSelectionRepository`, dessen `init{}`
 * sofort den CE-DataStore beobachtet. Ein Read dort VOR der ersten Entsperrung wirft NICHT - er
 * liefert still leere Preferences. Das `retryWhen`, das ausdruecklich gegen Direct Boot gebaut
 * war, feuerte deshalb nie; `_selectedCalendarIds` stand auf `emptySet()`, und weil DataStore das
 * leere Ergebnis fuer die restliche PROZESSLAUFZEIT cacht, blieb es dabei - jeder Wartungslauf
 * brach mit "No calendars selected" ab, ohne Fehler und ohne Signal.
 *
 * Die Regel dagegen: eine LEERE Auswahl ist nur dann eine Aussage, wenn der Storage lesbar war.
 */
class CalendarSelectionLockedReadTest {

    /** DER REGRESSIONSFALL: leer + gesperrt ist keine Auswahl, sondern keine Information. */
    @Test
    fun `leere Auswahl aus gesperrtem Storage wird nicht uebernommen`() {
        assertFalse(shouldAcceptSelectionRead(userUnlocked = false, ids = emptySet()))
    }

    /** Bei entsperrtem Storage ist "leer" eine echte Aussage (der Nutzer hat nichts gewaehlt). */
    @Test
    fun `leere Auswahl aus entsperrtem Storage ist eine Aussage`() {
        assertTrue(shouldAcceptSelectionRead(userUnlocked = true, ids = emptySet()))
    }

    /**
     * Nicht leer schlaegt die Sperr-Vermutung: solche Daten koennen aus einem unlesbaren Store
     * gar nicht stammen. So bleibt die Regel richtig, auch wenn der `UserManager` sich irrt -
     * sonst wuerde eine vorhandene Auswahl grundlos verworfen.
     */
    @Test
    fun `befuellte Auswahl wird auch bei gemeldeter Sperre uebernommen`() {
        assertTrue(shouldAcceptSelectionRead(userUnlocked = false, ids = setOf("kalender-1")))
        assertTrue(shouldAcceptSelectionRead(userUnlocked = true, ids = setOf("kalender-1")))
    }
}
