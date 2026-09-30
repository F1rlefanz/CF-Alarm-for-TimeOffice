package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Ausfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Companion.meldung
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation.MAIN_TAB_ZIELE
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.KEIN_KALENDER_AUSGEWAEHLT_TEXT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jeder Nutzertext, der in den Status-Bereich schickt, nennt ihn so, wie er in der Schublade und
 * in der Titelzeile heisst ([MAIN_TAB_ZIELE]).
 *
 * WARUM: Bis 30.09.2026 schickten sechs Texte - darunter die Hintergrund-Benachrichtigung - in
 * den "Status-Tab". Einen Bereich dieses Namens gibt es nicht; er heisst "System-Status", und wer
 * nach dem genannten Wort sucht, findet ihn in der Schublade nicht (UI-Regel: Beschriftungen
 * wortgleich nennen). Der Test bindet die Texte an den ECHTEN Namen: benennt jemand den Bereich
 * um, faellt hier jeder alte Verweis auf.
 */
class SystemStatusVerweisTest {

    private val bereich = MAIN_TAB_ZIELE.single { it.tab == MainTab.STATUS }.titel

    private val verweise: Map<String, String> = buildMap {
        put("KALENDER_NICHT_GEFUNDEN_TEXT", KALENDER_NICHT_GEFUNDEN_TEXT)
        put("KEIN_KALENDER_AUSGEWAEHLT_TEXT", KEIN_KALENDER_AUSGEWAEHLT_TEXT)
        for (grund in listOf(
            NoShiftReason.NO_CALENDAR_SELECTED,
            NoShiftReason.CALENDAR_UNAVAILABLE,
            NoShiftReason.SHIFT_CONFIG_NOT_LOADED
        )) {
            put("noShiftExplanation($grund)", noShiftExplanation(grund))
        }
        for (ausfall in Ausfall.entries) {
            for (anzahl in listOf(1, 2)) put("meldung($ausfall, $anzahl)", meldung(ausfall, anzahl).text)
        }
    }

    @Test
    fun `jeder Verweis nennt den Bereich wie Schublade und Titelzeile`() {
        verweise.forEach { (wo, text) ->
            assertTrue("$wo nennt \"$bereich\" nicht: $text", text.contains(bereich))
            assertFalse("$wo sagt noch \"Status-Tab\": $text", text.contains("Status-Tab"))
        }
    }
}
