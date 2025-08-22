package com.github.f1rlefanz.cf_alarmfortimeoffice.navigation

/**
 * Navigation State für die Main App
 * Ersetzt primitive Boolean-Navigation durch typisierte sealed classes
 */
sealed class NavigationState {
    
    // Haupt-Screens
    data class MainContent(val selectedTab: MainTab = MainTab.HOME) : NavigationState()
    data class CalendarSelection(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    data class ShiftConfig(val returnToTab: MainTab = MainTab.SETTINGS) : NavigationState()
    data class EventList(val returnToTab: MainTab = MainTab.HOME) : NavigationState()
    
    // Hue-Screens
    data class HueRuleConfig(val ruleId: String? = null, val returnToTab: MainTab = MainTab.HUE) : NavigationState()
    data class HueSettings(val returnToTab: MainTab = MainTab.HUE) : NavigationState()
    
    // Utility functions
    fun isCalendarSelection(): Boolean = this is CalendarSelection
    fun isShiftConfig(): Boolean = this is ShiftConfig
    fun isMainContent(): Boolean = this is MainContent
    fun isEventList(): Boolean = this is EventList
    fun isHueRuleConfig(): Boolean = this is HueRuleConfig
    fun isHueSettings(): Boolean = this is HueSettings
    
    fun asMainContent(): MainContent? = this as? MainContent
    fun asCalendarSelection(): CalendarSelection? = this as? CalendarSelection
    fun asShiftConfig(): ShiftConfig? = this as? ShiftConfig
    fun asEventList(): EventList? = this as? EventList
    fun asHueRuleConfig(): HueRuleConfig? = this as? HueRuleConfig
    fun asHueSettings(): HueSettings? = this as? HueSettings
}

enum class MainTab {
    HOME, STATUS, SETTINGS, HUE
}

/**
 * Navigation Actions für State-Änderungen
 */
sealed class NavigationAction {
    data class NavigateToCalendarSelection(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    data class NavigateToShiftConfig(val fromTab: MainTab = MainTab.SETTINGS) : NavigationAction()
    data class NavigateToEventList(val fromTab: MainTab = MainTab.HOME) : NavigationAction()
    
    // Hue Navigation Actions
    data class NavigateToHueRuleConfig(val ruleId: String? = null, val fromTab: MainTab = MainTab.HUE) : NavigationAction()
    data class NavigateToHueSettings(val fromTab: MainTab = MainTab.HUE) : NavigationAction()
    
    object NavigateBackToMain : NavigationAction()
    data class NavigateToMainWithTab(val tab: MainTab) : NavigationAction()
    data class ChangeTab(val tab: MainTab) : NavigationAction()
}
