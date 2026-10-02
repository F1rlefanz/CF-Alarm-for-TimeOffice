package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.GateSchritt
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.asMainContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.isMainContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * ViewModel für Navigation State Management
 * Ersetzt primitive Boolean-Navigation durch typisierte sealed classes
 */
@HiltViewModel
class NavigationViewModel @Inject constructor() : ViewModel() {
    
    private val _navigationState = MutableStateFlow<NavigationState>(
        NavigationState.MainContent(MainTab.HOME)
    )
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()
    
    fun handleNavigationAction(action: NavigationAction) {
        val currentState = _navigationState.value
        val newState = when (action) {
            is NavigationAction.NavigateToCalendarSelection -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Calendar Selection (from ${action.fromTab})")
                NavigationState.CalendarSelection(action.fromTab)
            }
            
            is NavigationAction.NavigateToShiftConfig -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Shift Config (from ${action.fromTab})")
                NavigationState.ShiftConfig(action.fromTab)
            }
            
            is NavigationAction.NavigateToEventList -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Event List (from ${action.fromTab})")
                NavigationState.EventList(action.fromTab)
            }
            
            // Battery and OEM navigation
            is NavigationAction.NavigateToBatteryExemption -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Battery Exemption (from ${action.fromTab})")
                NavigationState.BatteryExemption(action.fromTab)
            }
            
            is NavigationAction.NavigateToUnusedAppRestrictions -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Unused App Restrictions (from ${action.fromTab})")
                NavigationState.UnusedAppRestrictions(action.fromTab)
            }

            is NavigationAction.NavigateToTimeOfficeHealthCheck -> {
                Logger.d(LogTags.NAVIGATION, "Main -> TimeOffice Health Check (from ${action.fromTab})")
                NavigationState.TimeOfficeHealthCheck(action.fromTab)
            }

            is NavigationAction.NavigateToOEMWarning -> {
                Logger.d(LogTags.NAVIGATION, "Main -> OEM Warning (${action.oemType}, from ${action.fromTab})")
                NavigationState.OEMWarning(action.oemType, action.fromTab)
            }
            
            is NavigationAction.NavigateToHueRuleConfig -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Hue Rule Config (from ${action.fromTab}, rule: ${action.ruleId}, viaSettingsList: ${action.cameFromSettingsList})")
                NavigationState.HueRuleConfig(action.ruleId, action.fromTab, action.cameFromSettingsList)
            }
            
            is NavigationAction.NavigateToHueSettings -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Hue Settings (from ${action.fromTab})")
                NavigationState.HueSettings(action.fromTab)
            }

            is NavigationAction.NavigateToDimmerSettings -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Dimmer Settings (from ${action.fromTab})")
                NavigationState.DimmerSettings(action.fromTab)
            }

            is NavigationAction.NavigateToDimmerRuleConfig -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Dimmer Rule Config (from ${action.fromTab}, rule: ${action.ruleId}, viaSettingsList: ${action.cameFromSettingsList})")
                NavigationState.DimmerRuleConfig(action.ruleId, action.fromTab, action.cameFromSettingsList)
            }

            is NavigationAction.NavigateToDimmerPreview -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Dimmer Preview (from ${action.fromTab})")
                NavigationState.DimmerPreview(action.fromTab)
            }

            is NavigationAction.NavigateToDndSettings -> {
                Logger.d(LogTags.NAVIGATION, "Main -> DND Settings (from ${action.fromTab})")
                NavigationState.DndSettings(action.fromTab)
            }

            is NavigationAction.NavigateBackToMain -> rueckwegZiel(currentState)

            is NavigationAction.NavigateToMainWithTab -> {
                Logger.d(LogTags.NAVIGATION, "-> Main (${action.tab} tab)")
                NavigationState.MainContent(action.tab)
            }
            
            is NavigationAction.ChangeTab -> {
                val mainState = currentState.asMainContent()
                if (mainState != null) {
                    Logger.d(LogTags.UI, "Tab change -> ${action.tab}")
                    NavigationState.MainContent(action.tab)
                } else {
                    // Wenn nicht im MainContent, ignoriere Tab-Änderung
                    currentState
                }
            }
        }
        
        _navigationState.value = newState
    }
    
    // Convenience methods for common operations
    fun navigateToCalendarSelection(fromTab: MainTab = MainTab.HOME) = 
        handleNavigationAction(NavigationAction.NavigateToCalendarSelection(fromTab))
    
    fun navigateToShiftConfig(fromTab: MainTab = MainTab.WECKER) =
        handleNavigationAction(NavigationAction.NavigateToShiftConfig(fromTab))
    
    fun navigateToEventList(fromTab: MainTab = MainTab.HOME) = 
        handleNavigationAction(NavigationAction.NavigateToEventList(fromTab))
    
    // Battery and OEM navigation convenience methods
    fun navigateToBatteryExemption(fromTab: MainTab = MainTab.HOME) = 
        handleNavigationAction(NavigationAction.NavigateToBatteryExemption(fromTab))
    
    fun navigateToOEMWarning(oemType: BatteryOptimizationHelper.OEMType, fromTab: MainTab = MainTab.HOME) =
        handleNavigationAction(NavigationAction.NavigateToOEMWarning(oemType, fromTab))

    fun navigateToUnusedAppRestrictions(fromTab: MainTab = MainTab.HOME) =
        handleNavigationAction(NavigationAction.NavigateToUnusedAppRestrictions(fromTab))

    fun navigateToTimeOfficeHealthCheck(fromTab: MainTab = MainTab.HOME) =
        handleNavigationAction(NavigationAction.NavigateToTimeOfficeHealthCheck(fromTab))

    fun navigateToHueRuleConfig(
        ruleId: String? = null,
        fromTab: MainTab = MainTab.HUE,
        cameFromSettingsList: Boolean = false
    ) =
        handleNavigationAction(NavigationAction.NavigateToHueRuleConfig(ruleId, fromTab, cameFromSettingsList))
    
    fun navigateToHueSettings(fromTab: MainTab = MainTab.HUE) =
        handleNavigationAction(NavigationAction.NavigateToHueSettings(fromTab))

    fun navigateToDimmerSettings(fromTab: MainTab = MainTab.DIMMER) =
        handleNavigationAction(NavigationAction.NavigateToDimmerSettings(fromTab))

    fun navigateToDimmerRuleConfig(
        ruleId: String? = null,
        fromTab: MainTab = MainTab.DIMMER,
        cameFromSettingsList: Boolean = true
    ) =
        handleNavigationAction(NavigationAction.NavigateToDimmerRuleConfig(ruleId, fromTab, cameFromSettingsList))

    fun navigateToDimmerPreview(fromTab: MainTab = MainTab.DIMMER) =
        handleNavigationAction(NavigationAction.NavigateToDimmerPreview(fromTab))

    fun navigateToDndSettings(fromTab: MainTab = MainTab.SETTINGS) =
        handleNavigationAction(NavigationAction.NavigateToDndSettings(fromTab))

    fun navigateBackToMain() =
        handleNavigationAction(NavigationAction.NavigateBackToMain)

    /**
     * Zurueck aus [state] - die EINZIGE Aufloesung des Rueckwegs. System-Zurueck (`BackHandler`
     * in `MainScreen`), der Zurueck-Pfeil der Regel-Editoren und deren "Speichern" rufen alle
     * hierher; [navigateBackToMain] delegiert mit dem aktuellen Zustand.
     *
     * Bis Issue #132 stand der Rueckweg aus `HueRuleConfig`/`DimmerRuleConfig` DREIMAL im Code
     * (BackHandler, zwei Screen-Lambdas, hier) - mit genau der Gefahr, die v1.22.0 schon einmal
     * eingeholt hat: zwei Stellen, die fuer denselben Einstiegspfad verschiedene Ziele kennen.
     *
     * Warum der Zustand uebergeben wird statt immer den aktuellen zu nehmen: ein zweiter Tipp auf
     * "Speichern", nachdem der Zustand schon gewechselt hat, fuehrt so wieder zum selben Ziel
     * (idempotent) - genau wie die frueheren Closures ueber den gerenderten Zustand.
     */
    fun navigateBackFrom(state: NavigationState) {
        _navigationState.value = rueckwegZiel(state)
    }

    private fun rueckwegZiel(state: NavigationState): NavigationState {
        // HueRuleConfig/DimmerRuleConfig haben zwei Einstiegspfade (siehe cameFromSettingsList) -
        // ein Zurueck darf hier NICHT blind zu MainContent aufloesen, sonst ueberspringt der
        // Listen-Pfad HueSettings/DimmerSettings.
        if (state is NavigationState.HueRuleConfig && state.cameFromSettingsList) {
            Logger.d(LogTags.NAVIGATION, "-> Hue Settings (${state.returnToTab} tab, via settings list)")
            return NavigationState.HueSettings(state.returnToTab)
        }
        if (state is NavigationState.DimmerRuleConfig && state.cameFromSettingsList) {
            Logger.d(LogTags.NAVIGATION, "-> Dimmer Settings (${state.returnToTab} tab, via settings list)")
            return NavigationState.DimmerSettings(state.returnToTab)
        }
        val returnTab = when (state) {
            is NavigationState.CalendarSelection -> state.returnToTab
            is NavigationState.ShiftConfig -> state.returnToTab
            is NavigationState.EventList -> state.returnToTab
            is NavigationState.BatteryExemption -> state.returnToTab
            is NavigationState.UnusedAppRestrictions -> state.returnToTab
            is NavigationState.TimeOfficeHealthCheck -> state.returnToTab
            is NavigationState.OEMWarning -> state.returnToTab
            is NavigationState.HueRuleConfig -> state.returnToTab
            is NavigationState.HueSettings -> state.returnToTab
            is NavigationState.DimmerSettings -> state.returnToTab
            is NavigationState.DimmerRuleConfig -> state.returnToTab
            is NavigationState.DimmerPreview -> state.returnToTab
            is NavigationState.DndSettings -> state.returnToTab
            else -> MainTab.HOME
        }
        Logger.d(LogTags.NAVIGATION, "-> Main ($returnTab tab)")
        return NavigationState.MainContent(returnTab)
    }
    
    fun navigateToMainWithTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.NavigateToMainWithTab(tab))
    
    fun changeTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.ChangeTab(tab))
    
    /**
     * Automatischer Gate-Schritt bei jedem App-Vordergrund. WELCHER Schritt dran ist, entscheidet
     * `naechsterGateSchritt(lage, GateEinstieg.AUTO)` in `navigation/OnboardingGates.kt` (dort
     * auch, warum "Spaeter" beim Akku-Gate ERLEDIGT heisst); hier wird nur noch navigiert.
     *
     * Der Waechter bleibt HIER: navigiert wird nur aus `MainContent` heraus - nie aus einem
     * bereits offenen Screen weg (etwa waehrend der Nutzer gerade in der Schichtkonfiguration
     * ist). Er prueft den Zustand zum Zeitpunkt der Navigation, nicht zum Zeitpunkt des Lesens.
     *
     * Auch der OEM-Hinweis kommt seit Issue #132 auf diesem Weg - sonst sah ihn nie, wer ein Gate
     * mit "Spaeter" verlassen oder die Gates vor dem Hinweis durchlaufen hatte. Seinen
     * "gezeigt"-Merker schreibt der Aufrufer (Datenschicht, dieses ViewModel bleibt Android-frei)
     * - und zwar NUR, wenn hier wirklich navigiert wurde: deshalb der Rueckgabewert. Ein Merker
     * ohne angezeigten Screen hiesse, der Hinweis kaeme nie.
     *
     * `GateEinstieg.AUTO` liefert nie [GateSchritt.Fertig]: die Wartungskette stellt der Eintritt
     * in `MainContent`. [GateSchritt.Fertig] und [GateSchritt.Nichts] bleiben hier ohne Wirkung.
     *
     * @return `true`, wenn zu einem Gate navigiert wurde
     */
    fun handleAuthenticationSuccess(schritt: GateSchritt): Boolean {
        if (!_navigationState.value.isMainContent()) return false
        when (schritt) {
            GateSchritt.Kalender -> {
                Logger.i(LogTags.NAVIGATION, "Auto-navigation: User authenticated but no calendars selected")
                navigateToCalendarSelection()
            }
            GateSchritt.Akku -> {
                Logger.i(LogTags.NAVIGATION, "Auto-navigation: Calendars selected but no battery exemption")
                navigateToBatteryExemption()
            }
            GateSchritt.Unused -> {
                Logger.i(LogTags.NAVIGATION, "Auto-navigation: Battery gate resolved but unused-app restrictions still active")
                navigateToUnusedAppRestrictions()
            }
            GateSchritt.TimeOffice -> {
                // Noetig fuer Nutzer, die die vorherigen Gates schon VOR diesem Feature
                // durchlaufen hatten und darum nie wieder ueber einen aktiven Weg hierher kamen.
                Logger.i(LogTags.NAVIGATION, "Auto-navigation: Battery/Unused-App gates cleared but TimeOffice health check still needed")
                navigateToTimeOfficeHealthCheck()
            }
            is GateSchritt.Oem -> {
                Logger.i(LogTags.NAVIGATION, "Auto-navigation: all gates cleared, OEM warning for ${schritt.typ} never shown")
                navigateToOEMWarning(schritt.typ)
            }
            GateSchritt.Fertig, GateSchritt.Nichts -> return false
        }
        return true
    }
}
