package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueColor
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlin.math.*

/**
 * Color conversion utilities for Philips Hue lights
 * 
 * Provides conversion between different color spaces:
 * - RGB ↔ HSV
 * - RGB → XY (CIE 1931 color space)
 * - Color temperature calculations
 * - Hue-specific color mappings
 * 
 * Implementation follows Philips Hue API specifications and color science standards.
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1
 */
object HueColorConverter {
    
    /**
     * Philips Hue color gamut for most bulbs (Gamut B)
     * Based on official Philips documentation
     */
    private val HUE_GAMUT_B = arrayOf(
        floatArrayOf(0.675f, 0.322f), // Red
        floatArrayOf(0.4091f, 0.518f), // Green  
        floatArrayOf(0.167f, 0.04f)    // Blue
    )
    
    /**
     * Color temperature range for Philips Hue bulbs
     */
    const val MIN_COLOR_TEMPERATURE = 153 // ~6500K (cool white)
    const val MAX_COLOR_TEMPERATURE = 500 // ~2000K (warm white)
    
    /**
     * Converts RGB color to Hue HSV values
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @return HueColor with hue (0-65535), saturation (0-254), and RGB hex
     */
    fun rgbToHueColor(red: Int, green: Int, blue: Int): HueColor {
        Logger.d(LogTags.HUE_LIGHTS, "Converting RGB($red, $green, $blue) to Hue color")
        
        try {
            // Normalize RGB values to 0-1 range
            val r = red / 255.0f
            val g = green / 255.0f
            val b = blue / 255.0f
            
            // Convert to HSV
            val hsv = rgbToHsv(r, g, b)
            
            // Convert to Hue ranges
            val hue = (hsv.hue * 65535 / 360).roundToInt().coerceIn(0, 65535)
            val saturation = (hsv.saturation * 254).roundToInt().coerceIn(0, 254)
            
            // Convert to XY color space for advanced bulbs
            val xy = rgbToXy(r, g, b)
            
            // Create RGB hex string
            val rgbHex = "#%02X%02X%02X".format(red, green, blue)
            
            val hueColor = HueColor(
                hue = hue,
                saturation = saturation,
                xy = listOf(xy.first, xy.second),
                rgb = rgbHex
            )
            
            Logger.d(LogTags.HUE_LIGHTS, "RGB conversion result: hue=$hue, sat=$saturation, xy=[${xy.first}, ${xy.second}]")
            return hueColor
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error converting RGB to Hue color", e)
            // Return safe fallback color (white)
            return HueColor(
                hue = 0,
                saturation = 0,
                xy = listOf(0.3127f, 0.3290f), // D65 white point
                rgb = "#FFFFFF"
            )
        }
    }
    
    /**
     * Converts Hue color back to RGB
     * 
     * @param hueColor HueColor object with hue/saturation or XY values
     * @return Triple of RGB values (0-255)
     */
    fun hueColorToRgb(hueColor: HueColor): Triple<Int, Int, Int> {
        Logger.d(LogTags.HUE_LIGHTS, "Converting Hue color to RGB")
        
        try {
            // Parse RGB from hex if available
            hueColor.rgb?.let { rgbHex ->
                if (rgbHex.startsWith("#") && rgbHex.length == 7) {
                    val red = rgbHex.substring(1, 3).toInt(16)
                    val green = rgbHex.substring(3, 5).toInt(16) 
                    val blue = rgbHex.substring(5, 7).toInt(16)
                    return Triple(red, green, blue)
                }
            }
            
            // Convert from XY if available
            hueColor.xy?.let { xy ->
                if (xy.size >= 2) {
                    return xyToRgb(xy[0], xy[1])
                }
            }
            
            // Convert from HSV
            val hue = (hueColor.hue ?: 0) * 360.0f / 65535.0f
            val saturation = (hueColor.saturation ?: 0) / 254.0f
            val value = 1.0f // Assume full brightness for color conversion
            
            return hsvToRgb(hue, saturation, value)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error converting Hue color to RGB", e)
            return Triple(255, 255, 255) // White fallback
        }
    }
    
