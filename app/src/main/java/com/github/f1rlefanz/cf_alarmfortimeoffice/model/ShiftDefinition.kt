package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * IMMUTABLE Shift Definition Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Unveränderliche Keywords-Liste für bessere Cache-Performance
 */
@Immutable
@Serializable
data class ShiftDefinition(
    val id: String,
    val name: String,
    val keywords: List<String>,
    @Serializable(with = LocalTimeSerializer::class)
    val alarmTime: LocalTime,
    val isEnabled: Boolean = true
) {
    /**
     * Get alarm time as formatted string for display
     */
    fun getAlarmTimeFormatted(): String {
        return String.format("%02d:%02d", alarmTime.hour, alarmTime.minute)
    }
    
    /**
     * Get alarm local time for scheduling
     */
    fun getAlarmLocalTime(): LocalTime = alarmTime
    
    /**
     * Check if this shift definition matches any of the given keywords
     * Uses whole-word matching to prevent false positives
     */
    fun matchesKeywords(eventTitle: String): Boolean {
        val title = eventTitle.lowercase()
        return keywords.any { keyword ->
            val keywordLower = keyword.lowercase()
            // Use word boundaries to match complete words only
            val regex = "\\b${Regex.escape(keywordLower)}\\b".toRegex()
            regex.containsMatchIn(title)
        }
    }
}

/**
 * Utility object for creating ShiftDefinition instances
 */
object ShiftDefinitionFactory {
    /**
     * Create a default shift definition for testing
     */
    fun createDefault(id: String = "default"): ShiftDefinition {
        return ShiftDefinition(
            id = id,
            name = "Standard Schicht",
            keywords = listOf("schicht", "arbeit", "dienst"),
            alarmTime = LocalTime.of(6, 0),
            isEnabled = true
        )
    }
    
    /**
     * Create sample shift definitions
     */
    fun getSampleDefinitions(): List<ShiftDefinition> {
        return listOf(
            ShiftDefinition(
                id = "early_shift",
                name = "Frühschicht",
                keywords = listOf("F", "IMCF"),
                alarmTime = LocalTime.of(5, 30),
                isEnabled = true
            ),
            ShiftDefinition(
                id = "late_shift", 
                name = "Spätschicht",
                keywords = listOf("S", "IMCS"),
                alarmTime = LocalTime.of(12, 30),
                isEnabled = true
            ),
            ShiftDefinition(
                id = "night_shift",
                name = "Nachtschicht", 
                keywords = listOf("N", "IMCN"),
                alarmTime = LocalTime.of(20, 0),
                isEnabled = true
            ),
            ShiftDefinition(
                id = "s2_shift",
                name = "S2",
                keywords = listOf("S2"),
                alarmTime = LocalTime.of(14, 30),
                isEnabled = true
            ),
            ShiftDefinition(
                id = "intermediate_shift",
                name = "Zwischendienst",
                keywords = listOf("IMCZ"),
                alarmTime = LocalTime.of(7, 0),
                isEnabled = true
            )
        )
    }
}
