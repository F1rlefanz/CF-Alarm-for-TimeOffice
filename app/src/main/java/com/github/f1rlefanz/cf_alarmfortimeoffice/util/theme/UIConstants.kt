package com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme

import androidx.compose.ui.unit.dp

/**
 * UI Constants für Theme, Spacing, Dimensionen und visuelle Elemente
 */

// ============================
// SPACING & DIMENSION CONSTANTS
// ============================
object SpacingConstants {
    // Micro-Abstände für sehr kleine UI-Elemente
    val SPACING_MICRO = 2.dp
    val SPACING_TINY = 6.dp
    
    // Standard-Abstände
    val SPACING_EXTRA_SMALL = 4.dp
    val SPACING_SMALL = 8.dp
    val SPACING_MEDIUM = 12.dp
    val SPACING_LARGE = 16.dp
    val SPACING_EXTRA_LARGE = 24.dp
    val SPACING_XXL = 32.dp
    val SPACING_XXXL = 48.dp
    
    // Padding-Konstanten
    val PADDING_SCREEN_HORIZONTAL = 16.dp
    val PADDING_SCREEN_VERTICAL = 16.dp
    val PADDING_CARD = 16.dp
    val PADDING_SMALL = 8.dp
    
    // Icon-Größen
    val ICON_SIZE_SMALL = 16.dp
    val ICON_SIZE_MEDIUM = 18.dp
    val ICON_SIZE_STANDARD = 20.dp
    val ICON_SIZE_LARGE = 24.dp
    val ICON_SIZE_EXTRA_LARGE = 32.dp
    val ICON_SIZE_XXL = 48.dp
    val ICON_SIZE_XXXL = 64.dp
    val ICON_SIZE_GIANT = 80.dp
    
    // Spezielle UI-Elemente
    val APP_ICON_SIZE = 120.dp
    val FULLSCREEN_ELEMENT_SIZE = 140.dp
    
    // Button-Dimensionen
    val BUTTON_HEIGHT_STANDARD = 48.dp
    val BUTTON_HEIGHT_LARGE = 56.dp
    val BUTTON_HEIGHT_FULLSCREEN = 80.dp
    val BUTTON_MIN_WIDTH = 64.dp
    
    // Card & Surface
    val CARD_ELEVATION = 4.dp
    val SURFACE_CORNER_RADIUS = 8.dp
    val CARD_CORNER_RADIUS = 12.dp
    val FULLSCREEN_CORNER_RADIUS = 20.dp
}

// ============================
// LAYOUT FRACTION CONSTANTS
// ============================
object LayoutFractions {
    /** Breite für Dialoge (90% der Bildschirmbreite) */
    const val DIALOG_WIDTH = 0.9f
    
    /** Höhe für Dialoge (80% der Bildschirmhöhe) */  
    const val DIALOG_HEIGHT = 0.8f
    
    /** Standardbreite für Cards */
    const val CARD_WIDTH = 0.85f
    
    /** Vollbreite für wichtige Elemente */
    const val FULL_WIDTH = 1.0f
    
    /** Halbe Breite für zweispaltige Layouts */
    const val HALF_WIDTH = 0.5f
}

// ============================
// ALPHA & TRANSPARENCY CONSTANTS
// ============================
object AlphaValues {
    /** Sehr transparente Overlays */
    const val VERY_LIGHT = 0.05f
    
    /** Leichte Transparenz */
    const val LIGHT = 0.1f
    
    /** Mittlere Transparenz */
    const val MEDIUM = 0.15f
    
    /** Stärkere Transparenz */
    const val STRONG = 0.3f
    
    /** Für Disabled-States */
    const val DISABLED = 0.38f
    
    /** Für Surface-Variants */
    const val SURFACE_VARIANT = 0.5f
}

// ============================
// FONT SIZE CONSTANTS
// ============================
object FontSizes {
    /** Kleine Schriftgröße für Details */
    const val SMALL = 12
    
    /** Standard-Schriftgröße */
    const val MEDIUM = 14
    
    /** Große Schriftgröße für wichtige Elemente */
    const val LARGE = 18
    
    /** Extra große Schriftgröße */
    const val EXTRA_LARGE = 24
    
    /** Countdown-Timer große Zahlen */
    const val COUNTDOWN_LARGE = 28
    
    /** Countdown-Timer normale Zahlen */
    const val COUNTDOWN_NORMAL = 24
}

// ============================
// BORDER & STROKE CONSTANTS
// ============================
object BorderConstants {
    /** Standard-Border-Breite */
    const val STANDARD_WIDTH = 1
    
    /** Hervorgehobene Border-Breite */
    const val HIGHLIGHTED_WIDTH = 2
    
    /** Dicke Border für spezielle Fälle */
    const val THICK_WIDTH = 3
}

// ============================
// UI COLOR CONSTANTS
// ============================
object UIColors {
    // Standard semantic colors for status indicators
    const val STATUS_SUCCESS = 0xFF4CAF50L // Green
    const val STATUS_WARNING = 0xFFFF9800L // Orange
    const val STATUS_INFO = 0xFF2196F3L    // Blue
    const val STATUS_ERROR = 0xFFF44336L   // Red
    const val STATUS_LOADING = 0xFF9C27B0L // Purple
    
    // Warning and info container colors for light theme
    const val WARNING_CONTAINER_LIGHT = 0xFFFFF3E0L // Light orange
    const val ON_WARNING_CONTAINER_LIGHT = 0xFF5D4037L // Dark brown
    const val WARNING_CONTAINER_DARK = 0xFF3E2723L // Dark brown
    const val ON_WARNING_CONTAINER_DARK = 0xFFFFCC80L // Light orange
    const val WARNING_COLOR = 0xFFFF9800L // Orange
    const val ON_WARNING_COLOR = 0xFFFFFFFFL // White
}

// ============================
// UI GRAPHICS CONSTANTS
// ============================
object GraphicsConstants {
    /** Standard radius for gradient effects */
    const val GRADIENT_RADIUS = 500f
    
    /** Standard corner radius for UI elements */
    const val STANDARD_CORNER_RADIUS = 8f
    
    /** Large corner radius for prominent elements */
    const val LARGE_CORNER_RADIUS = 12f
    
    /** Extra large corner radius for special cases */
    const val EXTRA_LARGE_CORNER_RADIUS = 20f
}

// ============================
// ALARM & VIBRATION CONSTANTS
// ============================
object UIConstants {
    /** Animation duration for UI transitions */
    const val ANIMATION_DURATION_SHORT = 250
    const val ANIMATION_DURATION_MEDIUM = 500
    const val ANIMATION_DURATION_LONG = 1000
    
    /** Vibration pattern for alarms: [delay, vibrate, pause, vibrate, pause, vibrate] */
    val ALARM_VIBRATION_PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000)
    
    /** Standard animation delays */
    const val STANDARD_DELAY = 16L // 60fps
    const val LONG_DELAY = 33L     // 30fps
    
    /** Progress indicator sizes */
    const val PROGRESS_INDICATOR_SIZE = 40
    const val LARGE_PROGRESS_INDICATOR_SIZE = 60
}
