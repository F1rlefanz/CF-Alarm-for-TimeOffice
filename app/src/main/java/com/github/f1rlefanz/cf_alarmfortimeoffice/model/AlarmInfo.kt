package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import java.time.LocalDateTime

/**
 * IMMUTABLE Alarm Information Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Strukturelle Gleichheit für effiziente Flow-Operations
 */
@Immutable
data class AlarmInfo(
    val id: Int,
    val shiftId: String,
    val shiftName: String,
    val triggerTime: Long,
    val formattedTime: String,
    val isActive: Boolean = true
)
