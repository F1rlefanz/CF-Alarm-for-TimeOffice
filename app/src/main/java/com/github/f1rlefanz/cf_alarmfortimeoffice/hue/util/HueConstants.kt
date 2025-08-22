package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

/**
 * Central constants for Philips Hue integration
 * 
 * Contains all configuration values, limits, and defaults used throughout
 * the Hue integration system. Centralized for easy maintenance and consistency.
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1
 */
object HueConstants {
    
    // =============================================================================
    // PHILIPS HUE API SPECIFICATIONS
    // =============================================================================
    
    /**
     * Philips Hue Bridge discovery and connection
     */
    object Bridge {
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val CONNECTION_TIMEOUT_MS = 5_000L
        const val USER_CREATION_TIMEOUT_MS = 30_000L
        const val BRIDGE_LINK_BUTTON_TIMEOUT_SECONDS = 30
        const val MAX_DISCOVERY_ATTEMPTS = 3
        const val BRIDGE_PORT = 443
        
        // Standard Hue Bridge mDNS service
        const val MDNS_SERVICE_TYPE = "_hue._tcp.local."
        
        // Hue Bridge API endpoints
        const val API_BASE_PATH = "/api"
        const val CONFIG_ENDPOINT = "/config"
        const val LIGHTS_ENDPOINT = "/lights"
        const val GROUPS_ENDPOINT = "/groups"
        const val SCHEDULES_ENDPOINT = "/schedules"
        
        // Device type for user creation
        const val DEVICE_TYPE = "CF-Alarm#android"
    }
    
    /**
     * Light control value ranges and limits
     */
    object Lights {
        // Brightness range (Hue API specification)
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 254
        const val DEFAULT_BRIGHTNESS = 127
        
        // Hue range (0° - 360° mapped to 0-65535)
        const val MIN_HUE = 0
        const val MAX_HUE = 65535
        
        // Saturation range 
        const val MIN_SATURATION = 0
        const val MAX_SATURATION = 254
        const val DEFAULT_SATURATION = 254
        
        // Color temperature range (mireds)
        const val MIN_COLOR_TEMPERATURE = 153  // ~6500K (cool white)
        const val MAX_COLOR_TEMPERATURE = 500  // ~2000K (warm white)
        const val DEFAULT_COLOR_TEMPERATURE = 366 // ~2700K (warm white)
        
        // Transition times (in deciseconds, 1/10 second)
        const val MIN_TRANSITION_TIME = 0
        const val MAX_TRANSITION_TIME = 65535
        const val DEFAULT_TRANSITION_TIME = 10 // 1 second
        const val FAST_TRANSITION_TIME = 4     // 0.4 seconds
        const val SLOW_TRANSITION_TIME = 30    // 3 seconds
        
        // Alert types
        const val ALERT_NONE = "none"
        const val ALERT_SELECT = "select"      // Single flash
        const val ALERT_LSELECT = "lselect"    // Multiple flashes
        
        // Effect types
        const val EFFECT_NONE = "none"
        const val EFFECT_COLORLOOP = "colorloop"
        
        // XY color space limits (CIE 1931)
        const val MIN_XY_VALUE = 0.0f
        const val MAX_XY_VALUE = 1.0f
    }
    
    /**
     * Default configurations for common scenarios
     */
    object Defaults {
        // Alarm lighting defaults
        val ALARM_BRIGHTNESS = Lights.MAX_BRIGHTNESS
        val ALARM_TRANSITION_TIME = Lights.FAST_TRANSITION_TIME
        const val ALARM_DURATION = 15
        
        // Notification lighting defaults  
        val NOTIFICATION_BRIGHTNESS = (Lights.MAX_BRIGHTNESS * 0.8).toInt()
        val NOTIFICATION_ALERT = Lights.ALERT_LSELECT
        const val NOTIFICATION_DURATION = 5
        
        // Wake-up lighting defaults (gradual)
        val WAKEUP_START_BRIGHTNESS = Lights.MIN_BRIGHTNESS
        val WAKEUP_END_BRIGHTNESS = (Lights.MAX_BRIGHTNESS * 0.7).toInt()
        val WAKEUP_TRANSITION_TIME = Lights.SLOW_TRANSITION_TIME
        
