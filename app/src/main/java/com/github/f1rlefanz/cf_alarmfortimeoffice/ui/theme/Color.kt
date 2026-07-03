package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Corporate Design — Farbpalette
 * Übernommen von der Unternehmenswebsite (Rot/Off-White/Anthrazit).
 * Ersetzt die generierten Material3-Defaultfarben (Purple80/40 etc.).
 */

// ---- Brand ----
val BrandRed = Color(0xFFE2001A)          // Primärfarbe (Logo, CTAs)
val BrandRedDark = Color(0xFF8E0F30)      // Sekundär / dunkles Rot-Bordeaux
val BrandRedLight = Color(0xFFFBE1E4)     // helle Rot-Container (Chips, Badges)

// ---- Neutrals ----
val Charcoal = Color(0xFF2D2A28)          // Primärer Text
val WarmGrey = Color(0xFF726D68)          // Sekundärer Text
val Outline = Color(0xFFE3DED8)           // Trennlinien, Ränder
val OffWhite = Color(0xFFF0EDEA)          // App-Hintergrund
val SurfaceWhite = Color(0xFFFFFFFF)      // Card-/Oberflächenfarbe

// ---- Dark theme variants ----
val BrandRedLightMode = Color(0xFFFF6B7E) // aufgehelltes Rot für Dark-Theme-Kontrast
val DarkBg = Color(0xFF1C1B1A)
val DarkSurface = Color(0xFF242322)
val DarkSurfaceVariant = Color(0xFF3A3633)
val DarkOnSurfaceVariant = Color(0xFFC7C0BA)
val DarkOutline = Color(0xFF574F49)
val DarkContainer = Color(0xFF6B0B22)

// ---- Material "error" bleibt bewusst getrennt von BrandRed, damit
// Fehlerzustände optisch nicht mit der (roten) Markenfarbe verschmelzen ----
val ErrorRed = Color(0xFFBA1A1A)
val ErrorRedDark = Color(0xFFFFB4AB)
