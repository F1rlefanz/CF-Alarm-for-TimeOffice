package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
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
    // collectAsStateWithLifecycle statt collectAsState: reiner Anzeige-Zustand (Ladeflag und
    // Fehlertext). Es haengt kein Seiteneffekt am Sammeln, das Pausieren unterhalb von STARTED
    // ist also folgenlos - der Anmeldevorgang selbst laeuft im ViewModel weiter.
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    // SCROLL-AUSWEG: Logo, Titel, Anmeldeknopf, eine moegliche Fehlerkarte und der
    // Datenschutzhinweis passen bei grosser Schriftskalierung nicht mehr auf einen Bildschirm -
    // ohne Scrollweg waere der Anmeldeknopf dann nicht erreichbar und die App unbenutzbar.
    // `heightIn(min = maxHeight)` haelt die vertikale Zentrierung, solange alles passt.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
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
                        // dekorativ: die Knopfbeschriftung "Mit Google anmelden" sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD)
                    )
                }
            )

            authState.errors.error?.let { error ->
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                InlineErrorCard(message = error)

                // Der Weg zur Behebung, direkt neben der Meldung: Ohne ihn muesste der Nutzer
                // selbst darauf kommen, dass "Android hat kein Google-Konto geliefert" ein
                // Konto in den SYSTEM-Einstellungen meint - und danach suchen.
                //
                // Nur bei genau dieser Meldung (Vergleich gegen die Konstante, kein Textsuchen),
                // denn nur sie kann ein fehlendes Konto bedeuten; bei "Anmeldung wurde
                // abgebrochen" waere der Knopf eine Fehlleitung.
                //
                // UND nur, wenn das Geraet den Sprung wirklich anbietet: `resolveActivity` wird
                // VOR dem Anzeigen gefragt, weil kein Knopf einen Ablauf behaupten darf, den es
                // nicht gibt. Auf einem Geraet ohne diese Einstellungsseite bleibt der Text
                // stehen - er nennt den Weg ja auch in Worten.
                if (error == CredentialAuthManager.FEHLER_KEIN_CREDENTIAL) {
                    val context = LocalContext.current
                    val kontoIntent = remember {
                        Intent(Settings.ACTION_ADD_ACCOUNT)
                            .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                            .takeIf { it.resolveActivity(context.packageManager) != null }
                    }
                    if (kontoIntent != null) {
                        TextButton(onClick = { context.startActivity(kontoIntent) }) {
                            Text(UIText.ADD_GOOGLE_ACCOUNT)
                        }
                    }
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
}
