package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Ausfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Companion.meldung
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation.MAIN_TAB_ZIELE
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.KEIN_KALENDER_AUSGEWAEHLT_TEXT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * Der Name als GANZES Wort: weder Buchstabe noch Bindestrich davor oder dahinter. Ein blosses
     * `contains` hielte den Test gruen, wenn der Bereich etwa in "Status" umbenannt wird - das
     * steckt in jedem "System-Status".
     */
    private fun nenntBereich(text: String): Boolean =
        Regex("""(?<![\p{L}-])""" + Regex.escape(bereich) + """(?![\p{L}-])""").containsMatchIn(text)

    @Test
    fun `jeder Verweis nennt den Bereich wie Schublade und Titelzeile`() {
        verweise.forEach { (wo, text) ->
            assertTrue("$wo nennt \"$bereich\" nicht: $text", nenntBereich(text))
            assertFalse("$wo sagt noch \"Status-Tab\": $text", text.contains("Status-Tab"))
        }
    }

    @Test
    fun `die Wortgrenze faellt auf ein Teilwort nicht herein`() {
        assertFalse(nenntBereich("im Ober-$bereich"))
        assertFalse(nenntBereich("im ${bereich}bereich"))
        assertTrue(nenntBereich("im $bereich unter"))
    }

    /**
     * HERGANG (Review 30.09.2026): Der Text fuer eine unlesbare Schicht-Konfiguration sagte nur
     * "hilft der System-Status weiter" - dort steht zu diesem Zustand aber nichts. Der einzige
     * Weg ist, die Logs zu schicken; Karte und Knopf muessen so heissen wie im Bildschirm.
     */
    @Test
    fun `eine unlesbare Schicht-Konfiguration schickt zu Karte und Knopf, die es gibt`() {
        val text = noShiftExplanation(NoShiftReason.SHIFT_CONFIG_NOT_LOADED)
        val statusTab = File(
            "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/ui/screens/tabs/StatusTabContent.kt"
        ).readText()

        for (beschriftung in listOf("Debug-Informationen", "Logs an Entwickler senden")) {
            assertTrue("Der Text nennt \"$beschriftung\" nicht: $text", text.contains("\"$beschriftung\""))
            assertTrue(
                "Im System-Status heisst nichts \"$beschriftung\" - der Text schickt ins Leere",
                statusTab.contains("\"$beschriftung\"")
            )
        }
    }
}
