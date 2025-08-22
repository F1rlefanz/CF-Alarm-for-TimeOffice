package com.github.f1rlefanz.cf_alarmfortimeoffice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI Tests für MainActivity und kritische User Flows
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Test
    fun app_launches_successfully() {
        // Verify app launches without crash
        composeTestRule.waitForIdle()
    }
    
    @Test
    fun main_screen_shows_required_elements() {
        // Verify main UI elements are present
        composeTestRule.onNodeWithText("CF Alarm", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }
    
    @Test
    fun navigation_drawer_opens() {
        // Given - App is launched
        composeTestRule.waitForIdle()
        
        // When - Click menu button (if exists)
        composeTestRule.onNodeWithContentDescription("Menu", ignoreCase = true)
            .performClick()
        
        // Then - Navigation items should be visible
        composeTestRule.onNodeWithText("Einstellungen", ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun google_sign_in_button_is_visible_when_not_authenticated() {
        // Given - User is not signed in
        // This would require mocking the auth state
        
        // Then - Sign in button should be visible
        composeTestRule.onNodeWithText("Mit Google anmelden", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun alarm_list_is_displayed() {
        // Verify alarm list or empty state is shown
        val alarmList = composeTestRule.onNodeWithTag("alarm_list")
        val emptyState = composeTestRule.onNodeWithText("Keine Alarme", ignoreCase = true)
        
        // Either list or empty state should exist
        try {
            alarmList.assertExists()
        } catch (e: AssertionError) {
            emptyState.assertExists()
        }
    }
    
    @Test
    fun add_alarm_button_is_clickable() {
        // Find and click FAB
        composeTestRule.onNodeWithContentDescription("Alarm hinzufügen", ignoreCase = true)
            .assertIsDisplayed()
            .assertHasClickAction()
    }
    
    @Test
    fun settings_screen_is_accessible() {
        // Navigate to settings
        composeTestRule.onNodeWithText("Einstellungen", ignoreCase = true)
            .performClick()
        
        // Verify settings screen opens
        composeTestRule.onNodeWithText("Vorlaufzeit", ignoreCase = true)
            .assertExists()
    }
}