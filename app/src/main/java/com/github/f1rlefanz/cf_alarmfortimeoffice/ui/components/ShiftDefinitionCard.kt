package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

@Composable
fun ShiftDefinitionCard(
    shiftDefinition: ShiftDefinition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (shiftDefinition.isEnabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    Text(
                        text = shiftDefinition.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!shiftDefinition.isEnabled) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "Deaktiviert",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = SpacingConstants.SPACING_SMALL, vertical = SpacingConstants.SPACING_MICRO)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_EXTRA_SMALL))
                
                // Erkennungsmuster anzeigen
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL)
                ) {
                    Text(
                        text = "Muster:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    shiftDefinition.keywords.forEach { keyword ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = SpacingConstants.SPACING_TINY, vertical = SpacingConstants.SPACING_MICRO)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_EXTRA_SMALL))
                
                // Alarm-Zeit anzeigen
                Text(
                    text = "Weckzeit: ${shiftDefinition.alarmTime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleEnabled) {
                    Icon(
                        imageVector = if (shiftDefinition.isEnabled) 
                            Icons.Filled.VisibilityOff 
                        else 
                            Icons.Filled.Visibility,
                        contentDescription = if (shiftDefinition.isEnabled) 
                            "Deaktivieren" 
                        else 
                            "Aktivieren"
                    )
                }
                
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Bearbeiten"
                    )
                }
                
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Löschen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
