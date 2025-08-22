package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

@Composable
fun LoadingScreen(
    modifier: Modifier = Modifier,
    message: String = "Laden..."
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
            strokeWidth = SpacingConstants.SPACING_EXTRA_SMALL
        )
        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}