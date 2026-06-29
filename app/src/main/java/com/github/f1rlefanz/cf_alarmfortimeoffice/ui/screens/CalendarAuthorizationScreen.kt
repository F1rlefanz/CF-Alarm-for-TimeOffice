package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel

/**
 * CalendarAuthorizationScreen - ONBOARDING GATE
 *
 * Shown when the user is signed in (identity established via Credential Manager) but the app
 * does NOT yet hold a valid Google Calendar OAuth token. Previously such users were dropped into
 * a half-broken main screen; this screen makes calendar authorization a real, recoverable
 * onboarding step with a single clear action.
 *
 * - Primary action re-runs [AuthViewModel.requestCalendarAuthorization] (launches the consent
 *   dialog via the Activity).
 * - Errors (denied consent, missing account, etc.) are surfaced inline so the user can retry.
 * - "Abmelden" is the escape hatch (e.g. wrong Google account selected).
 */
@Composable
fun CalendarAuthorizationScreen(
    authViewModel: AuthViewModel,
    onSignOut: () -> Unit
) {
    val authState by authViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val loading = authState.calendarOps.calendarsLoading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.SPACING_EXTRA_LARGE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(SpacingConstants.APP_ICON_SIZE),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_XXL))

        Text(
            text = "Kalender-Zugriff erforderlich",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))

        Text(
            text = "Du bist angemeldet, aber die App hat noch keinen Zugriff auf deinen Google Kalender. " +
                "Für die Schichterkennung wird Lesezugriff auf deinen Kalender benötigt.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingConstants.SPACING_LARGE)
        )

        authState.userEmail?.let { email ->
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
            Text(
                text = "Konto: $email",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_XXXL))

        Button(
            onClick = {
                authViewModel.requestCalendarAuthorization(context as? Activity)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(SpacingConstants.BUTTON_HEIGHT_LARGE),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_MEDIUM))
                    Text("Kalender-Zugriff erlauben")
                }
            }
        }

        authState.error?.let { error ->
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
            text = "Hinweis: Es muss dasselbe Google-Konto auf diesem Gerät hinterlegt sein. " +
                "Fehlt es, füge es zuerst unter Android-Einstellungen → Konten hinzu.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingConstants.SPACING_LARGE)
        )

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))

        TextButton(onClick = onSignOut) {
            Text("Mit anderem Konto anmelden")
        }
    }
}
