package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R

/**
 * Dimmer-Tab (bis #226): die Regelliste und die Zeitleisten-Vorschau. Gedimmt wird seit #217 nach
 * den Fenstern am Schichttyp; die Geraete-Einstellungen stehen seit #225 unter „Einstellungen“, der
 * Hauptschalter ist weg. #226 entfernt den Reiter samt Bildschirmen.
 */
@Composable
fun DimmerTabContent(
    onNavigateToRules: () -> Unit,
    onNavigateToPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.dimmer_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onNavigateToRules,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_manage_rules))
                    }
                    Text(
                        text = stringResource(R.string.dimmer_preview_timeline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onNavigateToPreview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_preview_timeline))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
