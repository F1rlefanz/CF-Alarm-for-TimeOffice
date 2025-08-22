package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.AlphaValues
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.FontSizes
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.BorderConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.AnimationDurations
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Modern animated countdown timer component
 */
@Composable
fun CountdownTimer(
    targetTime: LocalDateTime,
    modifier: Modifier = Modifier,
    onTimeUp: () -> Unit = {}
) {
    var timeRemaining by remember { mutableStateOf(Duration.ZERO) }
    var isExpired by remember { mutableStateOf(false) }
    
    // Update timer every second
    LaunchedEffect(targetTime) {
        while (true) {
            val now = LocalDateTime.now()
            val remaining = Duration.between(now, targetTime)
            
            timeRemaining = if (remaining.isNegative) {
                if (!isExpired) {
                    isExpired = true
                    onTimeUp()
                }
                Duration.ZERO
            } else {
                isExpired = false
                remaining
            }
            
            delay(AnimationDurations.TIMER_UPDATE_MS)
        }
    }
    
    // Extract time components
    val days = timeRemaining.toDays()
    val hours = timeRemaining.toHours() % 24
    val minutes = (timeRemaining.toMinutes() % 60)
    
    // Animation states
    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val pulseScale = pulseAnimation.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(AnimationDurations.PULSE_MS.toInt(), easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val urgencyColor = when {
        days > 0 -> MaterialTheme.colorScheme.primary
        hours > 12 -> MaterialTheme.colorScheme.primary
        hours > 6 -> MaterialTheme.colorScheme.secondary
        hours > 1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(pulseScale.value),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = SpacingConstants.CARD_ELEVATION * 2
        ),
        shape = RoundedCornerShape(SpacingConstants.CARD_CORNER_RADIUS * 2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            urgencyColor.copy(alpha = AlphaValues.LIGHT),
                            urgencyColor.copy(alpha = AlphaValues.VERY_LIGHT),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.SPACING_EXTRA_LARGE),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
            ) {
                // Header with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    Icon(
                        imageVector = if (isExpired) Icons.Filled.NotificationsActive else Icons.Filled.Timer,
                        contentDescription = null,
                        tint = urgencyColor,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                    )
                    Text(
                        text = if (isExpired) UIText.TIME_EXPIRED else UIText.TIME_UNTIL_ALARM,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Time display
                if (!isExpired) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (days > 0) {
                            TimeUnit(
                                value = days.toInt(),
                                unit = UIText.UNIT_DAYS,
                                color = urgencyColor
                            )
                        }
                        
                        TimeUnit(
                            value = hours.toInt(),
                            unit = UIText.UNIT_HOURS,
                            color = urgencyColor,
                            isHighlighted = days == 0L
                        )
                        
                        TimeUnitSeparator()
                        
                        TimeUnit(
                            value = minutes.toInt(),
                            unit = UIText.UNIT_MINUTES,
                            color = urgencyColor,
                            isHighlighted = days == 0L && hours == 0L
                        )
                    }
                } else {
                    // Expired state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpacingConstants.SPACING_SMALL),
                        contentAlignment = Alignment.Center
                    ) {
                        val blink = rememberInfiniteTransition(label = "blink")
                        val alpha = blink.animateFloat(
                            initialValue = AlphaValues.STRONG,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(AnimationDurations.BLINK_MS.toInt()),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "blinkAlpha"
                        )
                        
                        Text(
                            text = UIText.ALARM_ACTIVE,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.alpha(alpha.value)
                        )
                    }
                }
                
                // Target time display
                Text(
                    text = targetTime.format(DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TimeUnit(
    value: Int,
    unit: String,
    color: Color,
    isHighlighted: Boolean = false
) {
    val displayValue = value.toString().padStart(2, '0')
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = if (isHighlighted) SpacingConstants.ICON_SIZE_XXL + SpacingConstants.SPACING_SMALL else SpacingConstants.ICON_SIZE_XXL,
                    height = if (isHighlighted) SpacingConstants.ICON_SIZE_XXL + SpacingConstants.SPACING_SMALL else SpacingConstants.ICON_SIZE_XXL
                )
                .clip(RoundedCornerShape(SpacingConstants.SPACING_LARGE))
                .background(
                    color = if (isHighlighted) 
                        color.copy(alpha = AlphaValues.MEDIUM) 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaValues.SURFACE_VARIANT)
                )
                .then(
                    if (isHighlighted) 
                        Modifier.border(BorderConstants.HIGHLIGHTED_WIDTH.dp, color, RoundedCornerShape(SpacingConstants.SPACING_LARGE))
                    else 
                        Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayValue,
                fontSize = if (isHighlighted) FontSizes.COUNTDOWN_LARGE.sp else FontSizes.COUNTDOWN_NORMAL.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHighlighted) color else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
        
        Text(
            text = unit,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun TimeUnitSeparator() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.height(SpacingConstants.ICON_SIZE_XXL)
    ) {
        Box(
            modifier = Modifier
                .size(SpacingConstants.SPACING_EXTRA_SMALL)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
        Box(
            modifier = Modifier
                .size(SpacingConstants.SPACING_EXTRA_SMALL)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CountdownTimerPreview() {
    CFAlarmForTimeOfficeTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            // In 2 days
            CountdownTimer(
                targetTime = LocalDateTime.now().plusDays(2).plusHours(5).plusMinutes(30)
            )
            
            // In 5 hours
            CountdownTimer(
                targetTime = LocalDateTime.now().plusHours(5).plusMinutes(15)
            )
            
            // In 45 minutes
            CountdownTimer(
                targetTime = LocalDateTime.now().plusMinutes(45).plusSeconds(30)
            )
            
            // In 30 seconds
            CountdownTimer(
                targetTime = LocalDateTime.now().plusSeconds(30)
            )
            
            // Expired
            CountdownTimer(
                targetTime = LocalDateTime.now().minusMinutes(5)
            )
        }
    }
}