        // Evening/night defaults
        val EVENING_BRIGHTNESS = (Lights.MAX_BRIGHTNESS * 0.3).toInt()
        val EVENING_COLOR_TEMPERATURE = Lights.MAX_COLOR_TEMPERATURE // Warm
        val EVENING_TRANSITION_TIME = Lights.SLOW_TRANSITION_TIME
    }
    
    /**
     * Validation helpers
     */
    object Validation {
        /**
         * Validates if a brightness value is within Hue range
         */
        fun isValidBrightness(brightness: Int): Boolean {
            return brightness in Lights.MIN_BRIGHTNESS..Lights.MAX_BRIGHTNESS
        }
        
        /**
         * Validates if a hue value is within Hue range
         */
        fun isValidHue(hue: Int): Boolean {
            return hue in Lights.MIN_HUE..Lights.MAX_HUE
        }
        
        /**
         * Validates if a saturation value is within Hue range
         */
        fun isValidSaturation(saturation: Int): Boolean {
            return saturation in Lights.MIN_SATURATION..Lights.MAX_SATURATION
        }
        
        /**
         * Validates if a color temperature value is within Hue range
         */
        fun isValidColorTemperature(colorTemperature: Int): Boolean {
            return colorTemperature in Lights.MIN_COLOR_TEMPERATURE..Lights.MAX_COLOR_TEMPERATURE
        }
        
        /**
         * Validates if a transition time is within Hue range
         */
        fun isValidTransitionTime(transitionTime: Int): Boolean {
            return transitionTime in Lights.MIN_TRANSITION_TIME..Lights.MAX_TRANSITION_TIME
        }
        
        /**
         * Validates if XY color coordinates are within valid range
         */
        fun isValidXY(x: Float, y: Float): Boolean {
            return x in Lights.MIN_XY_VALUE..Lights.MAX_XY_VALUE && 
                   y in Lights.MIN_XY_VALUE..Lights.MAX_XY_VALUE
        }
    }
    
    /**
     * Helper functions for common operations
     */
    object Utils {
        /**
         * Clamps brightness to valid Hue range
         */
        fun clampBrightness(brightness: Int): Int {
            return brightness.coerceIn(Lights.MIN_BRIGHTNESS, Lights.MAX_BRIGHTNESS)
        }
        
        /**
         * Clamps hue to valid range
         */
        fun clampHue(hue: Int): Int {
            return hue.coerceIn(Lights.MIN_HUE, Lights.MAX_HUE)
        }
        
        /**
         * Clamps saturation to valid range
         */
        fun clampSaturation(saturation: Int): Int {
            return saturation.coerceIn(Lights.MIN_SATURATION, Lights.MAX_SATURATION)
        }
        
        /**
         * Clamps color temperature to valid range
         */
        fun clampColorTemperature(colorTemperature: Int): Int {
            return colorTemperature.coerceIn(Lights.MIN_COLOR_TEMPERATURE, Lights.MAX_COLOR_TEMPERATURE)
        }
        
        /**
         * Converts percentage (0-100) to Hue brightness (1-254)
         */
        fun percentageToBrightness(percentage: Int): Int {
            val clamped = percentage.coerceIn(0, 100)
            return if (clamped == 0) Lights.MIN_BRIGHTNESS 
                   else ((clamped / 100.0f) * (Lights.MAX_BRIGHTNESS - Lights.MIN_BRIGHTNESS) + Lights.MIN_BRIGHTNESS).toInt()
        }
        
        /**
         * Converts Hue brightness (1-254) to percentage (0-100)
         */
        fun brightnessToPercentage(brightness: Int): Int {
            val clamped = clampBrightness(brightness)
            return ((clamped - Lights.MIN_BRIGHTNESS).toFloat() / (Lights.MAX_BRIGHTNESS - Lights.MIN_BRIGHTNESS) * 100).toInt()
        }
        
        /**
         * Creates a safe bridge username from device info
         */
        fun generateBridgeUsername(): String {
            return "cf_alarm_${System.currentTimeMillis()}"
        }
        
        /**
         * Creates a unique rule ID
         */
        fun generateRuleId(): String {
            return "rule_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
    }
}
