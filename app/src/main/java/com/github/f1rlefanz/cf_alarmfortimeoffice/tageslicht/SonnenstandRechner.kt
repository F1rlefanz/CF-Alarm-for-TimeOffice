package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Morgendliche Sonnenzeiten nach den NOAA-Formeln (Solar Calculator, Meeus-Naeherung), selbst
 * gerechnet: keine Bibliothek, kein Netz (#140). Genauigkeit gegen die Referenztabelle im Plan
 * (§3) etwa eine Minute - fuer "dunkel oder hell beim Wecken" weit mehr als noetig.
 *
 * Gerechnet wird durchgehend in UTC-Minuten, das Ergebnis ist ein [Instant] - es gibt keinen von
 * Hand addierten Zonenversatz, deshalb braucht die Rechnung keine Zone. Die Zeitumstellung
 * beruehrt sie nicht; erst die Anzeige rechnet in die lokale Zone um.
 *
 * KALENDERTAG: Ausgangspunkt ist 00:00 UTC des Datums. Fuer DE/AT/CH (Laenge 5..17° O, Zone
 * UTC+1/+2) liegt der Morgen damit immer auf dem lokalen Eingabedatum (Test ueber ein ganzes Jahr).
 * Fuer Orte weit weg vom Meridian ihrer Zeitzone (etwa Westchina, Alaska) koennte das Ereignis
 * auf den lokalen Nachbartag fallen; das ist hier bewusst nicht behandelt, weil die Ort-Kette
 * nur DACH-Orte oder einen Standort im Geraeteland liefert.
 */
object SonnenstandRechner {

    /** Buergerliche Daemmerung: Sonnenmittelpunkt 6° unter dem Horizont. */
    const val ZENIT_DAEMMERUNG = 96.0

    /** Oberer Sonnenrand am Horizont, mit Refraktion. */
    const val ZENIT_SONNENAUFGANG = 90.833

    private const val ITERATIONEN = 3
    private const val JULIANISCHER_TAG_EPOCHE = 2440587.5 // JD von 1970-01-01T00:00Z
    private const val J2000 = 2451545.0

    /**
     * Morgen-Ereignis am Kalendertag [datum] fuer den Zenitwinkel [zenitGrad].
     *
     * @return `null` = gibt es an diesem Tag nicht (Polarnacht/-tag fuer diesen Winkel)
     */
    fun morgenEreignis(datum: LocalDate, breite: Double, laenge: Double, zenitGrad: Double): Instant? {
        val jdMitternacht = datum.toEpochDay() + JULIANISCHER_TAG_EPOCHE
        var utcMinuten = 720.0
        repeat(ITERATIONEN) {
            utcMinuten = morgenInUtcMinuten(jdMitternacht + utcMinuten / 1440.0, breite, laenge, zenitGrad)
                ?: return null
        }
        val sekunden = (utcMinuten * 60.0).roundToLong()
        return datum.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(sekunden)
    }

    fun daemmerungsBeginn(datum: LocalDate, breite: Double, laenge: Double): Instant? =
        morgenEreignis(datum, breite, laenge, ZENIT_DAEMMERUNG)

    fun sonnenaufgang(datum: LocalDate, breite: Double, laenge: Double): Instant? =
        morgenEreignis(datum, breite, laenge, ZENIT_SONNENAUFGANG)

    /** Ab wann es am Kalendertag [datum] als hell gilt. [HellAb.IMMER] -> `null` (keine Schwelle). */
    fun hellAb(datum: LocalDate, ort: Ort, wahl: HellAb): Instant? = when (wahl) {
        HellAb.IMMER -> null
        HellAb.DAEMMERUNG -> daemmerungsBeginn(datum, ort.breite, ort.laenge)
        HellAb.SONNENAUFGANG -> sonnenaufgang(datum, ort.breite, ort.laenge)
    }

    /**
     * Ein NOAA-Schritt: Deklination und Zeitgleichung zum Zeitpunkt [jd], daraus die UTC-Minute
     * des Morgen-Ereignisses ab 00:00 UTC. `null`, wenn die Sonne den Winkel nicht erreicht.
     */
    private fun morgenInUtcMinuten(jd: Double, breite: Double, laenge: Double, zenitGrad: Double): Double? {
        val t = (jd - J2000) / 36525.0

        val l0 = normiere(280.46646 + t * (36000.76983 + 0.0003032 * t))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mRad = rad(m)
        val c = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3 * mRad) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))

        val deklination = asin(sin(rad(eps)) * sin(rad(lambda)))

        val y = tan(rad(eps) / 2).let { it * it }
        val l0Rad = rad(l0)
        val zeitgleichungMin = 4.0 * grad(
            y * sin(2 * l0Rad) - 2 * e * sin(mRad) + 4 * e * y * sin(mRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) - 1.25 * e * e * sin(2 * mRad)
        )

        val phi = rad(breite)
        val cosHa = cos(rad(zenitGrad)) / (cos(phi) * cos(deklination)) - tan(phi) * tan(deklination)
        if (abs(cosHa) > 1.0) return null
        val stundenwinkel = grad(acos(cosHa))

        return 720.0 - 4.0 * (laenge + stundenwinkel) - zeitgleichungMin
    }

    private fun rad(g: Double) = Math.toRadians(g)
    private fun grad(r: Double) = Math.toDegrees(r)
    private fun normiere(g: Double) = ((g % 360.0) + 360.0) % 360.0
}
