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
 * Der Ort-Bildschirm hat zwei Einstiege (Einstellungen, Hue-Regel-Editor).
 * Sein Rueckweg traegt den GENAUEN Herkunftszustand - ein Zurueck aus dem Ort muss den Editor
 * mit derselben `sitzung` wiederherstellen, sonst bekaeme der Editor einen frischen
 * SaveableStateProvider-Schluessel und die ungespeicherte Eingabe waere weg. Der Weg Typ-Seite ->
 * Editor -> Ort -> zurueck steht in [NavigationViewModelTest].
 */
class NavigationViewModelTageslichtTest {

    private val seite = NavigationState.TypSeite("typ-x", MainTab.WECKER, sitzung = 5)

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
        val herkunft = NavigationState.HueRuleConfig(seite, "h1", sitzung = 7)
        val vm = NavigationViewModel()
        vm.navigateToOrtFuerTageslicht(herkunft)
        vm.navigateBackToMain()
        assertEquals(herkunft, vm.navigationState.value)
    }

    @Test
    fun `jedes frische Oeffnen eines Editors bekommt eine neue Sitzung`() {
        val vm = NavigationViewModel()
        vm.navigateToTypLichtregel(seite)
        val erste = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        vm.navigateBackToMain()
        vm.navigateToTypLichtregel(seite)
        val zweite = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        assertNotEquals(erste, zweite)

        // Auch ohne Zurueck dazwischen: jedes Oeffnen ist eine eigene Bearbeitung.
        vm.navigateToTypLichtregel(seite)
        val dritte = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        vm.navigateToTypLichtregel(seite)
        val vierte = (vm.navigationState.value as NavigationState.HueRuleConfig).sitzung
        assertNotEquals(dritte, vierte)
        assertTrue(setOf(erste, zweite, dritte, vierte).size == 4)
    }

    @Test
    fun `zweiter Tipp auf Zurueck im Ort fuehrt wieder zum selben Ziel`() {
        val editor = NavigationState.HueRuleConfig(seite, "r1", sitzung = 3)
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
        val hue = NavigationState.HueRuleConfig(seite, sitzung = 11)
        assertEquals("hue-11", hue.editorFormularSchluessel())
        assertEquals("hue-11", NavigationState.OrtFuerTageslicht(hue).editorFormularSchluessel())
        assertNull(NavigationState.OrtFuerTageslicht().editorFormularSchluessel())
        assertNull(NavigationState.MainContent(MainTab.HUE).editorFormularSchluessel())
    }

    @Test
    fun `Umweg ueber den Ort behaelt das Formular, endgueltiges Verlassen verwirft es`() {
        val hue = NavigationState.HueRuleConfig(seite, sitzung = 11)
        val ort = NavigationState.OrtFuerTageslicht(hue)
        // Editor -> Ort und Ort -> derselbe Editor: nichts verwerfen.
        assertNull(verworfenesEditorFormular(hue, ort))
        assertNull(verworfenesEditorFormular(ort, hue))
        // Editor -> Tab oder Hue-Einstellungen: weg.
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.MainContent(MainTab.HUE)))
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.HueSettings()))
        // Ort -> irgendwo anders als zurueck in den Editor: ebenfalls weg.
        assertEquals("hue-11", verworfenesEditorFormular(ort, NavigationState.MainContent(MainTab.HOME)))
        // Neu geoeffneter Editor mit anderer Sitzung: der alte Schluessel ist verwaist.
        assertEquals("hue-11", verworfenesEditorFormular(hue, NavigationState.HueRuleConfig(seite, sitzung = 12)))
        // Ohne Editor davor gibt es nichts zu verwerfen.
        assertNull(verworfenesEditorFormular(null, hue))
        assertNull(verworfenesEditorFormular(NavigationState.MainContent(MainTab.SETTINGS), NavigationState.OrtFuerTageslicht()))
    }
}
