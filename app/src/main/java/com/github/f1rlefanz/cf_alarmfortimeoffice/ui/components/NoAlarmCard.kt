package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.UIColors
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.GraphicsConstants

/**
 * Card shown when no alarm is set
 */
@Composable
fun NoAlarmCard(
    reason: NoAlarmReason,
    modifier: Modifier = Modifier,
    onActionClick: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "no_alarm")
    
    val floatAnimation = infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(UIConstants.ANIMATION_DURATION_LONG_MS.toInt(), easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )
    
    val alphaAnimation = infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween((UIConstants.ANIMATION_DURATION_LONG_MS * 0.7).toInt(), easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = floatAnimation.value.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = SpacingConstants.CARD_ELEVATION
        ),
        shape = RoundedCornerShape(SpacingConstants.CARD_CORNER_RADIUS)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            reason.color.copy(alpha = 0.1f),
                            reason.color.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        radius = GraphicsConstants.GRADIENT_RADIUS
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
            ) {
                // Animated icon
                Box(
                    modifier = Modifier
                        .size(SpacingConstants.ICON_SIZE_XXL + SpacingConstants.SPACING_LARGE)
                        .clip(CircleShape)
                        .background(
                            color = reason.color.copy(alpha = alphaAnimation.value * 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = reason.icon,
                        contentDescription = null,
                        tint = reason.color,
                        modifier = Modifier
                            .size(SpacingConstants.ICON_SIZE_EXTRA_LARGE)
                            .rotate(floatAnimation.value)
                    )
                }
                
                // Title
                Text(
                    text = reason.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                // Description
                Text(
                    text = reason.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(0.8f)
                )
                
                // Action button (if provided)
                onActionClick?.let {
                    Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
                    
                    Button(
                        onClick = it,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = reason.color
                        ),
                        shape = RoundedCornerShape(SpacingConstants.SPACING_LARGE),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = reason.actionIcon,
                            contentDescription = null,
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                        )
                        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                        Text(
                            text = reason.actionText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reasons why no alarm is set
 */
sealed class NoAlarmReason(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val actionText: String,
    val actionIcon: androidx.compose.ui.graphics.vector.ImageVector
)

class NoShiftFound : NoAlarmReason(
    title = "Keine Schicht gefunden",
    description = "In den nächsten Tagen wurden keine Schichten in deinem Kalender gefunden.",
    icon = Icons.Outlined.EventBusy,
    color = Color(UIColors.STATUS_SUCCESS), // Green
    actionText = "Kalender auswählen",
    actionIcon = Icons.Filled.CalendarMonth
)

class AutoAlarmDisabled : NoAlarmReason(
    title = "Auto-Alarm deaktiviert",
    description = "Automatische Wecker sind momentan ausgeschaltet. Aktiviere sie in den Einstellungen.",
    icon = Icons.Outlined.NotificationsOff,
    color = Color(UIColors.STATUS_WARNING), // Orange
    actionText = "Einstellungen öffnen",
    actionIcon = Icons.Filled.Settings
)

class NoCalendarSelected : NoAlarmReason(
    title = "Kein Kalender ausgewählt",
    description = "Bitte wähle einen Dienstplan-Kalender aus, um automatische Wecker zu aktivieren.",
    icon = Icons.Outlined.CalendarMonth,
    color = Color(UIColors.STATUS_INFO), // Blue
    actionText = "Kalender auswählen",
    actionIcon = Icons.Filled.Add
)

class LoadingShifts : NoAlarmReason(
    title = "Lade Schichten...",
    description = "Deine Kalenderdaten werden gerade abgerufen. Einen Moment bitte.",
    icon = Icons.Outlined.Refresh,
    color = Color(UIColors.STATUS_LOADING), // Purple
    actionText = "",
    actionIcon = Icons.Filled.Refresh
)

@Preview(showBackground = true)
@Composable
fun NoAlarmCardPreview() {
    CFAlarmForTimeOfficeTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            NoAlarmCard(
                reason = NoShiftFound(),
                onActionClick = {}
            )
            
            NoAlarmCard(
                reason = AutoAlarmDisabled(),
                onActionClick = {}
            )
            
            NoAlarmCard(
                reason = NoCalendarSelected(),
                onActionClick = {}
            )
            
            NoAlarmCard(
                reason = LoadingShifts()
            )
        }
    }
}
