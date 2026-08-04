package com.github.f1rlefanz.cf_alarmfortimeoffice.navigation

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper

/**
 * Navigation State für die Main App
 * Ersetzt primitive Boolean-Navigation durch typisierte sealed classes
 *
 * PHASE 1 MIGRATION: Added Battery Exemption and OEM Warning states for onboarding flow
 */
sealed class NavigationState {

    // Haupt-Screens
    data class MainContent(val selectedTab: MainTab = MainTab.HOME) : NavigationState()
    data class CalendarSelection(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class ShiftConfig(val returnToTab: MainTab = MainTab.WECKER) : NavigationState()
    data class EventList(val returnToTab: MainTab = MainTab.HOME) : NavigationState()

    // Onboarding-Screens (PHASE 1 MIGRATION)
    data class BatteryExemption(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class UnusedAppRestrictions(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class TimeOfficeHealthCheck(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class OEMWarning(
        val oemType: BatteryOptimizationHelper.OEMType,
        val returnToTab: MainTab = MainTab.HOME
    ) : NavigationState()

    // Hue-Screens
    data class HueRuleConfig(val ruleId: String? = null, val returnToTab: MainTab = MainTab.HUE) : NavigationState()
    data class HueSettings(val returnToTab: MainTab = MainTab.HUE) : NavigationState()

    // Dimmer-Screens
    data class DimmerSettings(val returnToTab: MainTab = MainTab.DIMMER) : NavigationState()
    data class DimmerRuleConfig(val ruleId: String? = null, val returnToTab: MainTab = MainTab.DIMMER) : NavigationState()
    data class DimmerPreview(val returnToTab: MainTab = MainTab.DIMMER) : NavigationState()

    // DND-Screen (kein eigener Bottom-Tab, erreichbar ueber Settings)
    data class DndSettings(val returnToTab: MainTab = MainTab.SETTINGS) : NavigationState()
}

enum class MainTab {
    HOME, WECKER, STATUS, SETTINGS, HUE, DIMMER
}

// Extension functions for NavigationState
fun NavigationState.isMainContent(): Boolean = this is NavigationState.MainContent
fun NavigationState.asMainContent(): NavigationState.MainContent? = this as? NavigationState.MainContent

/**
 * Navigation Actions für State-Änderungen
 * PHASE 1 MIGRATION: Added Battery and OEM navigation actions
 */
sealed class NavigationAction {
    data class NavigateToCalendarSelection(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToShiftConfig(val fromTab: MainTab = MainTab.SETTINGS) : NavigationAction()
    data class NavigateToEventList(val fromTab: MainTab = MainTab.HOME) : NavigationAction()

    // Onboarding Navigation Actions (PHASE 1 MIGRATION)
    data class NavigateToBatteryExemption(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToUnusedAppRestrictions(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToTimeOfficeHealthCheck(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToOEMWarning(
        val oemType: BatteryOptimizationHelper.OEMType,
        val fromTab: MainTab = MainTab.HOME
    ) : NavigationAction()
    
    // Hue Navigation Actions
    data class NavigateToHueRuleConfig(val ruleId: String? = null, val fromTab: MainTab = MainTab.HUE) : NavigationAction()
    data class NavigateToHueSettings(val fromTab: MainTab = MainTab.HUE) : NavigationAction()

    // Dimmer Navigation Actions
    data class NavigateToDimmerSettings(val fromTab: MainTab = MainTab.DIMMER) : NavigationAction()
    data class NavigateToDimmerRuleConfig(val ruleId: String? = null, val fromTab: MainTab = MainTab.DIMMER) : NavigationAction()
    data class NavigateToDimmerPreview(val fromTab: MainTab = MainTab.DIMMER) : NavigationAction()

    // DND Navigation Action
    data class NavigateToDndSettings(val fromTab: MainTab = MainTab.SETTINGS) : NavigationAction()

    object NavigateBackToMain : NavigationAction()
    data class NavigateToMainWithTab(val tab: MainTab) : NavigationAction()
    data class ChangeTab(val tab: MainTab) : NavigationAction()
}
