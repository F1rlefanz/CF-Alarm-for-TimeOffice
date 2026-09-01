package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation

import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt die Ein-Quellen-Liste der Hauptbereiche vollstaendig.
 *
 * WARUM ES DIESEN TEST BRAUCHT: Bis v1.37.3 lagen Reihenfolge, Symbol und Beschriftung der
 * Bereiche in der unteren Navigationsleiste, und jeder Tab-Inhalt setzte seine Ueberschrift
 * zusaetzlich selbst - drei Quellen, von Hand parallel gehalten, und sie wichen tatsaechlich
 * voneinander ab ("Home" gegen "Uebersicht", "Dimmen" gegen "Schicht-Dimmer"). Seit dem Umbau
 * auf die Navigationsschublade gibt es nur noch [MAIN_TAB_ZIELE].
 *
 * Der eigentliche Schutz ist die Vollstaendigkeit: Wer einen siebten Bereich zum Enum
 * [MainTab] hinzufuegt und die Liste vergisst, baut ein Ziel, das der Nutzer NICHT erreichen
 * kann - die Schublade ist der einzige Weg dorthin. Der Compiler sagt dazu nichts, weil die
 * Liste ein gewoehnliches `listOf` ist. Dieser Test sagt es.
 */
class MainTabZieleTest {

    @Test
    fun `jeder Bereich des Enums kommt in der Liste vor`() {
        assertEquals(
            "Diese Bereiche fehlen in MAIN_TAB_ZIELE und waeren damit unerreichbar",
            emptySet<MainTab>(),
            MainTab.entries.toSet() - MAIN_TAB_ZIELE.map { it.tab }.toSet()
        )
    }

    @Test
    fun `kein Bereich kommt doppelt vor`() {
        assertEquals(
            "MAIN_TAB_ZIELE enthaelt einen Bereich mehrfach - die Schublade zeigte ihn zweimal",
            MAIN_TAB_ZIELE.size,
            MAIN_TAB_ZIELE.map { it.tab }.toSet().size
        )
    }

    @Test
    fun `die Liste hat genau so viele Eintraege wie das Enum`() {
        assertEquals(MainTab.entries.size, MAIN_TAB_ZIELE.size)
    }

    @Test
    fun `jeder Titel ist gesetzt und einmalig`() {
        // Ein leerer Titel liefe auf eine Kopfzeile ohne Namen hinaus; zwei gleiche Titel
        // machten zwei Bereiche in der Schublade ununterscheidbar.
        assertTrue(
            "Mindestens ein Titel ist leer",
            MAIN_TAB_ZIELE.all { it.titel.isNotBlank() }
        )
        assertEquals(
            "Zwei Bereiche tragen denselben Titel",
            MAIN_TAB_ZIELE.size,
            MAIN_TAB_ZIELE.map { it.titel }.toSet().size
        )
    }

    @Test
    fun `mainTabZiel liefert zu jedem Bereich einen Eintrag`() {
        MainTab.entries.forEach { tab ->
            assertEquals(tab, mainTabZiel(tab).tab)
        }
    }
}
