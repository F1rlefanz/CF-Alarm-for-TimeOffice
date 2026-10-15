package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.editorFormularSchluessel
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.verworfenesEditorFormular
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rueckweg und Formular-Erhalt rund um "Ort fuer Tageslicht" (#140).
 *
 * Der Ort-Bildschirm hat drei Einstiege (Einstellungen, Dimm-Fenster-Editor, Hue-Regel-Editor).
 * Sein Rueckweg traegt den GENAUEN Herkunftszustand - ein Zurueck aus dem Ort muss den Editor
 * mit derselben `sitzung` wiederherstellen, sonst bekaeme der Editor einen frischen
 * SaveableStateProvider-Schluessel und die ungespeicherte Eingabe waere weg. Das zweite Zurueck
 * fuehrt dann den ueblichen Rueckweg des Editors (Liste oder Tab).
 */
class NavigationViewModelTageslichtTest {

    @Test
    fun `aus den Einstellungen geoeffnet fuehrt Zurueck in die Einstellungen`() {
        val vm = NavigationViewModel()
        vm.navigateToOrtFuerTageslicht(NavigationState.MainContent(MainTab.SETTINGS))
        assertEquals(
            NavigationState.OrtFuerTageslicht(NavigationState.MainContent(MainTab.SETTINGS)),
            vm.navigationState.value
        )
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(NavigationState.MainContent(MainTab.SETTINGS), vm.navigationState.value)
    }

    @Test
    fun `navigateBackToMain nimmt aus dem Ort denselben Rueckweg`() {
        // System-Zurueck (BackHandler -> navigateBackFrom) und navigateBackToMain duerfen fuer
        // denselben Einstieg nie verschiedene Ziele kennen - der else-Zweig landete auf HOME.
        val herkunft = NavigationState.DimmerRuleConfig("d1", MainTab.DIMMER, true, sitzung = 7)
        val vm = NavigationViewModel()
        vm.navigateToOrtFuerTageslicht(herkunft)
        vm.navigateBackToMain()
        assertEquals(herkunft, vm.navigationState.value)
    }

    @Test
    fun `aus dem Dimmer-Editor geoeffnet fuehrt Zurueck in DENSELBEN Editor und dann zur Liste`() {
        val editor = NavigationState.DimmerRuleConfig("d1", MainTab.DIMMER, true, sitzung = 5)
        val vm = NavigationViewModel()
        vm.navigateToOrtFuerTageslicht(editor)
        vm.navigateBackFrom(vm.navigationState.value)
        // equals schliesst sitzung ein: derselbe Formular-Schluessel, dieselbe Eingabe.
        assertEquals(editor, vm.navigationState.value)
        assertEquals(5L, (vm.navigationState.value as NavigationState.DimmerRuleConfig).sitzung)
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(NavigationState.DimmerSettings(MainTab.DIMMER), vm.navigationState.value)
    }

    @Test
    fun `aus dem Hue-Editor vom Tab geoeffnet fuehrt zweimal Zurueck auf den Hue-Tab`() {
        val vm = NavigationViewModel()
        vm.navigateToHueRuleConfig(fromTab = MainTab.HUE, cameFromSettingsList = false)
        val editor = vm.navigationState.value
        vm.navigateToOrtFuerTageslicht(editor)
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(editor, vm.navigationState.value)
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(NavigationState.MainContent(MainTab.HUE), vm.navigationState.value)
    }

    @Test
    fun `aus dem Hue-Editor ueber die Liste geoeffnet fuehrt zweimal Zurueck zur Liste`() {
        val vm = NavigationViewModel()
        vm.navigateToHueRuleConfig(ruleId = "r1", fromTab = MainTab.HUE, cameFromSettingsList = true)
        val editor = vm.navigationState.value
        vm.navigateToOrtFuerTageslicht(editor)
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(editor, vm.navigationState.value)
        vm.navigateBackFrom(vm.navigationState.value)
        assertEquals(NavigationState.HueSettings(MainTab.HUE), vm.navigationState.value)
    }

    @Test
    fun `jedes frische Oeffnen eines Editors bekommt eine neue Sitzung`() {
        val vm = NavigationViewModel()
        vm.navigateToHueRuleConfig(fromTab = MainTab.HUE)
        val erste = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        vm.navigateBackToMain()
        vm.navigateToHueRuleConfig(fromTab = MainTab.HUE)
        val zweite = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        assertNotEquals(erste, zweite)

        vm.navigateToDimmerRuleConfig(fromTab = MainTab.DIMMER)
        val dritte = (vm.navigationState.value as NavigationState.DimmerRuleConfig).sitzung
        vm.navigateToDimmerRuleConfig(fromTab = MainTab.DIMMER)
        val vierte = (vm.navigationState.value as NavigationState.DimmerRuleConfig).sitzung
        assertNotEquals(dritte, vierte)
        assertTrue(setOf(erste, zweite, dritte, vierte).size == 4)
    }

    @Test
    fun `zweiter Tipp auf Zurueck im Ort fuehrt wieder zum selben Ziel`() {
        val editor = NavigationState.HueRuleConfig("r1", MainTab.HUE, true, sitzung = 3)
        val vm = NavigationViewModel()
        vm.navigateToOrtFuerTageslicht(editor)
        val gerendert = vm.navigationState.value
        vm.navigateBackFrom(gerendert)
        vm.navigateBackFrom(gerendert)
        assertEquals(editor, vm.navigationState.value)
    }

    // ---- Formular-Schluessel und wann er verworfen wird -----------------------------------

    @Test
    fun `der Ort traegt den Formular-Schluessel seines Editors`() {
        val hue = NavigationState.HueRuleConfig(sitzung = 11)
        val dim = NavigationState.DimmerRuleConfig(sitzung = 12)
        assertEquals("hue-11", hue.editorFormularSchluessel())
        assertEquals("dim-12", dim.editorFormularSchluessel())
        assertEquals("hue-11", NavigationState.OrtFuerTageslicht(hue).editorFormularSchluessel())
        assertNull(NavigationState.OrtFuerTageslicht().editorFormularSchluessel())
        assertNull(NavigationState.MainContent(MainTab.HUE).editorFormularSchluessel())
    }

    @Test
    fun `Umweg ueber den Ort behaelt das Formular, endgueltiges Verlassen verwirft es`() {
        val hue = NavigationState.HueRuleConfig(sitzung = 11)
        val ort = NavigationState.OrtFuerTageslicht(hue)
        // Editor -> Ort und Ort -> derselbe Editor: nichts verwerfen.
        assertNull(verworfenesEditorFormular(hue, ort))
        assertNull(verworfenesEditorFormular(ort, hue))
        // Editor -> Tab/Liste: weg.
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.MainContent(MainTab.HUE)))
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.HueSettings()))
        // Ort -> irgendwo anders als zurueck in den Editor: ebenfalls weg.
        assertEquals("hue-11", verworfenesEditorFormular(ort, NavigationState.MainContent(MainTab.HOME)))
        // Neu geoeffneter Editor mit anderer Sitzung: der alte Schluessel ist verwaist.
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.HueRuleConfig(sitzung = 12)))
        // Ohne Editor davor gibt es nichts zu verwerfen.
        assertNull(verworfenesEditorFormular(null, hue))
        assertNull(verworfenesEditorFormular(NavigationState.MainContent(MainTab.SETTINGS), NavigationState.OrtFuerTageslicht()))
    }
}
