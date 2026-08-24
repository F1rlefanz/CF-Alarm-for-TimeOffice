package com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing

/**
 * Timing Constants für Animationen, Timeouts und zeitbezogene Konfigurationen
 */

// ============================
// UI TIMING CONSTANTS
// ============================
object UIConstants {
    /** Standard-Animation-Dauer in Millisekunden */
    
    /** Kurze Animation-Dauer in Millisekunden */
    
    /** Lange Animation-Dauer für aufwändige Animationen in Millisekunden */
    const val ANIMATION_DURATION_LONG_MS = 3000L
    
    /** Debounce-Delay für Benutzereingaben in Millisekunden */
    
    /** Auto-Dismiss Zeit für Snackbars in Millisekunden */
    
    /** Auto-Dismiss Zeit für Error Messages in Millisekunden */
    
    /** Delay für UI-Stabilisierung nach State-Änderungen */
    const val UI_STABILITY_DELAY_MS = 300L

    // ENTFERNT (v1.34.3): fuenf Konstanten ohne Verwender. INPUT_DEBOUNCE_MS ist der Rest der
    // Slider-Entprellung, die aus DimmerViewModel entfernt wurde, weil ihr Job am viewModelScope
    // hing und beim Verlassen der App vor dem delay() starb. Sie stehenzulassen lud dazu ein,
    // genau diese Falle neu zu bauen - Hergang im Skill cfalarm-dimmer-und-dnd.
}
