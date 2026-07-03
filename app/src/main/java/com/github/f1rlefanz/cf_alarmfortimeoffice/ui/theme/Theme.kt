package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Corporate-Design Farbschemata.
 *
 * dynamicColor (Material You) wurde bewusst standardmäßig AUSGESCHALTET:
 * die App soll auf jedem Gerät in den Unternehmensfarben erscheinen statt im
 * Wallpaper-Farbschema des Nutzers. Wer das dynamische Verhalten testen will,
 * kann dynamicColor = true übergeben.
 */
private val LightColorScheme = lightColorScheme(
    primary = BrandRed,
    onPrimary = SurfaceWhite,
    primaryContainer = BrandRedLight,
    onPrimaryContainer = BrandRedDark,

    secondary = BrandRedDark,
    onSecondary = SurfaceWhite,
    secondaryContainer = BrandRedLight,
    onSecondaryContainer = BrandRedDark,

    tertiary = WarmGrey,
    onTertiary = SurfaceWhite,

    background = OffWhite,
    onBackground = Charcoal,

    surface = SurfaceWhite,
    onSurface = Charcoal,
    surfaceVariant = OffWhite,
    onSurfaceVariant = WarmGrey,

    outline = Outline,
    outlineVariant = Outline,

    error = ErrorRed,
    onError = SurfaceWhite
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandRedLightMode,
    onPrimary = Color(0xFF5C0016),
    primaryContainer = DarkContainer,
    onPrimaryContainer = BrandRedLight,

    secondary = BrandRedLight,
    onSecondary = Color(0xFF5C0016),
    secondaryContainer = DarkContainer,
    onSecondaryContainer = BrandRedLight,

    tertiary = DarkOnSurfaceVariant,
    onTertiary = DarkBg,

    background = DarkBg,
    onBackground = Color(0xFFEDEAE7),

    surface = DarkSurface,
    onSurface = Color(0xFFEDEAE7),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,

    outline = DarkOutline,
    outlineVariant = DarkOutline,

    error = ErrorRedDark,
    onError = Color(0xFF690005)
)

@Composable
fun CFAlarmForTimeOfficeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Corporate Design > Material You
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            // bewusst deaktiviert per Default — siehe Doku oben.
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
