package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.EinordnenTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KategorieZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.kategorieFarben
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Die Texte der Kategorie-Wahl fuer einen BESTEHENDEN Typ (Prototyp `katWahlIntro`, `neuCode` leer).
 * Der Fuss ist der von [EinordnenTexte.KATEGORIE_HINWEIS]: er nennt bis Etappe 3/4 nur Wecker und
 * Weckzeit, weil Dimmen, „Nicht stören“ und Licht erst dann am Typ stehen.
 */
internal object KategorieWahlTexte {
    const val TITEL = "Kategorie"

    /** „Deine Einstellungen bleiben“ stimmt: ein Wechsel behaelt alles ausser der Kategorie (SPEC E2). */
    fun einleitung(name: String) = "Kategorie für „$name“ ändern. Deine Einstellungen bleiben, wie sie sind."
}

/**
 * Die Kategorie-Wahl der Typ-Seite (SPEC 3.3 Punkt 2, Prototyp `zeigeKatWahl`): sieben Zeilen, die
 * aktuelle gewaehlt. Ein Tipp waehlt und schliesst. Schreibt nichts - [onWahl] meldet nur die Wahl,
 * die Seite setzt sie in den Entwurf ([TypAbschnittLogik.kategorieWechseln]).
 *
 * EIN DIALOG, damit Zurueck ihn schliesst: ein Dialog hat ein eigenes Fenster und bekommt die
 * Zurueck-Taste vor dem `BackHandler` in `MainScreen` und vor dem der Typ-Seite (Skill
 * cfalarm-ui-und-navigation). Ein eigener `NavigationState` haette beide Handler zu bedenken.
 */
@Composable
internal fun KategorieWahlDialog(
    name: String,
    aktuell: ShiftKategorie,
    onWahl: (ShiftKategorie) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(LayoutFractions.DIALOG_WIDTH)
                .fillMaxHeight(LayoutFractions.DIALOG_HEIGHT)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingConstants.SPACING_LARGE)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = KategorieWahlTexte.TITEL,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

                // weight(1f): sonst schiebt die Liste den Knopf darunter aus dem Dialog.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                ) {
                    item(key = "einleitung") { Einleitung(KategorieWahlTexte.einleitung(name)) }

                    item(key = "kategorien") {
                        // Dezente Karte (Skill: nie `surfaceVariant` als Flaeche).
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                                ShiftKategorie.entries.forEachIndexed { index, kategorie ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    KategorieZeile(
                                        option = kategorie,
                                        gewaehlt = kategorie == aktuell,
                                        onWahl = { onWahl(kategorie) },
                                        innenabstand = SpacingConstants.SPACING_LARGE
                                    )
                                }
                            }
                        }
                    }

                    item(key = "hinweis") {
                        Text(
                            EinordnenTexte.KATEGORIE_HINWEIS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

                KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                    CompactOutlinedButton(onClick = onDismiss, text = "Abbrechen")
                }
            }
        }
    }
}

/** Der Kasten oben (Prototyp `.banner`): dieselbe Bernsteinfamilie wie die Marke „Krank“, wie im Einordnen-Dialog. */
@Composable
private fun Einleitung(text: String) {
    val farben = kategorieFarben(ShiftKategorie.KRANK, isSystemInDarkTheme())
    Surface(shape = RoundedCornerShape(14.dp), color = farben.flaeche, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = farben.text,
            modifier = Modifier.padding(SpacingConstants.SPACING_LARGE)
        )
    }
}
