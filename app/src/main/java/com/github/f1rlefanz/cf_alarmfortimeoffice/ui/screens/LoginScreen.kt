package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.InlineErrorCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingIconButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
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
        // Echtes App-Logo (skaliert aus Icon.png -> res/drawable-nodpi/ic_app_logo.png)
        Image(
            painter = painterResource(id = R.drawable.ic_app_logo),
            contentDescription = UIText.APP_TITLE,
            modifier = Modifier
                .size(SpacingConstants.APP_ICON_SIZE)
                .clip(MaterialTheme.shapes.large)
        )

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

        LoadingIconButton(
            loading = authState.calendarOps.calendarsLoading,
            text = "Mit Google anmelden",
            onClick = onSignIn,
            icon = {
                // Modern credential icon
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_partial_secure),
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                )
            }
        )

        authState.errors.error?.let { error ->
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
            InlineErrorCard(message = error)
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
