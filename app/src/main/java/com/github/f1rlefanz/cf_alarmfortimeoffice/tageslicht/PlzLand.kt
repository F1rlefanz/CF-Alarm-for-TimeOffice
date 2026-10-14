package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import kotlinx.serialization.Serializable

/**
 * Laender mit eingebauter PLZ-Tabelle (#140). Unbekannte Laender fallen auf die Zeitzone zurueck.
 *
 * Liegt hier und nicht beim Ort-Bildschirm: Repository und Stufenkette brauchen den Typ, und ein
 * Hintergrund-Paket darf `ui.` nicht importieren (`tools/invarianten/pruefe_code.py`).
 * Gespeichert wird der Name ("DE") - nicht umbenennen.
 */
@Serializable
enum class PlzLand {
    DE, AT, CH;

    companion object {
        /** Region des Geraets, sonst DE. Nur Vorauswahl, wird erst mit einer PLZ gespeichert. */
        fun ausRegion(country: String?): PlzLand =
            entries.firstOrNull { it.name == country?.uppercase() } ?: DE

        /**
         * Gespeicherter Schluessel -> Land. Unbekannt (Import aus einer neueren Version, kaputter
         * Wert) ergibt `null` = keine PLZ-Stufe, die Kette faellt auf die Zeitzone durch. Kein
         * Rateversuch: ein falsch geratenes Land loeste eine fremde PLZ auf.
         */
        fun ausSchluessel(wert: String?): PlzLand? = entries.firstOrNull { it.name == wert }
    }
}
