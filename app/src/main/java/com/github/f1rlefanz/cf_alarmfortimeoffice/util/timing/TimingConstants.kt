package com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing

/**
 * Timing Constants für Animationen, Timeouts und zeitbezogene Konfigurationen
 */

// ============================
// UI TIMING CONSTANTS
// ============================
object UIConstants {
    /** Standard-Animation-Dauer in Millisekunden */
    const val ANIMATION_DURATION_MS = 300L
    
    /** Kurze Animation-Dauer in Millisekunden */
    const val ANIMATION_DURATION_SHORT_MS = 150L
    
    /** Lange Animation-Dauer für aufwändige Animationen in Millisekunden */
    const val ANIMATION_DURATION_LONG_MS = 3000L
    
    /** Debounce-Delay für Benutzereingaben in Millisekunden */
    const val INPUT_DEBOUNCE_MS = 300L
    
    /** Auto-Dismiss Zeit für Snackbars in Millisekunden */
    const val SNACKBAR_AUTO_DISMISS_MS = 4000L
    
    /** Auto-Dismiss Zeit für Error Messages in Millisekunden */
    const val ERROR_MESSAGE_AUTO_DISMISS_MS = 5000L
    
    /** Delay für UI-Stabilisierung nach State-Änderungen */
    const val UI_STABILITY_DELAY_MS = 300L
    
    /** Vibrations-Pattern für Alarme */
    val ALARM_VIBRATION_PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500)
}

// ============================
// ANIMATION DURATION CONSTANTS
// ============================
object AnimationDurations {
    /** Timer-Update-Intervall in Millisekunden */
    const val TIMER_UPDATE_MS = 1000L
    
    /** Pulsieren-Animation für Timer */
    const val PULSE_MS = 1000L
    
    /** Blinken für kritische Zustände */
    const val BLINK_MS = 500L
    
    /** Schnelle UI-Übergänge */
    const val QUICK_MS = 150L
    
    /** Standard UI-Animationen */
    const val STANDARD_MS = 300L
    
    /** Lange Animationen */
    const val LONG_MS = 600L
}

// ============================
// NETWORK & TIMEOUT CONSTANTS
// ============================
object NetworkConstants {
    /** Standard-Timeout für HTTP-Requests in Sekunden */
    const val HTTP_TIMEOUT_SECONDS = 30
    
    /** Timeout für Authentifizierung in Sekunden */
    const val AUTH_TIMEOUT_SECONDS = 60
    
    /** Retry-Anzahl für fehlgeschlagene Requests */
    const val MAX_RETRY_ATTEMPTS = 3
    
    /** Delay zwischen Retry-Versuchen in Millisekunden */
    const val RETRY_DELAY_MS = 1000L
}
