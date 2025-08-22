package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable

/**
 * IMMUTABLE Android Calendar Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation prevents unnecessary recompositions in Compose
 * ✅ Optimiert für Listen-Performance in UI
 */
@Immutable
data class AndroidCalendar(
    val id: String,
    val name: String,
    val accountName: String? = null,
    val color: Int? = null,
    val isPrimary: Boolean = false
)
