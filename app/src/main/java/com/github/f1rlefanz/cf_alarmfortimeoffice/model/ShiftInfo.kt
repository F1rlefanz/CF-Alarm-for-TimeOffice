package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import java.time.LocalDateTime

data class ShiftInfo(
    val id: String,
    val shiftType: ShiftType,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val eventTitle: String,
    val alarmTime: LocalDateTime
)

data class ShiftType(
    val name: String,
    val displayName: String,
    val color: Int? = null
)
