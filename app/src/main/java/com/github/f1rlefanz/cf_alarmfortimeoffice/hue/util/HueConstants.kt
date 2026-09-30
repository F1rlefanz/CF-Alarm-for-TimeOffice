package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

/**
 * Central constants for Philips Hue integration
 */
object HueConstants {
    
    // =============================================================================
    // PHILIPS HUE API SPECIFICATIONS
    // =============================================================================
    
    /**
     * Philips Hue Bridge discovery and connection
     */
    object Bridge {
        // Hue Bridge API endpoints
        const val API_BASE_PATH = "/api"
        const val SCHEDULES_ENDPOINT = "/schedules"
    }
    
    /**
     * Light control value ranges and limits
     */
    object Lights {
        // Brightness range (Hue API specification)
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 254
        
        // Hue range (0° - 360° mapped to 0-65535)
        const val MIN_HUE = 0
        const val MAX_HUE = 65535
        
        // Saturation range 
        const val MIN_SATURATION = 0
        const val MAX_SATURATION = 254
        
        // Color temperature range (mireds)
        const val MIN_COLOR_TEMPERATURE = 153  // ~6500K (cool white)
        const val MAX_COLOR_TEMPERATURE = 500  // ~2000K (warm white)
        
        // Transition times (in deciseconds, 1/10 second)
        const val MIN_TRANSITION_TIME = 0
        const val MAX_TRANSITION_TIME = 65535
        const val SLOW_TRANSITION_TIME = 30    // 3 seconds
        
        // Alert types
        const val ALERT_NONE = "none"
        const val ALERT_LSELECT = "lselect"    // Multiple flashes
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
    }
}
