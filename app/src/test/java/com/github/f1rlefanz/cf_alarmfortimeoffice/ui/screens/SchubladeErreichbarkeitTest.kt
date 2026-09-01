package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Haelt fest, WO die Navigationsschublade liegen darf - und wo nicht.
 *
 * WARUM DAS EIN TEST SEIN MUSS UND KEIN KOMMENTAR: Die vier Onboarding-Gates
 * (`BatteryExemption`, `UnusedAppRestrictions`, `TimeOfficeHealthCheck`, `OEMWarning`) sind
 * Zweige DESSELBEN `when` in `MainScreen` wie `MainContentScreen` - sie ERSETZEN den
 * Hauptbildschirm, sie liegen nicht darueber. Genau daran haengt, dass die Schublade waehrend
 * eines Gates nicht existiert: Sie steckt in `MainContentScreen`, und der ist dann gar nicht
 * komponiert.
 *
 * Zoege jemand die Schublade eine Ebene hoch nach `MainScreen` (oder gar in `MainActivity`) -
 * eine naheliegende "Aufraeumung", weil sie dort formal auch funktioniert -, koennte sich der
 * Nutzer per Wischgeste mitten aus einem Gate herausnavigieren, ohne dass dessen
 * Dismissed-Flag geschrieben wird. `handleAuthenticationSuccess()` wuerfe ihn beim naechsten
 * Durchlauf zurueck ins Gate, und zwar dauerhaft: genau die Schleife, gegen die der
 * `BackHandler` 2026 gebaut wurde (siehe
 * `.claude/skills/cfalarm-ui-und-navigation/reference/navigation.md`).
 *
 * **Am Geraet ist das nicht pruefbar.** Am Emulator (01.09.2026) waren alle Gate-Bedingungen
 * erfuellt - Akku-Ausnahme erteilt, keine App-Pausierung, TimeOffice gesund, kein OEM-Fall -,
 * es liess sich also kein Gate provozieren, um die Schublade dort zu suchen. Diese Pruefung
 * ist deshalb der einzige verfuegbare Nachweis.
 */
class SchubladeErreichbarkeitTest {

    private fun quelltext(pfad: String): String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad")
            .readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `die Schublade steckt in MainContentScreen`() {
        assertTrue(
            "MainContentScreen enthaelt keinen ModalNavigationDrawer mehr - wurde die " +
                "Navigation verschoben? Dann diesen Test mitziehen und den Gate-Fall neu denken.",
            quelltext("ui/screens/MainContentScreen.kt").contains("ModalNavigationDrawer")
        )
    }

    @Test
    fun `MainScreen enthaelt keine Schublade`() {
        assertFalse(
            "In MainScreen liegt eine Navigationsschublade. Dort umschliesst sie AUCH die vier " +
                "Onboarding-Gates - der Nutzer koennte sich per Wischgeste aus einem Gate " +
                "herausnavigieren, ohne dessen Dismissed-Flag zu schreiben.",
            quelltext("ui/screens/MainScreen.kt").contains("ModalNavigationDrawer")
        )
    }

    @Test
    fun `MainActivity enthaelt keine Schublade`() {
        assertFalse(
            "In MainActivity liegt eine Navigationsschublade - sie umschloesse damit auch " +
                "LoginScreen und CalendarAuthorizationScreen.",
            File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/MainActivity.kt")
                .readText()
                .contains("ModalNavigationDrawer")
        )
    }

    @Test
    fun `die Schublade nutzt die Ueberladung mit drawerState`() {
        // Nur ModalDrawerSheet(drawerState = ...) registriert intern den
        // PredictiveBackHandler(enabled = drawerState.isOpen) (Material3 1.4.0,
        // NavigationDrawer.kt:633 -> :643 -> :955). Mit der parameterlosen Ueberladung (:590)
        // wuerde ein Zurueck bei offener Schublade auf dem Home-Tab die App BEENDEN, weil der
        // BackHandler in MainScreen dort bewusst ausgeschaltet ist (enabled = !onHomeTab).
        val text = quelltext("ui/screens/MainContentScreen.kt")
        assertTrue(
            "ModalDrawerSheet ohne drawerState-Parameter: Zurueck bei offener Schublade wuerde " +
                "auf dem Home-Tab die App beenden.",
            Regex("""ModalDrawerSheet\(\s*drawerState\s*=""").containsMatchIn(text)
        )
    }
}
