package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import kotlinx.serialization.Serializable

/**
 * Eine Dimm-Regel, gekoppelt an eine (nutzerdefinierte) Schicht. Vorbild: das Hue-Regelsystem
 * (`hue/data/HueSchedule.kt`) – hier aber mit einer Fenster-Auflösung relativ zur Weckzeit.
 *
 * [shiftPattern] ist ENTWEDER ein Schicht-Definitionsname (z. B. "Frühschicht"), ODER eines der
 * Sondermuster [SHIFT_FREE] (Tage ohne erkannte Schicht) bzw. [SHIFT_UNIVERSAL] (Fallback für
 * alles ohne spezifische Regel). Auflösung pro Tag: spezifische Regel schlägt FREI/UNIVERSAL.
 *
 * Eine Regel MIT [shiftPattern] einer Schicht und LEERER [windows]-Liste unterdrückt bewusst das
 * Dimmen an solchen Tagen (schlägt die UNIVERSAL-„jede-Nacht"-Regel) – so entsteht die
 * Nachtdienst-Ausnahme („nachts nicht dimmen, weil ich wach bin").
 */
@Serializable
data class DimRule(
    val id: String = generateId(),
    val name: String,
    val shiftPattern: String,
    val enabled: Boolean = true,
    val windows: List<DimWindow> = emptyList(),
    // Reserviert fuer spaetere Pro-Regel-Intensitaet; der Scheduler nutzt aktuell die globale.
    val strength: Int = 55,
    val warmth: Int = 40
) {
    companion object {
        const val SHIFT_FREE = "__FREI__"
        const val SHIFT_UNIVERSAL = "__UNIVERSAL__"
        fun generateId(): String = "dimrule_${System.currentTimeMillis()}_${(0..9999).random()}"
    }
}

/** Woran ein Fenster-Rand hängt. */
@Serializable
enum class DimAnchor {
    /** Feste Uhrzeit (Minuten seit Mitternacht). */
    CLOCK,

    /** Relativ zur Weckzeit der Schicht (Offset in Minuten, negativ = davor). */
    ALARM,

    /** Relativ zum SchichtENDE (Kalender-Event-Ende) + Offset – z. B. ND-Tagschlaf „ab Schichtende
     * +60 Min". Braucht ein bekanntes Schichtende ([com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo.shiftEndTime]
     * ≠ 0); an manuellen Alarmen ohne Schicht nicht auflösbar. Nutzt dieselben Offset-Felder wie [ALARM]. */
    SHIFT_END
}

/**
 * Ein Dimm-Fenster einer Regel. Start und Ende sind je unabhängig verankert:
 * - [DimAnchor.CLOCK]: feste Uhrzeit ([startClockMinutes]/[endClockMinutes], 0..1439).
 * - [DimAnchor.ALARM]: Weckzeit + Offset ([startOffsetMinutes]/[endOffsetMinutes]).
 *
 * CLOCK-Auflösung an einem Schicht-Tag zielt bewusst auf die Uhrzeit **vor** der Weckzeit
 * (z. B. „20:00" vor einem 05:30-Wecker = Vorabend). Bei freien Tagen (kein Wecker) sind nur
 * CLOCK-Anker sinnvoll; Fenster über Mitternacht (Start ≥ Ende) werden erkannt.
 */
@Serializable
data class DimWindow(
    val startAnchor: DimAnchor = DimAnchor.CLOCK,
    val startClockMinutes: Int = 20 * 60,
    val startOffsetMinutes: Int = -120,
    val endAnchor: DimAnchor = DimAnchor.ALARM,
    val endClockMinutes: Int = 6 * 60,
    val endOffsetMinutes: Int = 0
)
