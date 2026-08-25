package com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing

/**
 * Timing Constants für Animationen, Timeouts und zeitbezogene Konfigurationen.
 *
 * ENTFERNT (v1.34.3 und v1.34.5): sechs Konstanten ohne Verwender, dazu ihre verwaisten
 * KDoc-Blöcke, die der erste Durchgang stehen ließ.
 *
 * `INPUT_DEBOUNCE_MS` war dabei der Rest der Slider-Entprellung, die aus `DimmerViewModel`
 * entfernt wurde, weil ihr Job am `viewModelScope` hing und beim Verlassen der App vor dem
 * `delay()` starb — der Prefs-Wert war geschrieben, das Overlay behielt bis zur nächsten
 * Fenstergrenze die alte Verdunkelung. Die Konstante stehenzulassen lud dazu ein, genau diese
 * Falle neu zu bauen; Hergang im Skill `cfalarm-dimmer-und-dnd`.
 */

// ============================
// UI TIMING CONSTANTS
// ============================
object UIConstants {
    /** Delay für UI-Stabilisierung nach State-Änderungen */
    const val UI_STABILITY_DELAY_MS = 300L
}