    /**
     * Converts RGB to HSV color space
     */
    private fun rgbToHsv(r: Float, g: Float, b: Float): HSV {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        
        // Calculate hue
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0) it + 360f else it }
        
        // Calculate saturation
        val saturation = if (max == 0f) 0f else delta / max
        
        // Value is the maximum component
        val value = max
        
        return HSV(hue, saturation, value)
    }
    
    /**
     * Converts HSV to RGB color space
     */
    private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Triple<Int, Int, Int> {
        val c = value * saturation
        val x = c * (1 - abs(((hue / 60f) % 2) - 1))
        val m = value - c
        
        val (r1, g1, b1) = when ((hue / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            5 -> Triple(c, 0f, x)
            else -> Triple(0f, 0f, 0f)
        }
        
        val red = ((r1 + m) * 255).roundToInt().coerceIn(0, 255)
        val green = ((g1 + m) * 255).roundToInt().coerceIn(0, 255)
        val blue = ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        
        return Triple(red, green, blue)
    }
    
    /**
     * Converts RGB to XY color space (CIE 1931)
     * This is the native color space for advanced Philips Hue bulbs
     */
    private fun rgbToXy(r: Float, g: Float, b: Float): Pair<Float, Float> {
        // Apply gamma correction
        val red = gammaCorrection(r)
        val green = gammaCorrection(g) 
        val blue = gammaCorrection(b)
        
        // Convert to XYZ color space using sRGB matrix
        val x = red * 0.664511f + green * 0.154324f + blue * 0.162028f
        val y = red * 0.283881f + green * 0.668433f + blue * 0.047685f
        val z = red * 0.000088f + green * 0.072310f + blue * 0.986039f
        
        // Convert to xy chromaticity coordinates
        val sum = x + y + z
        
        return if (sum == 0f) {
            // Return D65 white point if calculation fails
            Pair(0.3127f, 0.3290f)
        } else {
            val xyX = x / sum
            val xyY = y / sum
            
            // Ensure values are within Hue gamut
            gamutCorrection(xyX, xyY)
        }
    }
    
    /**
     * Converts XY color space back to RGB
     */
    private fun xyToRgb(x: Float, y: Float): Triple<Int, Int, Int> {
        // Calculate z coordinate
        val z = 1.0f - x - y
        
        // Convert to XYZ (assuming Y = 1 for maximum brightness)
        val xyzX = x / y
        val xyzY = 1.0f
        val xyzZ = z / y
        
        // Convert XYZ to RGB using inverse sRGB matrix
        var red = xyzX * 1.656492f - xyzY * 0.354851f - xyzZ * 0.255038f
        var green = -xyzX * 0.707196f + xyzY * 1.655397f + xyzZ * 0.036152f
        var blue = xyzX * 0.051713f - xyzY * 0.121364f + xyzZ * 1.011530f
        
        // Apply reverse gamma correction
        red = reverseGammaCorrection(red)
        green = reverseGammaCorrection(green)
        blue = reverseGammaCorrection(blue)
        
        // Convert to 0-255 range and clamp
        val redInt = (red * 255).roundToInt().coerceIn(0, 255)
        val greenInt = (green * 255).roundToInt().coerceIn(0, 255) 
        val blueInt = (blue * 255).roundToInt().coerceIn(0, 255)
        
        return Triple(redInt, greenInt, blueInt)
    }
    
    /**
     * Applies gamma correction for sRGB color space
     */
    private fun gammaCorrection(component: Float): Float {
        return if (component > 0.04045f) {
            ((component + 0.055f) / 1.055f).pow(2.4f)
        } else {
            component / 12.92f
        }
    }
    
    /**
     * Applies reverse gamma correction
     */
    private fun reverseGammaCorrection(component: Float): Float {
        val clamped = component.coerceIn(0f, 1f)
        return if (clamped > 0.0031308f) {
            1.055f * clamped.pow(1.0f / 2.4f) - 0.055f
        } else {
            12.92f * clamped
        }
    }
    
    /**
     * Ensures XY values are within the Philips Hue color gamut
     */
    private fun gamutCorrection(x: Float, y: Float): Pair<Float, Float> {
        // Check if point is inside triangle formed by color gamut
        if (isInGamut(x, y)) {
            return Pair(x, y)
        }
        
        // Find closest point on gamut triangle
        return getClosestPointInGamut(x, y)
    }
    
    /**
     * Checks if XY point is within the Philips Hue color gamut
     */
    private fun isInGamut(x: Float, y: Float): Boolean {
        val v1 = HUE_GAMUT_B[2][0] - HUE_GAMUT_B[0][0]
        val v2 = HUE_GAMUT_B[2][1] - HUE_GAMUT_B[0][1]
        val q = x - HUE_GAMUT_B[0][0]
        val s = y - HUE_GAMUT_B[0][1]
        val s1 = q * v2 - s * v1
        
        val v3 = HUE_GAMUT_B[1][0] - HUE_GAMUT_B[0][0]
        val v4 = HUE_GAMUT_B[1][1] - HUE_GAMUT_B[0][1]
        val s2 = v3 * s - v4 * q
        
        return (s1 >= 0 && s2 >= 0 && s1 + s2 <= v3 * v2 - v4 * v1)
    }
    
    /**
     * Finds the closest point within the color gamut triangle
     */
    private fun getClosestPointInGamut(x: Float, y: Float): Pair<Float, Float> {
        val red = HUE_GAMUT_B[0]
        val green = HUE_GAMUT_B[1]
        val blue = HUE_GAMUT_B[2]
        
        // Calculate distances to each edge of the triangle
        val distToRedGreen = getDistanceToLine(x, y, red[0], red[1], green[0], green[1])
        val distToGreenBlue = getDistanceToLine(x, y, green[0], green[1], blue[0], blue[1])
        val distToBlueRed = getDistanceToLine(x, y, blue[0], blue[1], red[0], red[1])
        
        // Find the minimum distance and corresponding edge
        val minDist = minOf(distToRedGreen.second, distToGreenBlue.second, distToBlueRed.second)
        
        return when (minDist) {
            distToRedGreen.second -> distToRedGreen.first
            distToGreenBlue.second -> distToGreenBlue.first
            else -> distToBlueRed.first
        }
    }
    
    /**
     * Calculates the closest point on a line segment and its distance
     */
    private fun getDistanceToLine(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Pair<Pair<Float, Float>, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        
        if (dx == 0f && dy == 0f) {
            return Pair(Pair(x1, y1), sqrt((px - x1).pow(2) + (py - y1).pow(2)))
        }
        
        val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        val tClamped = t.coerceIn(0f, 1f)
        
        val closestX = x1 + tClamped * dx
        val closestY = y1 + tClamped * dy
        val distance = sqrt((px - closestX).pow(2) + (py - closestY).pow(2))
        
        return Pair(Pair(closestX, closestY), distance)
    }
    
    /**
     * Converts color temperature in Kelvin to Hue mireds
     * 
     * @param kelvin Color temperature in Kelvin (2000-6500)
     * @return Hue color temperature value (153-500)
     */
    fun kelvinToHueMireds(kelvin: Int): Int {
        val mireds = 1_000_000 / kelvin.coerceIn(2000, 6500)
        return mireds.coerceIn(MIN_COLOR_TEMPERATURE, MAX_COLOR_TEMPERATURE)
    }
    
    /**
     * Converts Hue mireds to color temperature in Kelvin
     * 
     * @param mireds Hue color temperature value (153-500)
     * @return Color temperature in Kelvin
     */
    fun hueMiredsToKelvin(mireds: Int): Int {
        return 1_000_000 / mireds.coerceIn(MIN_COLOR_TEMPERATURE, MAX_COLOR_TEMPERATURE)
    }
    
    /**
     * Creates a HueColor from common color presets
     */
    fun getPresetColor(preset: ColorPreset): HueColor {
        return when (preset) {
            ColorPreset.WARM_WHITE -> HueColor(
                hue = 0,
                saturation = 0,
                xy = listOf(0.4573f, 0.4100f),
                rgb = "#FFB46B"
            )
            ColorPreset.COOL_WHITE -> HueColor(
                hue = 0,
                saturation = 0,
                xy = listOf(0.3127f, 0.3290f),
                rgb = "#FFFFFF"
            )
            ColorPreset.RED -> rgbToHueColor(255, 0, 0)
            ColorPreset.GREEN -> rgbToHueColor(0, 255, 0)
            ColorPreset.BLUE -> rgbToHueColor(0, 0, 255)
            ColorPreset.YELLOW -> rgbToHueColor(255, 255, 0)
            ColorPreset.PURPLE -> rgbToHueColor(128, 0, 128)
            ColorPreset.ORANGE -> rgbToHueColor(255, 165, 0)
            ColorPreset.PINK -> rgbToHueColor(255, 192, 203)
            ColorPreset.CYAN -> rgbToHueColor(0, 255, 255)
        }
    }
    
    /**
     * Data class for HSV color representation
     */
    private data class HSV(
        val hue: Float,        // 0-360
        val saturation: Float, // 0-1
        val value: Float       // 0-1
    )
    
    /**
     * Enum for common color presets
     */
    enum class ColorPreset {
        WARM_WHITE,
        COOL_WHITE,
        RED,
        GREEN,
        BLUE,
        YELLOW,
        PURPLE,
        ORANGE,
        PINK,
        CYAN
    }
}
