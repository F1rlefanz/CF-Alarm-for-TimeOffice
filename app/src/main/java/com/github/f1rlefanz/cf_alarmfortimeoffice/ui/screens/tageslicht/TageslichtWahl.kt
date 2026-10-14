package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.MIN_TOUCH_TARGET
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.Etikett
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.EtikettTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.InhaltUndBedienung

/**
 * Dimm-Fenster "Weckzeit, spaetestens": bis wann der Nachlauf hoechstens laeuft (#140).
 *
 * Gleiche Knopf-Mechanik wie die Anker-Wahl im Fenster-Editor (gefuellt = gewaehlt), damit die
 * Auswahl im selben Fenster nicht anders aussieht als die Zeilen darueber. Wird nur gezeigt, wenn
 * der Nachlauf groesser als 0 ist - ohne Nachlauf gibt es nichts zu kuerzen.
 *
 * [beispiel] ist die fertige Zeile fuer den naechsten Wecker ("Nächster Wecker Do 05:15 → …");
 * `null`, wenn keiner im Fenster liegt.
 *
 * [ohneOrt] `null` = der Ort ist noch nicht geladen: dann steht unter der Wahl nichts - sonst
 * blitzte fuer einen Moment der rote Kein-Ort-Hinweis auf, der nicht stimmt.
 */
@Composable
fun NachlaufBisHellWahl(
    wahl: HellAb,
    onWahl: (HellAb) -> Unit,
    ohneOrt: Boolean?,
    beispiel: String?,
    onOrtEinstellen: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tageslicht_dimmer_titel), style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HellAb.entries.forEach { option ->
                val label = stringResource(
                    when (option) {
                        HellAb.IMMER -> R.string.tageslicht_wahl_immer
                        HellAb.DAEMMERUNG -> R.string.tageslicht_dimmer_bis_daemmerung
                        HellAb.SONNENAUFGANG -> R.string.tageslicht_dimmer_bis_sonnenaufgang
                    }
                )
                if (option == wahl) {
                    Button(onClick = { onWahl(option) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onWahl(option) }) { Text(label) }
                }
            }
        }
        if (wahl != HellAb.IMMER && ohneOrt != null) {
            if (ohneOrt) {
                OhneOrtHinweis(stringResource(R.string.tageslicht_ohne_ort_dimmer), onOrtEinstellen)
            } else {
                Text(
                    stringResource(R.string.tageslicht_dimmer_hinweis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                beispiel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/**
 * Hue-Regel: nur ausfuehren, wenn es beim Wecken draussen noch dunkel ist (#140).
 *
 * Zeilen-Mechanik wie [com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ShiftPatternCard]
 * (ganze Zeile als Ziel, 48dp-Klemme). Bewusst NICHT "Sonnenaufgang" als Kartentitel: so heisst
 * schon der Licht-Modus mit der Rampe.
 *
 * [ortText] beschreibt die genutzte Ort-Stufe, `null` = kein Ort. [naechster] ist die fertige
 * Urteilszeile fuer den naechsten Wecker.
 */
@Composable
fun TageslichtKarte(
    wahl: HellAb,
    onWahl: (HellAb) -> Unit,
    ortText: String?,
    naechster: String?,
    onOrtAendern: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.tageslicht_hue_titel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.tageslicht_hue_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.selectableGroup()) {
                HellAb.entries.forEach { option ->
                    val (titel, text) = when (option) {
                        HellAb.IMMER -> R.string.tageslicht_wahl_immer to R.string.tageslicht_hue_immer_text
                        HellAb.DAEMMERUNG -> R.string.tageslicht_hue_vor_daemmerung to R.string.tageslicht_hue_vor_daemmerung_text
                        HellAb.SONNENAUFGANG -> R.string.tageslicht_hue_vor_sonnenaufgang to R.string.tageslicht_hue_vor_sonnenaufgang_text
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MIN_TOUCH_TARGET)
                            .selectable(selected = wahl == option, onClick = { onWahl(option) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = wahl == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(titel), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (wahl != HellAb.IMMER) {
                // Textehrlichkeit (Plan §0.9): verglichen wird mit dem Morgen des Wecktags, ein
                // Abendwecker entfaellt deshalb auch bei Dunkelheit - das muss hier stehen.
                Text(
                    stringResource(R.string.tageslicht_hue_hinweis_abend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ortText == null) {
                    OhneOrtHinweis(stringResource(R.string.tageslicht_ohne_ort_hue), onOrtAendern)
                } else {
                    naechster?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                    // Pflichtweg "Text links, Knopf rechts" (UI-Skill): bei 320 dp bleiben hier
                    // 256 dp, also untereinander - Knopf linksbuendig, nicht verloren am Rand.
                    InhaltUndBedienung(
                        inhalt = { m ->
                            Text(
                                stringResource(R.string.tageslicht_hue_ort, ortText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = m
                            )
                        },
                        untereinanderAusrichtung = Alignment.Start
                    ) {
                        CompactOutlinedButton(
                            onClick = onOrtAendern,
                            text = stringResource(R.string.tageslicht_hue_ort_aendern)
                        )
                    }
                }
            }
        }
    }
}

/** Etikett an einer Hue-Regel in der Liste, die nur bei Dunkelheit laeuft. */
@Composable
fun DunkelheitEtikett() {
    Etikett(stringResource(R.string.tageslicht_etikett_dunkel), EtikettTon.WIRKUNG, Icons.Default.WbTwilight)
}

@Composable
private fun OhneOrtHinweis(text: String, onOrtEinstellen: () -> Unit) {
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onOrtEinstellen) { Text(stringResource(R.string.tageslicht_ort_einstellen)) }
    }
}
