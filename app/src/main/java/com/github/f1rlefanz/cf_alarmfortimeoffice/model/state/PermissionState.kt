package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

/** Sub-State: Kalender-Berechtigung (erteilt, Begründung nötig, abgelehnt). */
data class PermissionState(
    val androidCalendarPermissionGranted: Boolean = false,
    val showAndroidCalendarPermissionRationale: Boolean = false,
    val calendarPermissionDenied: Boolean = false
) {
    val needsPermissionRequest: Boolean get() = 
        !androidCalendarPermissionGranted && !calendarPermissionDenied
    
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
    }
}
