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
    // Pro-Regel-Intensitaet: der Scheduler traegt diese Werte in die DimSpan der Regel-Fenster
    // (Wellness nutzt weiter die globale Darstellung; bei Ueberlappung gewinnt die dunkelste Spanne).
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
    SHIFT_END,

    /**
     * **Nur als ENDE-Anker.** „Bis zur Weckzeit – aber spätestens um [DimWindow.endClockMinutes]."
     * Das Fenster endet an der FRÜHESTEN Weckzeit, die zwischen seinem Start und dieser Uhrzeit
     * liegt; gibt es dort keine, endet es an der Uhrzeit.
     *
     * WARUM ES DEN ANKER GIBT (Befund 23.08.2026): Genau diese Semantik erwartet man von einem
     * Nacht-Fenster, und sie war im Modell nicht ausdrückbar. [ALARM] endet an der Weckzeit —
     * *egal wie spät die ist*: vor einem Spätdienst mit Wecker 12:30 dimmte der eingebaute
     * Nacht-Standard bis mittags, obwohl der Nutzer „bis 07:00" eingestellt zu haben glaubte
     * (die 07:00 galten dort nur an weckerfreien Tagen). [CLOCK] wiederum endet stur an der
     * Uhrzeit und überdimmt damit jeden früheren Wecker. Dieser Anker ist das Minimum aus beidem.
     *
     * ER ERSETZT AUSSERDEM EINE SONDERLOGIK: Mit [CLOCK] als Start ist ein solches Fenster „die
     * Nacht DIESES Kalendertags" und wird für jede Kalendernacht aufgelöst. Der eingebaute
     * Nacht-Standard brauchte dafür ein Paar aus Rückwärts- und Vorwärts-Fenster plus die
     * Bedingung „außer der Folgetag hat selbst einen Wecker" — eine Regel, die auf ein ANDERES
     * Datum schaut. Weil die Weckzeit hier aus der gesamten Zeitleiste gesucht wird und nicht aus
     * „dem Wecker dieses Tages", entfällt diese Bedingung ersatzlos.
     *
     * Als START-Anker ist er nicht vorgesehen; die Oberfläche bietet ihn dort nicht an, und die
     * Auflösung behandelt ihn am Start wie [CLOCK].
     */
    ALARM_SONST_CLOCK
}

/**
 * Wo im BLOCK aufeinanderfolgender Tage derselben Schicht ein Tag steht.
 *
 * WARUM ES DAS GIBT (Nutzerwunsch 18.09.2026, drei Nachtdienste Fr–So): Der Schlaf nach der
 * LETZTEN Nacht ist kürzer als der zwischen zwei Nächten – wer sich zurück auf den Tag umstellt,
 * schläft am Montag nur bis 12:00 statt bis 14:00. Bis dahin bekam jeder Tag einer Schicht
 * dieselben Fenster; "erster" oder "letzter Tag" war im Modell nicht ausdrückbar, obwohl der
 * Kalender die Antwort kennt. Ein Fenster trägt deshalb die Positionen, an denen es gilt
 * ([DimWindow.blockPositionen]); der Resolver leitet die Position eines Tages aus seinen
 * Nachbartagen ab (gleiche Schicht am Vortag? am Folgetag?).
 *
 * Die vier Werte sind bewusst DISJUNKT – ein Tag hat genau eine Position. Ein alleinstehender
 * Tag ist weder "erster" noch "letzter", sondern [EINZELNER]: sonst müsste man entscheiden, ob für
 * ihn die Erster- oder die Letzter-Fenster gelten (beide zugleich wäre additiv und dimmte mehr
 * als jede Auswahl für sich). So sagt es der Nutzer selbst, mit einem Häkchen.
 */
@Serializable
enum class Blockposition {
    /** Vortag ohne diese Schicht, Folgetag mit ihr. */
    ERSTER,

    /** Vor- und Folgetag mit dieser Schicht. */
    MITTLERER,

    /** Vortag mit dieser Schicht, Folgetag ohne. */
    LETZTER,

    /** Weder Vor- noch Folgetag mit dieser Schicht. */
    EINZELNER;

    companion object {
        /** Der Default eines Fensters: gilt an jedem Tag des Blocks – das bisherige Verhalten. */
        val ALLE: Set<Blockposition> = entries.toSet()
    }
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
    val endOffsetMinutes: Int = 0,
    /**
     * An welchen Tagen eines Schicht-BLOCKS dieses Fenster gilt (siehe [Blockposition]). Default =
     * alle, damit jedes bestehende Fenster unverändert weiterwirkt; ein alter Regelbestand ohne
     * dieses Feld liest sich damit als "wie bisher". Leere Menge = Fenster gilt nirgends (nicht
     * verboten, aber sinnlos – der Editor lässt das letzte Häkchen deshalb nicht abwählen).
     * Für FREI-Regeln ohne Schicht wird das Feld ignoriert.
     */
    val blockPositionen: Set<Blockposition> = Blockposition.ALLE
)
