package com.github.f1rlefanz.cf_alarmfortimeoffice.navigation

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper

/**
 * Navigation State für die Main App
 * Ersetzt primitive Boolean-Navigation durch typisierte sealed classes
 */
sealed class NavigationState {

    // Haupt-Screens
    data class MainContent(val selectedTab: MainTab = MainTab.HOME) : NavigationState()
    data class CalendarSelection(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class ShiftConfig(val returnToTab: MainTab = MainTab.WECKER) : NavigationState()
    data class EventList(val returnToTab: MainTab = MainTab.HOME) : NavigationState()

    /**
     * Die Typ-Seite (Umbau, SPEC 3.3): ein Schichttyp als eigener Bildschirm, erreichbar nur aus
     * [ShiftConfig]. Zurueck fuehrt dorthin (NavigationViewModel.rueckwegZiel), mit demselben
     * [returnToTab] - nicht auf die Uebersicht. [definitionId] ist die stabile id des Typs, nie sein
     * Name (der ist frei aenderbar). Der Entwurf liegt im Bildschirm, nicht hier: dieser Zustand
     * bleibt eine reine Adresse.
     */
    data class TypSeite(
        val definitionId: String,
        val returnToTab: MainTab = MainTab.SETTINGS
    ) : NavigationState()

    // Onboarding-Screens
    data class BatteryExemption(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class UnusedAppRestrictions(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class TimeOfficeHealthCheck(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class OEMWarning(
        val oemType: BatteryOptimizationHelper.OEMType,
        val returnToTab: MainTab = MainTab.HOME
    ) : NavigationState()

    // Hue-Screens
    // cameFromSettingsList unterscheidet die zwei Einstiegspfade: direkt vom Hue-Tab ("Neue
    // Regel") vs. ueber HueSettingsScreen (Regel bearbeiten/anlegen aus der Liste). Zurueck UND
    // Speichern muessen zum jeweils richtigen Ausgangspunkt fuehren - sonst landet der
    // Direktpfad-Nutzer auf einer Liste, die er nie geoeffnet hat, oder der Listen-Pfad-Nutzer
    // ueberspringt die Liste beim Zurueckgehen. Ausgewertet wird der Flag an genau EINER Stelle,
    // NavigationViewModel.navigateBackFrom() - System-Zurueck (MainScreen-BackHandler),
    // Zurueck-Pfeil und Speichern laufen alle darueber.
    //
    // sitzung (#140): Schluessel des SaveableStateProvider in MainScreen. Jedes frische Oeffnen
    // bekommt vom NavigationViewModel eine neue (frisches Formular); der Umweg ueber
    // OrtFuerTageslicht kehrt mit DERSELBEN zurueck (Eingabe bleibt). Siehe editorFormularSchluessel.
    data class HueRuleConfig(
        val ruleId: String? = null,
        val returnToTab: MainTab = MainTab.HUE,
        val cameFromSettingsList: Boolean = false,
        val sitzung: Long = 0
    ) : NavigationState()
    data class HueSettings(val returnToTab: MainTab = MainTab.HUE) : NavigationState()

    // Dimmer-Screens
    data class DimmerSettings(val returnToTab: MainTab = MainTab.DIMMER) : NavigationState()
    // Gleiches Muster wie HueRuleConfig.cameFromSettingsList - aktuell gibt es nur den Pfad ueber
    // DimmerSettingsScreen, deshalb Default true, aber explizit gesetzt an der einzigen Aufrufstelle.
    // sitzung: wie bei HueRuleConfig.
    data class DimmerRuleConfig(
        val ruleId: String? = null,
        val returnToTab: MainTab = MainTab.DIMMER,
        val cameFromSettingsList: Boolean = true,
        val sitzung: Long = 0
    ) : NavigationState()
    data class DimmerPreview(val returnToTab: MainTab = MainTab.DIMMER) : NavigationState()

    // DND-Screen (kein eigener Bottom-Tab, erreichbar ueber Settings)
    data class DndSettings(val returnToTab: MainTab = MainTab.SETTINGS) : NavigationState()

    /** Ort fuer Tageslicht - drei Einstiege (Einstellungen, Dimm-Fenster-Editor, Hue-Regel-Editor).
     *  Der Rueckweg traegt den GENAUEN Herkunftszustand statt eines dritten Booleans (siehe
     *  cameFromSettingsList): aus einem Editor fuehrt Zurueck in DENSELBEN Editor, samt
     *  `sitzung` - nur so findet MainScreen dessen ungespeichertes Formular wieder. */
    data class OrtFuerTageslicht(
        val zurueckZu: NavigationState = MainContent(MainTab.SETTINGS)
    ) : NavigationState()
}

/**
 * Schluessel, unter dem MainScreen das Formular eines Regel-Editors im SaveableStateHolder
 * ablegt - oder `null`, wenn [this] kein Editor ist. Der Ort-Bildschirm traegt den Schluessel
 * des Editors, aus dem er geoeffnet wurde: solange der Nutzer dort ist, gehoert das Formular
 * noch zur laufenden Bearbeitung.
 */
fun NavigationState.editorFormularSchluessel(): String? = when (this) {
    is NavigationState.HueRuleConfig -> "hue-$sitzung"
    is NavigationState.DimmerRuleConfig -> "dim-$sitzung"
    is NavigationState.OrtFuerTageslicht -> zurueckZu.editorFormularSchluessel()
    else -> null
}

/**
 * Der Formular-Schluessel, den der Wechsel [vorher] -> [jetzt] endgueltig verwaist, oder `null`.
 * Verwaist ist er, sobald der neue Zustand nicht mehr zu derselben Bearbeitung gehoert: Editor
 * -> Liste/Tab, Ort -> irgendwo anders als zurueck in seinen Editor, oder ein frisch geoeffneter
 * Editor (neue sitzung). Der Umweg Editor -> Ort -> Editor behaelt ihn.
 */
fun verworfenesEditorFormular(vorher: NavigationState?, jetzt: NavigationState): String? {
    val alt = vorher?.editorFormularSchluessel() ?: return null
    return if (alt == jetzt.editorFormularSchluessel()) null else alt
}

enum class MainTab {
    HOME, WECKER, STATUS, SETTINGS, HUE, DIMMER
}

fun NavigationState.isMainContent(): Boolean = this is NavigationState.MainContent
fun NavigationState.asMainContent(): NavigationState.MainContent? = this as? NavigationState.MainContent

/**
 * Navigation Actions für State-Änderungen
 */
sealed class NavigationAction {
    data class NavigateToCalendarSelection(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToShiftConfig(val fromTab: MainTab = MainTab.SETTINGS) : NavigationAction()
    data class NavigateToEventList(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToTypSeite(
        val definitionId: String,
        val fromTab: MainTab = MainTab.SETTINGS
    ) : NavigationAction()

    // Onboarding Navigation Actions
    data class NavigateToBatteryExemption(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToUnusedAppRestrictions(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToTimeOfficeHealthCheck(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToOEMWarning(
        val oemType: BatteryOptimizationHelper.OEMType,
        val fromTab: MainTab = MainTab.HOME
    ) : NavigationAction()
    
    // Hue Navigation Actions
    data class NavigateToHueRuleConfig(
        val ruleId: String? = null,
        val fromTab: MainTab = MainTab.HUE,
        val cameFromSettingsList: Boolean = false
    ) : NavigationAction()
    data class NavigateToHueSettings(val fromTab: MainTab = MainTab.HUE) : NavigationAction()

    // Dimmer Navigation Actions
    data class NavigateToDimmerSettings(val fromTab: MainTab = MainTab.DIMMER) : NavigationAction()
    data class NavigateToDimmerRuleConfig(
        val ruleId: String? = null,
        val fromTab: MainTab = MainTab.DIMMER,
        val cameFromSettingsList: Boolean = true
    ) : NavigationAction()
    data class NavigateToDimmerPreview(val fromTab: MainTab = MainTab.DIMMER) : NavigationAction()

    // DND Navigation Action
    data class NavigateToDndSettings(val fromTab: MainTab = MainTab.SETTINGS) : NavigationAction()

    // Ort fuer Tageslicht (#140): zurueckZu ist der genaue Herkunftszustand
    data class NavigateToOrtFuerTageslicht(val zurueckZu: NavigationState) : NavigationAction()

    object NavigateBackToMain : NavigationAction()
    data class NavigateToMainWithTab(val tab: MainTab) : NavigationAction()
    data class ChangeTab(val tab: MainTab) : NavigationAction()
}
