package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import java.time.LocalDate

/** #142: woher die Tage eines [Ausfall]s stammen - entscheidet ueber Text und "Zuruecknehmen". */
enum class AusfallQuelle { HAND, KALENDER, GEMISCHT }

/**
 * #142: ein zusammenhaengender Block freier Tage, der heute oder morgen beginnt bzw. heute
 * enthaelt. [von] ist nie vor heute - die Uebersicht spricht nur von "heute/morgen".
 *
 * @param handTage die Hand-Freigaben INNERHALB dieses Blocks - nur sie kann "Zuruecknehmen"
 *        anfassen, Kalender-Tage gehoeren dem Sync.
 */
data class Ausfall(
    val von: LocalDate,
    val bis: LocalDate,
    val quelle: AusfallQuelle,
    val handTage: Set<LocalDate>
)

/**
 * #142: der laufende Ausfall fuer die Karte ganz oben in der Uebersicht - rein und ohne Android
 * testbar.
 *
 * Beginn ist heute, sonst morgen; GESTERN zaehlt nie als Beginn, auch wenn der Tag dank Rueckschau
 * noch in der Menge steht - ein Block, der gestern begann und heute weiterlaeuft, beginnt fuer die
 * Karte heute. Das Ende laeuft ueber die VEREINIGUNG beider Quellen, weil genau sie wirkt.
 */
fun laufenderAusfall(hand: Set<LocalDate>, kalender: Set<LocalDate>, heute: LocalDate): Ausfall? {
    val alle = hand + kalender
    val von = when {
        heute in alle -> heute
        heute.plusDays(1) in alle -> heute.plusDays(1)
        else -> return null
    }
    var bis = von
    while (bis.plusDays(1) in alle) bis = bis.plusDays(1)

    val block = generateSequence(von) { tag -> tag.plusDays(1).takeIf { !it.isAfter(bis) } }.toList()
    // Ein Tag in BEIDEN Mengen macht den Block GEMISCHT: Zuruecknehmen nimmt dann nur die eigene
    // Freigabe, der Tag bleibt laut Kalender frei - das muss der Text sagen koennen.
    val quelle = when {
        block.all { it in hand && it !in kalender } -> AusfallQuelle.HAND
        block.all { it in kalender && it !in hand } -> AusfallQuelle.KALENDER
        else -> AusfallQuelle.GEMISCHT
    }
    return Ausfall(von = von, bis = bis, quelle = quelle, handTage = block.filter { it in hand }.toSet())
}
