package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import java.time.ZoneId

/**
 * Letzte Stufe der Ort-Kette (#140): ein fester, ungefaehrer Ort je DACH-Zeitzone.
 * Bis zu einer guten halben Stunde ungenau (so steht es auch im Ort-Bildschirm) - besser als
 * gar keine Schwelle, und jede genauere Stufe schlaegt ihn. Andere Zonen: `null` = kein Ort,
 * keine Einschraenkung.
 */
object ZeitzonenOrte {

    private val zuerich = Ort(46.80, 8.23, OrtQuelle.ZEITZONE, "Sarnen")

    private val tabelle: Map<String, Ort> = mapOf(
        "Europe/Berlin" to Ort(50.98, 11.03, OrtQuelle.ZEITZONE, "Erfurt"),
        "Europe/Vienna" to Ort(47.60, 14.14, OrtQuelle.ZEITZONE, "Liezen"),
        "Europe/Zurich" to zuerich,
        // Deutsche Exklave am Hochrhein, laeuft auf Schweizer Zeit
        "Europe/Busingen" to zuerich,
        "Europe/Vaduz" to Ort(47.14, 9.52, OrtQuelle.ZEITZONE, "Vaduz")
    )

    fun fuer(zone: ZoneId): Ort? = tabelle[zone.id]
}
