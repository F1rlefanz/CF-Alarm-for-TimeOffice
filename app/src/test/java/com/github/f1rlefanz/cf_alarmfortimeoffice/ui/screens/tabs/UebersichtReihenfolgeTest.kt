package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * #141: Die Uebersicht ordnet "Nächste Schicht" und "Nächster Wecker" nach Zeit - was zuerst
 * kommt, steht oben. Fehlt ein Zeitpunkt, bleibt die Schicht oben; darauf stuetzen sich zwei
 * Texte der Schicht-Karte ("die Karte darunter").
 */
class UebersichtReihenfolgeTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private fun millis(tag: Int, stunde: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, tag, stunde, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `der Wecker vor seiner Fruehschicht steht oben`() {
        assertEquals(
            UebersichtReihenfolge.WECKER_ZUERST,
            uebersichtReihenfolge(schichtBeginn = millis(17, 6), weckerZeitpunkt = millis(17, 5, 30))
        )
    }

    @Test
    fun `ein stiller Abrufdienst vor dem naechsten Wecker steht oben`() {
        // Der Anlass: Abrufdienst Fr 16.10. ab 00:00, naechster Wecker erst Sa 17.10. 05:30.
        assertEquals(
            UebersichtReihenfolge.SCHICHT_ZUERST,
            uebersichtReihenfolge(schichtBeginn = millis(16, 0), weckerZeitpunkt = millis(17, 5, 30))
        )
    }

    @Test
    fun `bei gleichem Zeitpunkt gewinnt der Wecker`() {
        val t = millis(17, 6)
        assertEquals(UebersichtReihenfolge.WECKER_ZUERST, uebersichtReihenfolge(t, t))
    }

    @Test
    fun `ohne Weckzeit bleibt die Schicht oben`() {
        // Pause, kein klingelnder Wecker, Laden: die Wecker-Karte nennt keinen Zeitpunkt.
        assertEquals(UebersichtReihenfolge.SCHICHT_ZUERST, uebersichtReihenfolge(millis(17, 6), null))
    }

    @Test
    fun `ohne Schicht bleibt die Schicht oben`() {
        // "Keine Schicht erkannt" / Zugriff abgelaufen: der Text verweist auf die Karte darunter.
        assertEquals(UebersichtReihenfolge.SCHICHT_ZUERST, uebersichtReihenfolge(null, millis(17, 5, 30)))
        assertEquals(UebersichtReihenfolge.SCHICHT_ZUERST, uebersichtReihenfolge(null, null))
    }

    @Test
    fun `der Schichtbeginn folgt den Zweigen der Karte`() {
        val erkannt = millis(17, 6)
        val offline = millis(18, 6)
        assertEquals(erkannt, angezeigterSchichtBeginn(erkannt, laedt = true, offlineSchicht = offline))
        // Waehrend des Ladens zeigt die Karte "Wird geladen …" - keine Schicht, kein Zeitpunkt.
        assertNull(angezeigterSchichtBeginn(null, laedt = true, offlineSchicht = offline))
        assertEquals(offline, angezeigterSchichtBeginn(null, laedt = false, offlineSchicht = offline))
        assertNull(angezeigterSchichtBeginn(null, laedt = false, offlineSchicht = null))
    }
}
