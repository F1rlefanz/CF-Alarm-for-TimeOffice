package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Error message types for different severity levels
 */
enum class ErrorSeverity {
    INFO,
    WARNING,
    ERROR
}

/**
 * Composable for displaying error messages with optional auto-dismiss
 */
@Composable
fun ErrorMessage(
    message: String,
    modifier: Modifier = Modifier,
    severity: ErrorSeverity = ErrorSeverity.ERROR,
    onDismiss: (() -> Unit)? = null,
    autoDismissAfterMs: Long? = null,
    onRetry: (() -> Unit)? = null
) {
    if (message.isBlank()) return

    val (icon, containerColor, contentColor) = when (severity) {
        ErrorSeverity.INFO -> Triple(
            Icons.Default.Info,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ErrorSeverity.WARNING -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ErrorSeverity.ERROR -> Triple(
            Icons.Default.Error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    // Auto-dismiss effect
    LaunchedEffect(message, autoDismissAfterMs) {
        if (autoDismissAfterMs != null && autoDismissAfterMs > 0) {
            delay(autoDismissAfterMs.milliseconds)
            onDismiss?.invoke()
        }
    }

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
                imageVector = icon,
                contentDescription = severity.name,
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

                if (onRetry != null) {
                    Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = contentColor
                        )
                    ) {
                        Text("Erneut versuchen")
                    }
                }
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
