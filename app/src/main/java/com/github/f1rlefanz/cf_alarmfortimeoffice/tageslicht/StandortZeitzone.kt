package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Passt ein gespeicherter Standort zur Zeitzone des Geraets? (#144)
 *
 * "Standort übernehmen" speichert dauerhaft. Im Urlaub uebernommen (oder eine veraltete Position),
 * rechnet die App danach still mit Sonnenzeiten eines fremden Orts - am Emulator ergab Kalifornien
 * bei Europe/Berlin "Sonnenaufgang 16:15". Die Pruefung erkennt das, verwirft aber nichts: sie ist
 * nur ein Hinweis auf dem Ort-Bildschirm.
 *
 * MASS: wie weit der mittlere Sonnenmittag des Orts von 12:00 STANDARDzeit der Zone abweicht, also
 * von 12 Uhr im Winter bzw. 13 Uhr in der Sommerzeit. Gerechnet mit dem Standard-Offset, nicht mit
 * dem aktuellen: sonst laege die spanische Westkueste (Vigo, -8,7°) im Sommer bei 2,6 h und bekaeme
 * den Hinweis, obwohl Ort und Zone zusammengehoeren (mit Standard-Offset: 1,6 h). Die Zeitgleichung
 * (hoechstens 17 min) bleibt weg - gegen eine Schwelle von Stunden ist sie Rauschen.
 *
 * GRENZE: Es gibt echte Orte, die weiter als [SCHWELLE_MINUTEN] von ihrer eigenen Zone entfernt
 * liegen (Westchina unter Asia/Shanghai, etwa 3 h). Dort erschiene der Hinweis zu Unrecht; fuer
 * einen Nutzerkreis in DACH bewusst hingenommen - er ist ein Hinweis, kein Eingriff.
 */
object StandortZeitzone {

    /** Ab dieser Abweichung (exklusiv) passt der Ort nicht mehr - Vorschlag aus #144. */
    const val SCHWELLE_MINUTEN = 120

    /**
     * Abweichung des mittleren Sonnenmittags bei [laenge] (Grad, Osten positiv) von 12:00
     * Standardzeit der [zone] zum Zeitpunkt [jetzt], in Minuten, auf (-720, 720] normiert.
     * Positiv: die Sonne steht am Ort SPAETER im Sueden als um 12 Uhr Standardzeit.
     */
    fun abweichungMinuten(laenge: Double, zone: ZoneId, jetzt: Instant): Int {
        val standardMinuten = zone.rules.getStandardOffset(jetzt).totalSeconds / 60.0
        // Mittlerer Sonnenmittag in UTC: 12:00 - 4 min je Grad oestlicher Laenge
        val roh = standardMinuten - 4.0 * laenge
        val normiert = ((roh % 1440.0) + 1440.0) % 1440.0
        return (if (normiert > 720.0) normiert - 1440.0 else normiert).roundToInt()
    }

    fun passt(laenge: Double, zone: ZoneId, jetzt: Instant): Boolean =
        abs(abweichungMinuten(laenge, zone, jetzt)) <= SCHWELLE_MINUTEN
}
