package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * IMMUTABLE Shift Configuration Model
 * 
 * ARCHITECTURE (Briefing 4.0):
 * ✅ @Immutable annotation for Compose performance
 * ✅ Immutable lists for better performance
 * ✅ Removed: daysAhead (fixed 14d in maintenance)
 * ✅ Removed: syncIntervalHours (fixed 6h via Exact Alarm)
 */
@Immutable
@Serializable
data class ShiftConfig(
    val autoAlarmEnabled: Boolean = true,
    val definitions: List<ShiftDefinition> = emptyList()
) {
    companion object {
        fun getDefaultConfig(): ShiftConfig = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
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
        )
    }
}
