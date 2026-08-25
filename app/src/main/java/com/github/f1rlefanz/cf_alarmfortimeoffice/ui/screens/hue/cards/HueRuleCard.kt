package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.modus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.hueRuleModusLabel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.hueShiftPatternLabel

/**
 * Eine Regel in der Liste. Aus `HueSettingsScreen` ausgelagert.
 *
 * Zeigt den MODUS als Abzeichen und darunter, was die Regel konkret tut. Vorher stand dort
 * "N Zeitbereich(e)" - eine Zahl aus einem Modell-Rest, den die Ausfuehrung nie aufgeloest hat
 * (siehe HueTimeRange): sie war fuer den Nutzer immer 1 und sagte nichts. Seit es drei
 * Betriebsarten gibt, waere ein Blick in die Liste ohne Modus vollends blind.
 */
@Composable
internal fun HueRuleCard(
    rule: HueSchedule,
    unresolvedTargets: List<UnresolvedRuleTarget>,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    // Loeschen ist unwiderruflich (Lampenauswahl, Farbwerte, Auto-Aus, Sunrise sind danach weg) und
    // der Papierkorb sitzt in einer schmalen Zeile direkt neben "Bearbeiten" — ein Fehlgriff kostete
    // bisher die ganze Regel ohne Rueckfrage. Dieselbe Karte fragt fuer die WENIGER folgenreiche
    // Aktion "Bridge vergessen" schon nach (BridgeStatusCard), das war ein Widerspruch.
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
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
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        // Das Abzeichen liest aus derselben Quelle wie der Editor
                        // (hueRuleModusLabel) - zwei Formulierungen waeren zwei Wahrheiten.
                        AssistChip(
                            onClick = onEdit,
                            label = { Text(hueRuleModusLabel(rule.modus)) }
                        )
                    }
                    // hueShiftPatternLabel: das Universalmuster ist ein Sentinel ("ALL") und
                    // darf in der Liste nicht roh erscheinen - dieselbe Beschriftung wie im Editor.
                    Text(
                        "Schichtmuster: ${hueShiftPatternLabel(rule.shiftPattern)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        regelBeschreibung(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (rule.enabled) "Aktiv" else "Deaktiviert",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }

            // Ein Ziel, das auf DIESER Bridge nicht existiert, macht die Regel nicht kaputt - sie
            // schaltet nur nichts. Ohne diesen Hinweis sieht sie vollstaendig aus, und der Nutzer
            // merkt es erst morgens. Erscheint nur, wenn die Bridge geantwortet hat (siehe
            // HueUiState.unresolvedTargets) - ein fremdes WLAN loest das hier NICHT aus.
            if (unresolvedTargets.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    // dekorativ: der Text daneben sagt es aus
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${unresolvedTargets.size} Ziel(e) auf dieser Bridge unbekannt: " +
                            "${unresolvedTargets.joinToString { it.label }}. " +
                            "Diese Regel schaltet dafür nichts – bitte in \"Bearbeiten\" neu auswählen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }


            // Hier war "Bearbeiten" zu "Bea/rbei/ten" zerfallen: zwei weight(1f)-Buttons plus
            // IconButton lassen je ~116dp, davon gehen 48dp allein für den Material3-Innenabstand
            // ab. CompactOutlinedButton nimmt den zurück und lässt nur eine Zeile zu.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactOutlinedButton(
                    onClick = onEdit,
                    text = "Bearbeiten",
                    icon = Icons.Default.Edit,
                    modifier = Modifier.weight(1f)
                )
                CompactOutlinedButton(
                    onClick = onTest,
                    text = "Test",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showDeleteDialog = true }) {
                    // Eigenstaendiges Bedienelement ohne sichtbare Beschriftung: die
                    // contentDescription benennt die AKTION, nicht das Symbol.
                    Icon(Icons.Default.Delete, "Regel löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Regel löschen?") },
            text = {
                Text(
                    "Die Regel \"${rule.name}\" wird mit allen Einstellungen (Lampenauswahl, " +
                        "Farbe, Auto-Aus, Sonnenaufgang) gelöscht. Das lässt sich nicht rückgängig " +
                        "machen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

/**
 * Was die Regel konkret tut, in einer Zeile. Je Modus eine eigene Formulierung - eine gemeinsame
 * ("N Ziele") verschwiege genau das, was den Unterschied ausmacht.
 */
private fun regelBeschreibung(rule: HueSchedule): String = when (rule.modus) {
    HueRuleModus.SZENE -> {
        val aktion = rule.lightActions.firstOrNull { it.isScene }
        val szene = aktion?.sceneName?.takeIf { it.isNotBlank() } ?: "Szene"
        val raum = aktion?.targetName?.takeIf { it.isNotBlank() }
        if (raum != null) "Szene «$szene» · $raum" else "Szene «$szene»"
    }

    HueRuleModus.SONNENAUFGANG ->
        "Sonnenaufgang über ${rule.sunrise?.durationMinutes ?: 0} Min · ${zielZahl(rule)}"

    HueRuleModus.MANUELL -> {
        val an = rule.lightActions.any { it.on == true }
        "${if (an) "Einschalten" else "Ausschalten"} · ${zielZahl(rule)}"
    }
}

private fun zielZahl(rule: HueSchedule): String {
    val lichter = rule.lightActions.count { !it.isScene && !it.isGroup }
    val gruppen = rule.lightActions.count { !it.isScene && it.isGroup }
    return listOfNotNull(
        lichter.takeIf { it > 0 }?.let { "$it ${if (it == 1) "Licht" else "Lichter"}" },
        gruppen.takeIf { it > 0 }?.let { "$it ${if (it == 1) "Gruppe" else "Gruppen"}" }
    ).joinToString(", ").ifEmpty { "kein Ziel" }
}
