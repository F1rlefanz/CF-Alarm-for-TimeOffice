package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.Locale

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
    val isEnabled: Boolean = true,
    // "Stille Schicht" (z.B. Rufbereitschaft AD1): alarmTime bleibt Pflicht-Anker fuer
    // DND/Dimmer/Hue, aber Ton/Vibration/Vollbild-Wecker werden beim Feuern unterdrueckt.
    // Bewusst KEIN Ersatz fuer eine optionale alarmTime - siehe CLAUDE.md "Stille Schicht".
    val isSilent: Boolean = false
) {
    /**
     * Get alarm time as formatted string for display
     */
    fun getAlarmTimeFormatted(): String {
        return String.format(Locale.US, "%02d:%02d", alarmTime.hour, alarmTime.minute)
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


