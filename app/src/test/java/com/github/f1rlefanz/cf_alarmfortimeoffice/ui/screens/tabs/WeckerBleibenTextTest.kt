package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Die gestellten Wecker bleiben" darf nur dastehen, wenn welche gestellt sind - bei Master-Pause
 * und ausgeschalteter Automatik raeumt `syncAlarms()` sie (am Emulator gesehen: der Satz stand
 * direkt neben "Alles ist pausiert — es wird kein Wecker gestellt").
 */
class WeckerBleibenTextTest {

    @Test
    fun `nur ohne Pause und mit Automatik bleiben Wecker`() {
        assertTrue(gestellteWeckerBleiben(masterPausePaused = false, autoAlarmEnabled = true))
        assertFalse(gestellteWeckerBleiben(masterPausePaused = true, autoAlarmEnabled = true))
        assertFalse(gestellteWeckerBleiben(masterPausePaused = false, autoAlarmEnabled = false))
    }

    @Test
    fun `offline ohne Pause sagt die Erklaerung, dass die Wecker bleiben`() {
        val text = noShiftExplanation(NoShiftReason.KALENDER_NICHT_ERREICHBAR)
        assertTrue(text, text.contains("die gestellten Wecker bleiben"))
        assertTrue(text, text.contains("\"Mit Google Kalender abgleichen\""))
    }

    @Test
    fun `offline bei Pause widerspricht die Erklaerung dem Pausenhinweis nicht`() {
        val text = noShiftExplanation(NoShiftReason.KALENDER_NICHT_ERREICHBAR, masterPausePaused = true)
        assertFalse(text, text.contains("Wecker bleiben"))
        assertTrue(text, text.endsWith(NO_SHIFT_HINWEIS_PAUSIERT))
    }

    @Test
    fun `offline bei Automatik aus ebenso`() {
        val text = noShiftExplanation(NoShiftReason.KALENDER_NICHT_ERREICHBAR, autoAlarmEnabled = false)
        assertFalse(text, text.contains("Wecker bleiben"))
        assertTrue(text, text.contains("Automatische Alarme sind derzeit ausgeschaltet"))
    }
}
