package com.github.f1rlefanz.cf_alarmfortimeoffice.service

/**
 * 🎯 Shared alarm types and enums used across multiple services
 * 
 * This file contains common enum definitions to avoid duplication and 
 * maintain consistency across the alarm system.
 */

/**
 * 🎯 Reasons why the fallback service was activated
 */
enum class FallbackActivationReason(val displayName: String) {
    ACTIVITY_FAILURE("Activity konnte nicht gestartet werden"),
    ACTIVITY_KILLED("Activity wurde vom System beendet"),
    ONEPLUS_INTERFERENCE("OnePlus Power Management Interferenz"),
    USER_REQUESTED("Benutzer-angefordert"),
    EXTREME_RELIABILITY("Extreme Zuverlässigkeits-Modus"),
    TESTING("Test-Modus")
}

/**
 * 📈 Escalation levels for progressive alarm intensity
 */
enum class EscalationLevel {
    GENTLE,     // Start quietly for false alarms
    STANDARD,   // Normal alarm intensity
    AGGRESSIVE, // Higher intensity for heavy sleepers
    MAXIMUM     // Maximum intensity for extreme cases
}

/**
 * 🔍 Sources of alarm verification
 */
enum class VerificationSource { 
    FULL_SCREEN_ACTIVITY, 
    FALLBACK_SERVICE, 
    NOTIFICATION_ACTION, 
    USER_INTERACTION 
}

/**
 * ❌ Reasons for verification failure
 */
enum class VerificationFailureReason { 
    TIMEOUT, 
    ACTIVITY_KILLED, 
    ONEPLUS_INTERFERENCE, 
    SYSTEM_INTERFERENCE 
}

/**
 * 📊 Outcome of alarm execution
 */
enum class AlarmOutcome { 
    SUCCESS, 
    FAILURE 
}
