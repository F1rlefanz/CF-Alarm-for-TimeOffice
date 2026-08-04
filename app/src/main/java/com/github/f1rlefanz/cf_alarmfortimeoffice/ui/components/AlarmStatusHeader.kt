package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState

/**
 * Icon + Titel + 3-Zweig-Status-Text (aktive Alarme / lädt / keine Alarme), geteilt zwischen
 * Home- und Wecker-Tab. `trailingContent` haengt ein optionales drittes Element (z.B. Chevron)
 * an dieselbe Row an - die aufrufende Karte bestimmt Padding/Klickbarkeit selbst.
 */
@Composable
fun AlarmStatusHeader(
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.AccessAlarm,
            contentDescription = null,
            modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
            tint = when {
                skipState.isNextAlarmSkipped -> MaterialTheme.colorScheme.warning
                alarmState.hasActiveAlarms -> MaterialTheme.colorScheme.success
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Alarm-Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (alarmState.hasActiveAlarms) {
                Text("${alarmState.activeAlarms.size} aktive Alarme")
                alarmState.nextAlarmTime?.let {
                    Text("Nächster Alarm: $it")
                }
            } else if (alarmState.isLoading) {
                Text(
                    "Wird geladen …",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Keine aktiven Alarme",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        trailingContent?.invoke()
    }
}
