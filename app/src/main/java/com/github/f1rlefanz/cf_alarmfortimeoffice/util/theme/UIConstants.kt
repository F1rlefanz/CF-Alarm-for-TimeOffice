package com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme

import androidx.compose.ui.unit.dp

/**
 * UI Constants für Theme, Spacing, Dimensionen und visuelle Elemente
 */

// ============================
// SPACING & DIMENSION CONSTANTS
// ============================
object SpacingConstants {
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
    val PADDING_CARD = 16.dp
    
    // Icon-Größen
    val ICON_SIZE_SMALL = 16.dp
    val ICON_SIZE_MEDIUM = 18.dp
    val ICON_SIZE_STANDARD = 20.dp
    val ICON_SIZE_LARGE = 24.dp
    val ICON_SIZE_EXTRA_LARGE = 32.dp
    val ICON_SIZE_XXL = 48.dp
    val ICON_SIZE_XXXL = 64.dp
    
    // Spezielle UI-Elemente
    val APP_ICON_SIZE = 120.dp
    
    // Button-Dimensionen
    val BUTTON_HEIGHT_LARGE = 56.dp
    
    // Card & Surface
    val CARD_ELEVATION = 4.dp
    val SURFACE_CORNER_RADIUS = 8.dp
    val CARD_CORNER_RADIUS = 12.dp
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
    
    /** Vollbreite für wichtige Elemente */
    
    /** Halbe Breite für zweispaltige Layouts */
}

// ============================
// UI COLOR CONSTANTS
// ============================
object UIColors {
    // Standard semantic colors for status indicators
    const val STATUS_SUCCESS = 0xFF4CAF50L // Green
    const val STATUS_WARNING = 0xFFFF9800L // Orange
    const val STATUS_INFO = 0xFF2196F3L    // Blue
    const val STATUS_LOADING = 0xFF9C27B0L // Purple
}

// ============================
// UI GRAPHICS CONSTANTS
// ============================
object GraphicsConstants {
    /** Standard radius for gradient effects */
    const val GRADIENT_RADIUS = 500f
    
    /** Standard corner radius for UI elements */
    
    /** Large corner radius for prominent elements */
    
    /** Extra large corner radius for special cases */
}

// ============================
// ALARM & VIBRATION CONSTANTS
// ============================
object UIConstants {
    /** Animation duration for UI transitions */
    
    /** Vibration pattern for alarms: [delay, vibrate, pause, vibrate, pause, vibrate] */
    
    /** Standard animation delays */
    
    /** Progress indicator sizes */

    // ENTFERNT (v1.34.3): 30 Gestaltungs-Konstanten ohne einen einzigen Verwender - Abstaende,
    // Eckenradien, Animationsdauern und ein kompletter Warnfarben-Satz, alle auf Vorrat angelegt.
    // Dieselbe "fertige API fuer spaeter"-Falle, die dieses Projekt schon einmal in
    // NetworkStateMonitor gefunden hat. Wer einen Wert braucht, legt ihn an - eine Zeile.
}
