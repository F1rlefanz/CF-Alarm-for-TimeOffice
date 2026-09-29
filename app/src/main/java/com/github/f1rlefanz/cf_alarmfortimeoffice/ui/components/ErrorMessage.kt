package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Composable for displaying error messages with optional dismiss button
 */
@Composable
fun ErrorMessage(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    if (message.isBlank()) return

    val containerColor = MaterialTheme.colorScheme.errorContainer
    val contentColor = MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SpacingConstants.PADDING_SCREEN_HORIZONTAL,
                vertical = SpacingConstants.SPACING_SMALL
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "ERROR",
                tint = contentColor,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
            )

            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_MEDIUM))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = message,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Schließen",
                        tint = contentColor,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                    )
                }
            }
        }
    }
}
