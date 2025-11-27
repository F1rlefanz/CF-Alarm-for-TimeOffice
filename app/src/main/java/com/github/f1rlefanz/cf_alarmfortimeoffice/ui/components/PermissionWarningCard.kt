package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.UIColors

@Composable
fun PermissionWarningCard(
    title: String,
    description: String,
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.warningContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SpacingConstants.CARD_ELEVATION),
        shape = RoundedCornerShape(SpacingConstants.CARD_CORNER_RADIUS)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            // Header mit Icon und Titel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onWarningContainer
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onWarningContainer
                    )
                }

                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Schließen",
                            tint = MaterialTheme.colorScheme.onWarningContainer
                        )
                    }
                }
            }

            // Beschreibung
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onWarningContainer
            )

            // Action Button
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.warning,
                    contentColor = MaterialTheme.colorScheme.onWarning
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionText)
            }
        }
    }
}

// Spezielle Varianten für häufige Anwendungsfälle
@Composable
fun CalendarPermissionWarningCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    PermissionWarningCard(
        title = "Kalender-Berechtigung erforderlich",
        description = "Um Ihre Schichten automatisch zu erkennen, benötigt die App Zugriff auf Ihren Google Kalender.",
        actionText = "Berechtigung erteilen",
        onActionClick = onRequestPermission,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

// Extension für ColorScheme um WarningContainer zu definieren
@get:Composable
val ColorScheme.warningContainer: androidx.compose.ui.graphics.Color
    get() = if (this.background == androidx.compose.ui.graphics.Color.White) {
        androidx.compose.ui.graphics.Color(UIColors.WARNING_CONTAINER_LIGHT) // Light orange
    } else {
        androidx.compose.ui.graphics.Color(UIColors.WARNING_CONTAINER_DARK) // Dark brown
    }

@get:Composable
val ColorScheme.onWarningContainer: androidx.compose.ui.graphics.Color
    get() = if (this.background == androidx.compose.ui.graphics.Color.White) {
        androidx.compose.ui.graphics.Color(UIColors.ON_WARNING_CONTAINER_LIGHT) // Dark brown
    } else {
        androidx.compose.ui.graphics.Color(UIColors.ON_WARNING_CONTAINER_DARK) // Light orange
    }

@Suppress("unused") // Extension for ColorScheme theming consistency
@get:Composable
val ColorScheme.warning: androidx.compose.ui.graphics.Color
    get() = androidx.compose.ui.graphics.Color(UIColors.WARNING_COLOR) // Orange

@Suppress("unused") // Extension for ColorScheme theming consistency
@get:Composable
val ColorScheme.onWarning: androidx.compose.ui.graphics.Color
    get() = androidx.compose.ui.graphics.Color(UIColors.ON_WARNING_COLOR)
