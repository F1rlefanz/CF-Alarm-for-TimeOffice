package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.StatusTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.kategorieFarben
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.tonPaar

/**
 * Der Kasten zu einem Sonderfall (SPEC 3.3 Punkt 4, E3; Prototyp `besonders` und `banner` in `zeigeTyp`):
 * Rufbereitschaft und Krank violett mit fettem Titel, Ignorieren amber ohne Titel. Zustandslos; ob und was
 * er sagt, entscheidet [ShiftDefinitionTexte.sonderfall] (reine Funktion, mit Test).
 */
@Composable
internal fun SonderfallKasten(kategorie: ShiftKategorie, text: ShiftDefinitionTexte.SonderfallText) {
    val dunkel = isSystemInDarkTheme()
    // Violett wie die Marke „Sonderfall“ (= Farbe der Rufbereitschaft, auch bei Krank - so zeichnet es der
    // Prototyp); amber wie `.banner` = der Warnton der App, dessen Kontrast `ThemeKontrastTest` haelt.
    val (flaeche, schrift) = if (kategorie == ShiftKategorie.IGNORIEREN) {
        tonPaar(StatusTon.WARNUNG, dunkel).let { it.flaeche to it.text }
    } else {
        kategorieFarben(ShiftKategorie.RUFBEREITSCHAFT, dunkel).let { it.flaeche to it.text }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = flaeche
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            text.titel?.let { titel ->
                Text(
                    titel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = schrift,
                    modifier = Modifier.semantics { heading() }
                )
            }
            text.absaetze.forEach { absatz ->
                Text(absatz, style = MaterialTheme.typography.bodyMedium, color = schrift)
            }
        }
    }
}
