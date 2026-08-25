package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.MIN_TOUCH_TARGET
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.SzenenAuswahl

/**
 * Auswahl von Szenen aus der Hue-App: erst der Raum, dann die Szene darin - und das fuer MEHRERE
 * Raeume nacheinander.
 *
 * WARUM ZWEISTUFIG UND NICHT EINE FLACHE LISTE: An der Bridge des Nutzers gemessen
 * (25.08.2026) liegen dort **73 Szenen**, und die Namen wiederholen sich je Raum - "Nachtlicht"
 * neun Mal, "Energie tanken" zehn Mal. Eine flache Liste waere unbedienbar UND mehrdeutig; die
 * Raumwahl davor macht beides weg. Genau dieselbe Zweistufigkeit ist auch der Anker, mit dem der
 * `HueTargetReconciler` die Auswahl auf einer anderen Bridge wiederfindet.
 *
 * MEHRERE RAEUME, ABER HOECHSTENS EINE SZENE JE RAUM: Eine Regel darf das Wohnzimmer auf
 * "Nachtlicht" und das Schlafzimmer auf "Lesen" setzen - die Ausfuehrung schickt dann zwei PUTs,
 * und das Auto-Aus legt zwei Bridge-Timer an. Die Kette darunter konnte das von Anfang an
 * (`convertRuleToLightActions` laeuft ueber alle Aktionen, `autoOffTargetsOf()` flatMapt und
 * dedupliziert); die frueher einzelne Auswahl war eine reine Oberflaechen-Begrenzung.
 *
 * Zwei Szenen auf DEMSELBEN Raum waeren dagegen zwei PUTs auf denselben Endpunkt: der zweite
 * gewaenne, die Einstellung widerspraeche sich selbst. Deshalb ersetzt eine neue Wahl im selben
 * Raum die alte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneSelectionCard(
    lightTargets: LightTargets,
    ausgewaehlt: List<SzenenAuswahl>,
    onAuswahlChange: (List<SzenenAuswahl>) -> Unit,
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

    var raumId by rememberSaveable(raeume.firstOrNull()?.id) {
        mutableStateOf(ausgewaehlt.firstOrNull()?.groupId ?: raeume.firstOrNull()?.id)
    }
    var menueOffen by remember { mutableStateOf(false) }

    val gewaehlterRaum = raeume.firstOrNull { it.id == raumId }
    val szenenImRaum = remember(lightTargets, raumId) {
        lightTargets.scenes.filter { it.group == raumId }
            .sortedBy { (it.name ?: "").lowercase() }
    }

    // Teilen sich zwei gewaehlte Bereiche eine Lampe, gewinnt fuer diese Lampe die zuletzt
    // gesendete Szene. Das ist keine Fehlbedienung - Zonen ueberschneiden sich auf der Bridge des
    // Nutzers real (Lampe 4 liegt in "Wohnzimmer", "Deckenlampe" UND "Zuhause") -, aber es
    // ueberrascht, wenn man es nicht weiss. Also sagen wir es, statt es zu verbieten.
    val ueberschneidung = remember(ausgewaehlt, lightTargets) {
        lightTargets.groups
            .filter { g -> ausgewaehlt.any { it.groupId == g.id } }
            .flatMap { it.lights }
            .groupingBy { it }
            .eachCount()
            .any { it.value > 1 }
    }

    // Denselben Rahmen wie Manuell und Sonnenaufgang: Ueberschrift = gewaehlte Betriebsart,
    // daneben das Aktualisieren-Symbol. Siehe ModusKarten.kt.
    ModusKarte(
        modus = HueRuleModus.SZENE,
        onRefreshTargets = onRefreshTargets,
        refreshBeschreibung = stringResource(R.string.hue_scene_refresh)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        stringResource(R.string.hue_scene_intro_multi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Ein Ziel aus einer FREMDEN Bridge taucht in keiner Liste auf - ohne diesen
                    // Hinweis waere es unsichtbar: die Regel ist gespeichert, aber nichts ist
                    // ausgewaehlt, und nichts sagt warum.
                    if (unresolvedTargets.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.hue_scene_unknown_targets,
                                unresolvedTargets.joinToString { it.label }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // --- Was bereits gewaehlt ist, ueber ALLE Raeume hinweg ---
                    //
                    // Ohne diese Uebersicht saehe der Nutzer immer nur den gerade aufgeklappten
                    // Raum und wuesste nicht mehr, was er sonst noch ausgewaehlt hat.
                    if (ausgewaehlt.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ausgewaehlt.forEach { wahl ->
                                InputChip(
                                    selected = true,
                                    onClick = { onAuswahlChange(ausgewaehlt - wahl) },
                                    label = { Text("${wahl.sceneName} · ${wahl.groupName}") },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(
                                                R.string.hue_scene_remove,
                                                wahl.sceneName,
                                                wahl.groupName
                                            ),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (ueberschneidung) {
                        Text(
                            stringResource(R.string.hue_scene_overlap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // --- Raumwahl ---
                    Column {
                        OutlinedButton(
                            onClick = { menueOffen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                gewaehlterRaum?.name ?: stringResource(R.string.hue_scene_pick_room),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = menueOffen, onDismissRequest = { menueOffen = false }) {
                            raeume.forEach { raum ->
                                val hatWahl = ausgewaehlt.any { it.groupId == raum.id }
                                DropdownMenuItem(
                                    // Der Haken zeigt, in welchen Raeumen schon etwas gewaehlt ist -
                                    // sonst muesste man jeden einzeln aufklappen, um es zu sehen.
                                    text = { Text(if (hatWahl) "✓ ${raum.name}" else raum.name) },
                                    onClick = {
                                        raumId = raum.id
                                        menueOffen = false
                                    }
                                )
                            }
                        }
                    }

                    // --- Szenenwahl im aufgeklappten Raum ---
                    szenenImRaum.forEach { szene ->
                        val istGewaehlt = ausgewaehlt.any { it.sceneId == szene.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MIN_TOUCH_TARGET)
                                .toggleable(
                                    value = istGewaehlt,
                                    role = Role.Checkbox,
                                    onValueChange = { angehakt ->
                                        val raum = gewaehlterRaum ?: return@toggleable
                                        // Erst alles aus DIESEM Raum entfernen: hoechstens eine
                                        // Szene je Raum (siehe KDoc oben).
                                        val ohneDiesenRaum =
                                            ausgewaehlt.filterNot { it.groupId == raum.id }
                                        onAuswahlChange(
                                            if (!angehakt) ohneDiesenRaum
                                            else ohneDiesenRaum + SzenenAuswahl(
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
                            Checkbox(checked = istGewaehlt, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(szene.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    if (showValidationErrors && ausgewaehlt.isEmpty()) {
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
