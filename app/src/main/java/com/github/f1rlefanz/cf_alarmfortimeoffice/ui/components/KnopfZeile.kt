package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gleichrangige Knoepfe nebeneinander - und linksbuendig UNTEREINANDER, sobald ihre natuerlichen
 * Breiten nicht mehr in die Zeile passen.
 *
 * WARUM nicht `Row` + `weight(1f)`: die Gewichtung teilt die Zeile, egal wie lang die Beschriftung
 * ist, und [CompactOutlinedButton] kuerzt dann mit Auslassung. Bei 320 dp mit Schrift 1,3 stand
 * auf der Hue-Regelkarte „Bea…“ statt „Bearbeiten“ und im Hue-Tab „Neue R…“ (Emulator,
 * 14.10.2026). Eine feste Breitenschwelle wie bei `InhaltUndBedienung` passt hier nicht: ob zwei
 * Knoepfe nebeneinander passen, haengt an IHREN Beschriftungen, also wird genau das gemessen.
 *
 * Passen sie, bekommt jeder Knopf seine Breite plus einen gleichen Anteil am Rest - die Zeile ist
 * gefuellt wie mit `weight`, nur ohne zu kuerzen.
 */
@Composable
fun KnopfZeile(
    modifier: Modifier = Modifier,
    abstand: Dp = 8.dp,
    inhalt: @Composable () -> Unit
) {
    Layout(content = inhalt, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val abstandPx = abstand.roundToPx()
        val breite = constraints.maxWidth
        val natuerlich = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val bedarf = natuerlich.sum() + abstandPx * (measurables.size - 1)

        if (constraints.hasBoundedWidth && bedarf <= breite) {
            val zuschlag = (breite - bedarf) / measurables.size
            val platziert = measurables.mapIndexed { i, m ->
                val w = natuerlich[i] + zuschlag
                m.measure(Constraints(minWidth = w, maxWidth = w))
            }
            val hoehe = platziert.maxOf { it.height }
            layout(breite, hoehe) {
                var x = 0
                platziert.forEach {
                    it.placeRelative(x, (hoehe - it.height) / 2)
                    x += it.width + abstandPx
                }
            }
        } else {
            val platziert = measurables.map { it.measure(Constraints(maxWidth = breite)) }
            val hoehe = platziert.sumOf { it.height } + abstandPx * (platziert.size - 1)
            layout(if (constraints.hasBoundedWidth) breite else platziert.maxOf { it.width }, hoehe) {
                var y = 0
                platziert.forEach {
                    it.placeRelative(0, y)
                    y += it.height + abstandPx
                }
            }
        }
    }
}
