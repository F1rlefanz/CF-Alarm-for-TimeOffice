package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypTexte

/** Die Marke „Sonderfall“ (Prototyp `.badge` = violett, wie die Marke „Rufbereitschaft“). */
@Composable
internal fun SonderfallMarke() {
    val farben = kategorieFarben(ShiftKategorie.RUFBEREITSCHAFT, isSystemInDarkTheme())
    Surface(shape = RoundedCornerShape(8.dp), color = farben.flaeche) {
        Text(
            SchichttypTexte.SONDERFALL,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = farben.text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Das kleine Farbquadrat einer Kategorie (Prototyp `.code` mit 16 x 16 dp und Radius 4). */
@Composable
internal fun KategorieMarke(kategorie: ShiftKategorie, modifier: Modifier = Modifier) {
    val farben = kategorieFarben(kategorie, isSystemInDarkTheme())
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)).background(farben.flaeche))
}

/** Gestrichelter Rand um eine abgerundete Flaeche (Prototyp `.fund`; die feste Zeile nutzt dasselbe). */
internal fun Modifier.gestrichelterRand(farbe: Color, radius: Dp, breite: Dp = 1.5.dp): Modifier = drawBehind {
    drawRoundRect(
        color = farbe,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = breite.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
        )
    )
}

/**
 * Die Code-Marke einer Zeile (Prototyp `.code` + `.k-<kategorie>`). [kategorie] `null` = die feste
 * Zeile: ohne Flaeche, mit gestricheltem Rand. Hoechstens eine Zeile breit, damit ein langes erstes
 * Muster die Zeile nicht sprengt.
 */
@Composable
internal fun CodeMarke(code: String, kategorie: ShiftKategorie?) {
    val farben = kategorie?.let { kategorieFarben(it, isSystemInDarkTheme()) }
    val rand = MaterialTheme.colorScheme.onSurfaceVariant
    val form = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp, max = 96.dp)
            .height(32.dp)
            .clip(form)
            .then(
                if (farben != null) Modifier.background(farben.flaeche) else Modifier.gestrichelterRand(rand, 9.dp)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = farben?.text ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Flaeche und Schrift der Code-Marke einer Kategorie. */
internal data class KategorieFarben(val flaeche: Color, val text: Color)

/**
 * Hell: die Werte des Prototyps (`.k-dienst` usw.). Dunkel: dieselbe Farbfamilie, Flaeche abgedunkelt
 * und die helle Flaeche des Prototyps als Schrift. Jedes Paar erreicht mindestens 4,5:1 - das haelt
 * `ShiftConfigScreenTextTest` fest. Ohne `else`: eine achte Kategorie braucht bewusst eine Farbe.
 *
 * Geteilt von der Typ-Liste, dem Einordnen-Dialog (#176), dem Dialog "Ohne Kalendereintrag" und der
 * Typ-Seite des Umbaus - deshalb hier und nicht in `ShiftConfigScreen`.
 */
internal fun kategorieFarben(kategorie: ShiftKategorie, dunkel: Boolean): KategorieFarben = when (kategorie) {
    ShiftKategorie.DIENST ->
        if (dunkel) KategorieFarben(Color(0xFF5A1A29), Color(0xFFFBE1E4)) else KategorieFarben(Color(0xFFFBE1E4), Color(0xFF8E0F30))
    ShiftKategorie.RUFBEREITSCHAFT ->
        if (dunkel) KategorieFarben(Color(0xFF3F2A52), Color(0xFFEEE4F3)) else KategorieFarben(Color(0xFFEEE4F3), Color(0xFF4E2A6B))
    ShiftKategorie.FREI ->
        if (dunkel) KategorieFarben(Color(0xFF1F4A25), Color(0xFFE3F1E4)) else KategorieFarben(Color(0xFFE3F1E4), Color(0xFF1B5E20))
    ShiftKategorie.URLAUB ->
        if (dunkel) KategorieFarben(Color(0xFF15443F), Color(0xFFDDF0EE)) else KategorieFarben(Color(0xFFDDF0EE), Color(0xFF0F4F4A))
    ShiftKategorie.FORTBILDUNG ->
        if (dunkel) KategorieFarben(Color(0xFF243D5A), Color(0xFFE2EAF2)) else KategorieFarben(Color(0xFFE2EAF2), Color(0xFF24476B))
    ShiftKategorie.KRANK ->
        if (dunkel) KategorieFarben(Color(0xFF5A3A0C), Color(0xFFFBEBD3)) else KategorieFarben(Color(0xFFFBEBD3), Color(0xFF6E4100))
    ShiftKategorie.IGNORIEREN ->
        if (dunkel) KategorieFarben(Color(0xFF3E3B38), Color(0xFFEDEAE7)) else KategorieFarben(Color(0xFFEDEAE7), Color(0xFF5E5955))
}
