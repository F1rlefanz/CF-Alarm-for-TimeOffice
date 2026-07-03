package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R

/**
 * Corporate-Design Typografie: Mulish für Überschriften, Roboto für UI/Fließtext
 * — dieselbe Paarung wie auf der Unternehmenswebsite.
 *
 * Mulish wird als EINE variable Schriftdatei (res/font/mulish_variable.ttf,
 * wght-Achse) gebündelt; die Gewichte Light/Regular/Medium/Bold werden über
 * FontVariation.weight(...) angesteuert. Das ist ab minSdk 26 unterstützt und
 * spart drei separate .ttf-Dateien gegenüber statischen Instanzen. Roboto ist
 * auf praktisch jedem Android-Gerät als Systemfont vorhanden — dafür genügt
 * FontFamily.Default, kein Bundling nötig.
 */
private fun mulish(weight: FontWeight) = Font(
    resId = R.font.mulish_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

private val MulishFamily = FontFamily(
    mulish(FontWeight.Light),
    mulish(FontWeight.Normal),
    mulish(FontWeight.Medium),
    mulish(FontWeight.Bold)
)

private val RobotoFamily = FontFamily.Default

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = MulishFamily, fontWeight = FontWeight.Light, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = 0.sp),
    displayMedium = TextStyle(fontFamily = MulishFamily, fontWeight = FontWeight.Light, fontSize = 30.sp, lineHeight = 36.sp),

    headlineLarge = TextStyle(fontFamily = MulishFamily, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = MulishFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),

    titleLarge = TextStyle(fontFamily = MulishFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    bodyLarge = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),

    labelLarge = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = RobotoFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)
