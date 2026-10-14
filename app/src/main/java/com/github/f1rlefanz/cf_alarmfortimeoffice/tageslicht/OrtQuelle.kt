package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

/**
 * Woher der Ort fuer die Sonnenzeiten stammt - in der Reihenfolge, in der die Stufenkette sie
 * versucht (#140). Es gilt immer die genaueste belegte Stufe; ist keine belegt, gibt es keinen Ort
 * und damit keine Einschraenkung.
 */
enum class OrtQuelle {
    /** Einmal per Berechtigung uebernommen, gespeichert nur auf 0,1° gerundet. */
    STANDORT,

    /** Land + Postleitzahl, aufgeloest ueber die eingebaute Tabelle. */
    PLZ,

    /** Grob aus der Zeitzone des Geraets. */
    ZEITZONE
}
