package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    isPositive: Boolean = true,
    actionButton: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPositive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SpacingConstants.CARD_ELEVATION / 2)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPositive) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isPositive) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPositive) 
                                MaterialTheme.colorScheme.onPrimaryContainer 
                            else 
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            actionButton?.let {
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))
                it()
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SpacingConstants.CARD_ELEVATION / 2)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                modifier = Modifier.padding(bottom = SpacingConstants.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                )
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            content()
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary
) {
    when (variant) {
        ButtonVariant.Primary -> {
            Button(
                onClick = onClick,
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = SpacingConstants.PADDING_CARD, vertical = SpacingConstants.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text(text)
            }
        }
        ButtonVariant.Secondary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = SpacingConstants.PADDING_CARD, vertical = SpacingConstants.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text(text)
            }
        }
        ButtonVariant.Error -> {
            Button(
                onClick = onClick,
                modifier = modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                contentPadding = PaddingValues(horizontal = SpacingConstants.PADDING_CARD, vertical = SpacingConstants.SPACING_MEDIUM)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text(text)
            }
        }
    }
}

enum class ButtonVariant {
    Primary,
    Secondary,
    Error
}
