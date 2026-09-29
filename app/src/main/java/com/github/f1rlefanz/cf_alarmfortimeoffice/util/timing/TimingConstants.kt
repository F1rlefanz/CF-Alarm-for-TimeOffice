package com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing

/**
 * Timing Constants für Animationen, Timeouts und zeitbezogene Konfigurationen.
 *
 * `INPUT_DEBOUNCE_MS` bewusst entfernt - Entprellung am `viewModelScope` ist eine Falle,
 * siehe Skill `cfalarm-dimmer-und-dnd`.
 */

// ============================
// UI TIMING CONSTANTS
// ============================
object UIConstants {
    /** Delay für UI-Stabilisierung nach State-Änderungen */
    const val UI_STABILITY_DELAY_MS = 300L
}
