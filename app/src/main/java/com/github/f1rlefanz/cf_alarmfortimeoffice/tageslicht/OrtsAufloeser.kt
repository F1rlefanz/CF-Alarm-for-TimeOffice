package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import java.time.ZoneId

/**
 * Die EINE Stelle fuer "die genaueste belegte Stufe gewinnt" (#140) - Repository (Hintergrund)
 * und Ort-Bildschirm (Anzeige) rufen beide hier, damit beide denselben Ort meinen.
 *
 * 1. Standort ([OrtQuelle.STANDORT])
 * 2. Land + PLZ, sofern die Tabelle sie kennt ([OrtQuelle.PLZ]); unbekannt -> weiter
 * 3. Zeitzone ([OrtQuelle.ZEITZONE])
 * 4. `null` = kein Ort, keine Einschraenkung
 */
object OrtsAufloeser {

    fun loese(e: TageslichtEinstellungen, tabellen: (PlzLand) -> PlzTabelle, zone: ZoneId): Ort? {
        e.standort?.let {
            return Ort(it.breiteZehntel / 10.0, it.laengeZehntel / 10.0, OrtQuelle.STANDORT, null)
        }
        val land = e.land
        val plz = e.plz
        if (land != null && !plz.isNullOrBlank()) {
            tabellen(land).suche(land, plz)?.let {
                return Ort(it.breite, it.laenge, OrtQuelle.PLZ, it.name)
            }
        }
        return ZeitzonenOrte.fuer(zone)
    }
}
