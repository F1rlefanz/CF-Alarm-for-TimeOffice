package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

/**
 * Ein Ort fuer die Sonnenzeiten (#140). Breite/Laenge in Grad, Norden und OSTEN positiv.
 *
 * [bezeichnung] ist reiner Anzeigetext ("München", "Erfurt"), beim Standort `null`. Weder sie noch
 * die Koordinaten gehoeren ins Release-Log - nur `Logger.d`.
 */
data class Ort(
    val breite: Double,
    val laenge: Double,
    val quelle: OrtQuelle,
    val bezeichnung: String?
)
