package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.UIConstants
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
    severity: ErrorSeverity = ErrorSeverity.ERROR,
    onDismiss: (() -> Unit)? = null,
    autoDismissAfterMs: Long? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
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
            delay(autoDismissAfterMs)
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

/**
 * Snackbar-style error message
 */
@Composable
fun ErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionColor = MaterialTheme.colorScheme.error
            )
        }
    )
}

/**
 * Full-screen error state
 */
@Composable
fun ErrorScreen(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(SpacingConstants.SPACING_XXL)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_XXL)
            )
            
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
            
            Text(
                text = "Ein Fehler ist aufgetreten",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_EXTRA_LARGE))
                
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Erneut versuchen")
                }
            }
        }
    }
}