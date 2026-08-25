package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.MIN_TOUCH_TARGET
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.SzenenAuswahl

/**
 * Auswahl einer Szene aus der Hue-App: erst der Raum, dann die Szene darin.
 *
 * WARUM ZWEISTUFIG UND NICHT EINE FLACHE LISTE: An der Bridge des Nutzers gemessen
 * (25.08.2026) liegen dort **73 Szenen**, und die Namen wiederholen sich je Raum - „Nachtlicht"
 * neun Mal, „Energie tanken" zehn Mal. Eine flache Liste waere unbedienbar UND mehrdeutig; die
 * Raumwahl davor macht beides weg. Genau dieselbe Zweistufigkeit ist auch der Anker, mit dem der
 * `HueTargetReconciler` die Auswahl auf einer anderen Bridge wiederfindet.
 *
 * Einzelauswahl (RadioButton, kein Checkbox): eine Szene, ein Raum, ein PUT. Mehrere Szenen
 * gleichzeitig gaebe es auf der Bridge nicht sinnvoll abzubilden.
 */
@Composable
internal fun SceneSelectionCard(
    lightTargets: LightTargets,
    ausgewaehlt: SzenenAuswahl?,
    onAuswahlChange: (SzenenAuswahl) -> Unit,
    onRefreshTargets: () -> Unit,
    showValidationErrors: Boolean,
    unresolvedTargets: List<UnresolvedRuleTarget> = emptyList()
) {
    // Raeume, in denen es ueberhaupt Szenen gibt - ein leerer Raum waere ein Sackgassen-Eintrag.
    val raeume = remember(lightTargets) {
        lightTargets.groups.filter { gruppe ->
            lightTargets.scenes.any { it.group == gruppe.id }
        }.sortedBy { it.name.lowercase() }
    }

    // Vorauswahl: der Raum der gespeicherten Szene, sonst der erste mit Szenen.
    var raumId by rememberSaveable(ausgewaehlt?.groupId, raeume.firstOrNull()?.id) {
        mutableStateOf(ausgewaehlt?.groupId ?: raeume.firstOrNull()?.id)
    }
    var menueOffen by remember { mutableStateOf(false) }

    val gewaehlterRaum = raeume.firstOrNull { it.id == raumId }
    val szenenImRaum = remember(lightTargets, raumId) {
        lightTargets.scenes.filter { it.group == raumId }
            .sortedBy { (it.name ?: "").lowercase() }
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.hue_scene_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefreshTargets) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.hue_scene_refresh))
                }
            }

            when {
                // DREI GETRENNTE LEERZUSTAENDE. "Nicht abrufbar" und "keine vorhanden" duerfen
                // niemals denselben Text bekommen: im einen Fall ist die Bridge das Problem, im
                // anderen fehlt schlicht eine Szene in der Hue-App. Ein gemeinsames "Keine Szenen
                // gefunden" schickt den Nutzer in die falsche Richtung.
                lightTargets.scenesFailed -> Text(
                    stringResource(R.string.hue_scene_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                lightTargets.scenes.isEmpty() -> Text(
                    stringResource(R.string.hue_scene_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    Text(
                        stringResource(R.string.hue_scene_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Ein Ziel aus einer FREMDEN Bridge taucht in keiner Liste auf - ohne diesen
                    // Hinweis waere es unsichtbar: die Regel ist gespeichert, aber nichts ist
                    // ausgewaehlt, und nichts sagt warum.
                    if (unresolvedTargets.isNotEmpty()) {
                        Text(
                            stringResource(R.string.hue_scene_unknown_targets, unresolvedTargets.joinToString { it.label }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // --- Raumwahl ---
                    Column {
                        OutlinedButton(
                            onClick = { menueOffen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(gewaehlterRaum?.name ?: stringResource(R.string.hue_scene_pick_room), modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = menueOffen, onDismissRequest = { menueOffen = false }) {
                            raeume.forEach { raum ->
                                DropdownMenuItem(
                                    text = { Text(raum.name) },
                                    onClick = {
                                        raumId = raum.id
                                        menueOffen = false
                                    }
                                )
                            }
                        }
                    }

                    // --- Szenenwahl im Raum ---
                    szenenImRaum.forEach { szene ->
                        val istGewaehlt = ausgewaehlt?.sceneId == szene.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MIN_TOUCH_TARGET)
                                .selectable(
                                    selected = istGewaehlt,
                                    role = Role.RadioButton,
                                    onClick = {
                                        val raum = gewaehlterRaum ?: return@selectable
                                        onAuswahlChange(
                                            SzenenAuswahl(
                                                sceneId = szene.id,
                                                sceneName = szene.name.orEmpty(),
                                                groupId = raum.id,
                                                groupName = raum.name
                                            )
                                        )
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = istGewaehlt, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(szene.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    if (showValidationErrors && ausgewaehlt == null) {
                        Text(
                            stringResource(R.string.hue_scene_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // EHRLICHKEIT ueber den Zuschnitt: Szenen ohne Raum/Zone (LightScenes) werden
                    // bewusst nicht angeboten - ihnen fehlt der Gruppen-Anker UND das Ziel fuers
                    // Auto-Aus. Das still wegzulassen hiesse, den Nutzer eine fehlende Szene
                    // suchen zu lassen, die es nie geben wird.
                    Text(
                        stringResource(R.string.hue_scene_lightscene_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
