package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Eckenradien passend zu SpacingConstants.CARD_CORNER_RADIUS (12dp) /
 * SURFACE_CORNER_RADIUS (8dp), damit Material3-Defaultkomponenten
 * (Buttons, Dialoge, Sheets) zum bestehenden Card-Design passen.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
