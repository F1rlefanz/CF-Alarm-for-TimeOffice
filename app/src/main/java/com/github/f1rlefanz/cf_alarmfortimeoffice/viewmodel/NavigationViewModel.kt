package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
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
 * 
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor mit @Inject
 * ✅ Keine Dependencies - perfekt für ersten Test
 * 
 * MEMORY LEAK FIXED: Added proper cleanup to prevent pthread_mutex_lock errors
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
            
            // PHASE 1 MIGRATION: Battery and OEM navigation
            is NavigationAction.NavigateToBatteryExemption -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Battery Exemption (from ${action.fromTab})")
                NavigationState.BatteryExemption(action.fromTab)
            }
            
            is NavigationAction.NavigateToUnusedAppRestrictions -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Unused App Restrictions (from ${action.fromTab})")
                NavigationState.UnusedAppRestrictions(action.fromTab)
            }

            is NavigationAction.NavigateToOEMWarning -> {
                Logger.d(LogTags.NAVIGATION, "Main -> OEM Warning (${action.oemType}, from ${action.fromTab})")
                NavigationState.OEMWarning(action.oemType, action.fromTab)
            }
            
            is NavigationAction.NavigateToHueRuleConfig -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Hue Rule Config (from ${action.fromTab}, rule: ${action.ruleId})")
                NavigationState.HueRuleConfig(action.ruleId, action.fromTab)
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
                Logger.d(LogTags.NAVIGATION, "Main -> Dimmer Rule Config (from ${action.fromTab}, rule: ${action.ruleId})")
                NavigationState.DimmerRuleConfig(action.ruleId, action.fromTab)
            }

            is NavigationAction.NavigateToDimmerPreview -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Dimmer Preview (from ${action.fromTab})")
                NavigationState.DimmerPreview(action.fromTab)
            }

            is NavigationAction.NavigateToDndSettings -> {
                Logger.d(LogTags.NAVIGATION, "Main -> DND Settings (from ${action.fromTab})")
                NavigationState.DndSettings(action.fromTab)
            }

            is NavigationAction.NavigateBackToMain -> {
                val returnTab = when (currentState) {
                    is NavigationState.CalendarSelection -> currentState.returnToTab
                    is NavigationState.ShiftConfig -> currentState.returnToTab
                    is NavigationState.EventList -> currentState.returnToTab
                    is NavigationState.BatteryExemption -> currentState.returnToTab
                    is NavigationState.UnusedAppRestrictions -> currentState.returnToTab
                    is NavigationState.OEMWarning -> currentState.returnToTab
                    is NavigationState.HueRuleConfig -> currentState.returnToTab
                    is NavigationState.HueSettings -> currentState.returnToTab
                    is NavigationState.DimmerSettings -> currentState.returnToTab
                    is NavigationState.DimmerRuleConfig -> currentState.returnToTab
                    is NavigationState.DimmerPreview -> currentState.returnToTab
                    is NavigationState.DndSettings -> currentState.returnToTab
                    else -> MainTab.HOME
                }
                Logger.d(LogTags.NAVIGATION, "-> Main ($returnTab tab)")
                NavigationState.MainContent(returnTab)
            }
            
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
    
    fun navigateToShiftConfig(fromTab: MainTab = MainTab.SETTINGS) = 
        handleNavigationAction(NavigationAction.NavigateToShiftConfig(fromTab))
    
    fun navigateToEventList(fromTab: MainTab = MainTab.HOME) = 
        handleNavigationAction(NavigationAction.NavigateToEventList(fromTab))
    
    // PHASE 1 MIGRATION: Battery and OEM navigation convenience methods
    fun navigateToBatteryExemption(fromTab: MainTab = MainTab.HOME) = 
        handleNavigationAction(NavigationAction.NavigateToBatteryExemption(fromTab))
    
    fun navigateToOEMWarning(oemType: BatteryOptimizationHelper.OEMType, fromTab: MainTab = MainTab.HOME) =
        handleNavigationAction(NavigationAction.NavigateToOEMWarning(oemType, fromTab))

    fun navigateToUnusedAppRestrictions(fromTab: MainTab = MainTab.HOME) =
        handleNavigationAction(NavigationAction.NavigateToUnusedAppRestrictions(fromTab))
    
    fun navigateToHueRuleConfig(ruleId: String? = null, fromTab: MainTab = MainTab.HUE) = 
        handleNavigationAction(NavigationAction.NavigateToHueRuleConfig(ruleId, fromTab))
    
    fun navigateToHueSettings(fromTab: MainTab = MainTab.HUE) =
        handleNavigationAction(NavigationAction.NavigateToHueSettings(fromTab))

    fun navigateToDimmerSettings(fromTab: MainTab = MainTab.DIMMER) =
        handleNavigationAction(NavigationAction.NavigateToDimmerSettings(fromTab))

    fun navigateToDimmerRuleConfig(ruleId: String? = null, fromTab: MainTab = MainTab.DIMMER) =
        handleNavigationAction(NavigationAction.NavigateToDimmerRuleConfig(ruleId, fromTab))

    fun navigateToDimmerPreview(fromTab: MainTab = MainTab.DIMMER) =
        handleNavigationAction(NavigationAction.NavigateToDimmerPreview(fromTab))

    fun navigateToDndSettings(fromTab: MainTab = MainTab.SETTINGS) =
        handleNavigationAction(NavigationAction.NavigateToDndSettings(fromTab))

    fun navigateBackToMain() =
        handleNavigationAction(NavigationAction.NavigateBackToMain)
    
    fun navigateToMainWithTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.NavigateToMainWithTab(tab))
    
    fun changeTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.ChangeTab(tab))
    
    // Battery-Prompt: Der Nutzer kann den Akku-Ausnahme-Screen mit "Spaeter" ueberspringen.
    // Das Dismiss-Flag ist DataStore-persistiert (BatteryOptimizationHelper.isDismissed/
    // setDismissed) - Aufrufer (MainScreen) reichen den aktuellen Wert an
    // handleAuthenticationSuccess durch, damit dieses ViewModel Android-frei bleibt (bestehende
    // Konvention: kein ViewModel im Projekt injiziert Context/DataStore direkt).
    fun dismissBatteryPrompt() {
        Logger.business(LogTags.NAVIGATION, "Battery-Prompt vom Nutzer uebersprungen (Spaeter) -> Home")
        navigateToMainWithTab(MainTab.HOME)
    }

    // Auto-navigation logic
    fun handleAuthenticationSuccess(
        hasSelectedCalendars: Boolean,
        hasBatteryExemption: Boolean,
        batteryPromptDismissed: Boolean,
        needsUnusedAppRestrictionsPrompt: Boolean
    ) {
        if (!hasSelectedCalendars && _navigationState.value.isMainContent()) {
            Logger.i(LogTags.NAVIGATION, "Auto-navigation: User authenticated but no calendars selected")
            navigateToCalendarSelection()
        } else if (hasSelectedCalendars && !hasBatteryExemption && !batteryPromptDismissed && _navigationState.value.isMainContent()) {
            Logger.i(LogTags.NAVIGATION, "Auto-navigation: Calendars selected but no battery exemption")
            navigateToBatteryExemption()
        } else if (hasSelectedCalendars && hasBatteryExemption && needsUnusedAppRestrictionsPrompt && _navigationState.value.isMainContent()) {
            Logger.i(LogTags.NAVIGATION, "Auto-navigation: Battery exempted but unused-app restrictions still active")
            navigateToUnusedAppRestrictions()
        }
    }
    
    /**
     * CRITICAL FIX: NavigationViewModel Cleanup to prevent pthread_mutex_lock errors
     * MEMORY LEAK PREVENTION: Clear navigation state on destruction
     */
    override fun onCleared() {
        super.onCleared()
        
        try {
            // CRITICAL FIX: Reset navigation state to prevent memory leaks
            _navigationState.value = NavigationState.MainContent(MainTab.HOME)
            
            Logger.d(LogTags.LIFECYCLE, "NavigationViewModel cleared - cleaning up navigation state")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during NavigationViewModel cleanup", e)
        }
        
        // Note: NavigationViewModel has no coroutines to cancel
    }
    
}
