package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onSignIn: () -> Unit
) {
    val authState by authViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingConstants.SPACING_EXTRA_LARGE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Icon placeholder
        Surface(
            modifier = Modifier.size(SpacingConstants.APP_ICON_SIZE),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Text(
                    UIText.APP_ICON_LETTERS,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_XXL))

        Text(
            text = UIText.APP_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))

        Text(
            text = UIText.APP_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_XXXL))

        Button(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(SpacingConstants.BUTTON_HEIGHT_LARGE),
            enabled = !authState.calendarOps.calendarsLoading
        ) {
            if (authState.calendarOps.calendarsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Modern credential icon
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_partial_secure),
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_MEDIUM))
                    Text("Mit Google anmelden")
                }
            }
        }

        authState.errors.error?.let { error ->
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_XXL))

        Text(
            text = UIText.PERMISSION_EXPLANATION,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingConstants.SPACING_XXL)
        )
    }
}
