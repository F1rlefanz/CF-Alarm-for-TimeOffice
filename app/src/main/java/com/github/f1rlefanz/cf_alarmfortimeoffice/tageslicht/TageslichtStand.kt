package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import java.time.LocalDate
import java.time.ZoneId

/**
 * Unveraenderlicher Schnappschuss "welcher Ort, welche Zone" (#140). Die reinen Funktionen
 * (Dimm-Fenster, Hue-Regelauswahl) bekommen IHN, nicht das Repository - so bleiben sie ohne
 * Android und ohne Speicherzugriff testbar.
 *
 * Fail-safe-Richtung: [ort] `null` heisst "keine Einschraenkung" - der Dimmer-Nachlauf gilt voll,
 * Hue-Regeln laufen.
 */
class TageslichtStand(val ort: Ort?, val zone: ZoneId) {

    /**
     * Epoch-ms, ab dem es am Kalendertag [datum] als hell gilt; `null` = keine Einschraenkung
     * (kein Ort, [HellAb.IMMER], kein solches Ereignis an diesem Tag).
     */
    fun hellZeitpunkt(datum: LocalDate, wahl: HellAb): Long? {
        val o = ort ?: return null
        return SonnenstandRechner.hellAb(datum, o, zone, wahl)?.toEpochMilli()
    }

    companion object {
        fun ohneOrt(zone: ZoneId = ZoneId.systemDefault()) = TageslichtStand(null, zone)
    }
}
