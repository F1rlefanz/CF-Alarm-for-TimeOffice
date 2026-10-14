package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

/**
 * Ab wann es draussen als hell gilt - gewaehlt JE Dimm-Fenster bzw. JE Hue-Regel (#140), damit
 * sich beide Schwellen nebeneinander ausprobieren lassen.
 *
 * [IMMER] ist der Ausgangswert jedes bestehenden Fensters und jeder bestehenden Regel: ohne Zutun
 * aendert sich nichts. Fehlt der Ort oder gibt es an einem Tag kein solches Ereignis, wirkt jede
 * Wahl wie [IMMER] - im Zweifel hell und gedimmt wie bisher, nie "aus".
 */
enum class HellAb {
    IMMER,

    /** Buergerliche Daemmerung: Sonnenmittelpunkt 6° unter dem Horizont. */
    DAEMMERUNG,

    /** Oberer Sonnenrand am Horizont, mit Refraktion (Zenitwinkel 90,833°). */
    SONNENAUFGANG
}
