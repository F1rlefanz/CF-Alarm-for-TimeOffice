package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * ViewModel für Navigation State Management
 * Ersetzt primitive Boolean-Navigation durch typisierte sealed classes
 */
class NavigationViewModel : ViewModel() {
    
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
            
            is NavigationAction.NavigateToHueRuleConfig -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Hue Rule Config (from ${action.fromTab}, rule: ${action.ruleId})")
                NavigationState.HueRuleConfig(action.ruleId, action.fromTab)
            }
            
            is NavigationAction.NavigateToHueSettings -> {
                Logger.d(LogTags.NAVIGATION, "Main -> Hue Settings (from ${action.fromTab})")
                NavigationState.HueSettings(action.fromTab)
            }
            
            is NavigationAction.NavigateBackToMain -> {
                val returnTab = when (currentState) {
                    is NavigationState.CalendarSelection -> currentState.returnToTab
                    is NavigationState.ShiftConfig -> currentState.returnToTab
                    is NavigationState.EventList -> currentState.returnToTab
                    is NavigationState.HueRuleConfig -> currentState.returnToTab
                    is NavigationState.HueSettings -> currentState.returnToTab
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
    
    fun navigateToHueRuleConfig(ruleId: String? = null, fromTab: MainTab = MainTab.HUE) = 
        handleNavigationAction(NavigationAction.NavigateToHueRuleConfig(ruleId, fromTab))
    
    fun navigateToHueSettings(fromTab: MainTab = MainTab.HUE) = 
        handleNavigationAction(NavigationAction.NavigateToHueSettings(fromTab))
    
    fun navigateBackToMain() = 
        handleNavigationAction(NavigationAction.NavigateBackToMain)
    
    fun navigateToMainWithTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.NavigateToMainWithTab(tab))
    
    fun changeTab(tab: MainTab) = 
        handleNavigationAction(NavigationAction.ChangeTab(tab))
    
    // Auto-navigation logic
    fun handleAuthenticationSuccess(hasSelectedCalendars: Boolean) {
        if (!hasSelectedCalendars && _navigationState.value.isMainContent()) {
            Logger.i(LogTags.NAVIGATION, "Auto-navigation: User authenticated but no calendars selected")
            navigateToCalendarSelection()
        }
    }
}
