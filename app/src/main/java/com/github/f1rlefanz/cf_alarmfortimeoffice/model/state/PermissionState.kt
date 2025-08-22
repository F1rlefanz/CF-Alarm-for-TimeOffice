package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

/**
 * Sub-State für Permission Management
 * 
 * GRANULAR STATE MODELING: Fokussiert auf Permission-spezifische Daten
 * - Single Responsibility: Nur Permission-bezogene States
 * - Clear State Machine: Explicit Permission States
 * - Validation: Built-in permission flow logic
 */
data class PermissionState(
    val androidCalendarPermissionGranted: Boolean = false,
    val showAndroidCalendarPermissionRationale: Boolean = false,
    val calendarPermissionDenied: Boolean = false
) {
    // Computed properties für Permission Flow Logic
    val needsPermissionRequest: Boolean get() = 
        !androidCalendarPermissionGranted && !calendarPermissionDenied
    
    val shouldShowRationale: Boolean get() = 
        showAndroidCalendarPermissionRationale && !androidCalendarPermissionGranted
    
    val isPermanentlyDenied: Boolean get() = 
        calendarPermissionDenied && !showAndroidCalendarPermissionRationale
    
    val isPermissionGranted: Boolean get() = androidCalendarPermissionGranted
    
    companion object {
        val EMPTY = PermissionState()
        
        fun granted() = PermissionState(
            androidCalendarPermissionGranted = true,
            showAndroidCalendarPermissionRationale = false,
            calendarPermissionDenied = false
        )
        
        fun needsRationale() = PermissionState(
            androidCalendarPermissionGranted = false,
            showAndroidCalendarPermissionRationale = true,
            calendarPermissionDenied = false
        )
        
        fun denied() = PermissionState(
            androidCalendarPermissionGranted = false,
            showAndroidCalendarPermissionRationale = false,
            calendarPermissionDenied = true
        )
    }
}
