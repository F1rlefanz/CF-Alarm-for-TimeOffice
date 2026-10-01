package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Karte "Naechste Schicht" offline (Entscheidung 01.10.2026): der letzte bekannte Stand statt
 * "Keine Schicht erkannt" - aber nur, wenn der Kalender nicht erreichbar ist, und immer als alt
 * gekennzeichnet. Quelle sind die Schichtspannen (Name + Uhrzeit), nie Termininhalte.
 */
class NaechsteSchichtOfflineTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private val jetzt = LocalDateTime.of(2026, 10, 1, 15, 0)
    private val now = millis(jetzt)

    private fun span(name: String, start: LocalDateTime) = ShiftSpan(
        shiftName = name,
        startTime = millis(start),
        endTime = millis(start.plusHours(8)),
        alarmTriggerTime = millis(start.minusMinutes(45))
    )

    private val stand = LetzterSchichtStand(
        spans = listOf(
            span("Frühschicht", jetzt.minusHours(8)), // laeuft/vorbei
            span("Spätschicht", jetzt.plusDays(2)),
            span("Frühschicht", jetzt.plusDays(1))
        ),
        stand = millis(LocalDateTime.of(2026, 10, 1, 14, 47))
    )

    @Test
    fun `offline zeigt die naechste noch nicht begonnene Schicht`() {
        val schicht = offlineAngezeigteSchicht(NoShiftReason.KALENDER_NICHT_ERREICHBAR, stand, now)
        assertEquals("Frühschicht", schicht?.shiftName)
        assertEquals(millis(jetzt.plusDays(1)), schicht?.startTime)
    }

    @Test
    fun `bei jedem anderen Grund bleibt es beim bisherigen Hinweis`() {
        for (grund in NoShiftReason.entries - NoShiftReason.KALENDER_NICHT_ERREICHBAR) {
            assertNull(grund.name, offlineAngezeigteSchicht(grund, stand, now))
        }
    }

    @Test
    fun `ohne Stand oder ohne kuenftige Schicht gibt es nichts anzuzeigen`() {
        assertNull(offlineAngezeigteSchicht(NoShiftReason.KALENDER_NICHT_ERREICHBAR, null, now))
        val nurVergangenes = LetzterSchichtStand(listOf(span("Nacht", jetzt.minusDays(1))), stand.stand)
        assertNull(offlineAngezeigteSchicht(NoShiftReason.KALENDER_NICHT_ERREICHBAR, nurVergangenes, now))
    }

    @Test
    fun `der Hinweis nennt den Stand mit Bezug und sagt, dass er alt ist`() {
        val text = offlineStandHinweis(stand.stand, zone)
        assertTrue(text, text.contains("Stand des letzten Kalender-Abgleichs: 01.10.2026 14:47"))
        assertTrue(text, text.contains("nicht erreichbar"))
        assertTrue(text, text.contains("die gestellten Wecker bleiben"))
    }

    @Test
    fun `ohne bekannten Zeitpunkt wird keiner erfunden`() {
        val text = offlineStandHinweis(null, zone)
        assertTrue(text, text.startsWith("Stand eines früheren Kalender-Abgleichs."))
        assertFalse(text, text.contains("2026"))
    }

    // --- System-Status, Karte "Schicht-Erkennung" ---

    @Test
    fun `Status online unveraendert`() {
        assertEquals("3 Schichten erkannt", schichtErkennungDetails(3, false, stand, now, zone))
        assertEquals("Keine Schichten erkannt", schichtErkennungDetails(0, false, stand, now, zone))
        assertEquals("Keine Schichten erkannt", schichtErkennungDetails(0, true, null, now, zone))
    }

    @Test
    fun `Status offline nennt den letzten Stand statt keine Schichten`() {
        assertEquals(
            "Ohne Verbindung nicht prüfbar. Beim letzten Kalender-Abgleich (01.10.2026 14:47) " +
                "waren 2 kommende Schichten bekannt.",
            schichtErkennungDetails(0, true, stand, now, zone)
        )
        val eine = LetzterSchichtStand(listOf(span("Nacht", jetzt.plusDays(1))), null)
        assertEquals(
            "Ohne Verbindung nicht prüfbar. Beim letzten Kalender-Abgleich war 1 kommende Schicht bekannt.",
            schichtErkennungDetails(0, true, eine, now, zone)
        )
    }

    // --- Der Zeitpunkt wird mit dem Bestand geschrieben ---

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    @Test
    fun `replaceAll schreibt den Stand im selben Schritt, vorher ist er unbekannt`() = runTest {
        val store = ShiftSpanStore(FakePreferencesDataStore())
        assertEquals(LetzterSchichtStand(emptyList(), null), store.letzterStand().getOrThrow())

        val spans = listOf(span("Frühschicht", jetzt.plusDays(1)))
        store.replaceAll(spans, now)

        assertEquals(LetzterSchichtStand(spans, now), store.letzterStand().getOrThrow())
    }
}
