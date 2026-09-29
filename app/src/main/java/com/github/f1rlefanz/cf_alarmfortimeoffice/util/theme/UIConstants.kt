package com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme

import androidx.compose.ui.unit.dp

/**
 * UI Constants für Theme, Spacing, Dimensionen und visuelle Elemente.
 * Keine Werte auf Vorrat: **wer einen Wert braucht, legt ihn an — das ist eine Zeile.**
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
}

// ============================
// LAYOUT FRACTION CONSTANTS
// ============================
object LayoutFractions {
    /** Breite für Dialoge (90% der Bildschirmbreite) */
    const val DIALOG_WIDTH = 0.9f

    /** Höhe für Dialoge (80% der Bildschirmhöhe) */
    const val DIALOG_HEIGHT = 0.8f
}
