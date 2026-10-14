package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import kotlinx.serialization.Serializable

/**
 * Ab wann es draussen als hell gilt - gewaehlt JE Dimm-Fenster bzw. JE Hue-Regel (#140), damit
 * sich beide Schwellen nebeneinander ausprobieren lassen.
 *
 * [IMMER] ist der Ausgangswert jedes bestehenden Fensters und jeder bestehenden Regel: ohne Zutun
 * aendert sich nichts. Fehlt der Ort oder gibt es an einem Tag kein solches Ereignis, wirkt jede
 * Wahl wie [IMMER] - im Zweifel hell und gedimmt wie bisher, nie "aus".
 *
 * Gespeichert im Regel-JSON (Dimm-Fenster, Hue-Regel), als Name - nicht umbenennen. Ein
 * UNBEKANNTER Wert (Downgrade, Import aus einer neueren Version) ist ein Dekodierfehler: der
 * Dimmer-Schreibpfad und der Import lehnen ihn ab, nur die Dimmer-Anzeige faellt per
 * `coerceInputValues` auf den Feld-Default zurueck (wie bei `DimAnchor`).
 */
@Serializable
enum class HellAb {
    IMMER,

    /** Buergerliche Daemmerung: Sonnenmittelpunkt 6° unter dem Horizont. */
    DAEMMERUNG,

    /** Oberer Sonnenrand am Horizont, mit Refraktion (Zenitwinkel 90,833°). */
    SONNENAUFGANG
}
