package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/** Statischer Fehler-Banner (errorContainer-Karte). Fuer schwerere Faelle mit Icon/Dismiss/Retry: [ErrorMessage]. */
@Composable
fun InlineErrorCard(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
