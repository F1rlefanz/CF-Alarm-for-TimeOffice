package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.UnbekanntesZielAmTyp
import androidx.compose.ui.res.pluralStringResource

/**
 * Warnkarte fuer Regel-Ziele, die auf DIESER Bridge nicht existieren. Je Ziel nennt sie den
 * Schichttyp, dessen Lichtregel es traegt; Tippen oeffnet dessen Typ-Seite (#255). Ist der Typ nicht
 * bestimmbar (Konfiguration noch nicht gelesen), bleibt die Zeile stehen, nur ohne Tippen.
 *
 * Die Beschriftung des Ziels kommt aus `UnresolvedRuleTarget.label` - der einzigen
 * Beschriftungsquelle fuer nicht zuordenbare Ziele, damit Karte, Editor und Import-Dialog nicht drei
 * Formulierungen fuehren.
 */
@Composable
internal fun UnresolvedTargetsCard(
    ziele: List<UnbekanntesZielAmTyp>,
    onTypOeffnen: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // dekorativ: die Ueberschrift daneben sagt den Zustand aus
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        R.string.hue_unresolved_title,
                        pluralStringResource(R.plurals.hue_count_unresolved, ziele.size, ziele.size)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.hue_unresolved_body),
                style = MaterialTheme.typography.bodyMedium
            )
            ziele.forEach { ZielZeile(it, onTypOeffnen) }
            if (ziele.any { it.typId != null }) {
                Text(
                    stringResource(R.string.hue_unresolved_tap),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Ein Ziel mit seinem Schichttyp. Die ganze Zeile ist das Tipp-Ziel (mindestens 48 dp). */
@Composable
private fun ZielZeile(eintrag: UnbekanntesZielAmTyp, onTypOeffnen: (String) -> Unit) {
    val typId = eintrag.typId
    val tippen = if (typId == null) Modifier else Modifier.clickable(role = Role.Button) { onTypOeffnen(typId) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = tippen
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    eintrag.ziel.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    eintrag.typName?.let {
                        stringResource(R.string.hue_unresolved_row_type, it, eintrag.ziel.ruleName)
                    } ?: stringResource(R.string.hue_unresolved_row_rule, eintrag.ziel.ruleName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (typId != null) {
                // dekorativ: die Zeile selbst ist der Knopf und nennt den Typ
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}
