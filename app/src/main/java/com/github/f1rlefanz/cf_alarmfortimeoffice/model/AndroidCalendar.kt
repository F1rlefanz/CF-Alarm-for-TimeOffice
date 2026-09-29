package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable

/** Ein Kalender des Geraets bzw. Google-Kontos, wie ihn die Kalenderauswahl zeigt. */
@Immutable
data class AndroidCalendar(
    val id: String,
    val name: String,
    val accountName: String? = null,
    val color: Int? = null
)
