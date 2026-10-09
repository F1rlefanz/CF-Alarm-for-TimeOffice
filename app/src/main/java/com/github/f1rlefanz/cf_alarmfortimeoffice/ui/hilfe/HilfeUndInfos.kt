package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig

/**
 * Einstieg in Anleitung, Problemhilfe, Aenderungen und Datenschutz.
 *
 * Nur "Was ist neu" bleibt in der App (es gehoert zur installierten Version); alles andere
 * fuehrt auf die Website - das Pfeil-Symbol sagt, dass man die App dabei verlaesst.
 */
@Composable
fun HilfeUndInfosKarte() {
    val context = LocalContext.current
    var zeigeNeuigkeiten by remember { mutableStateOf<List<NeuigkeitenVersion>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Eintrag("Anleitung", "So richtest du die App ein", Icons.AutoMirrored.Outlined.HelpOutline, extern = true) {
                oeffneHilfe(context, HilfeThema.ANLEITUNG)
            }
            Eintrag("Hilfe bei Problemen", "Wenn der Wecker nicht klingelt und mehr", Icons.Outlined.Build, extern = true) {
                oeffneHilfe(context, HilfeThema.PROBLEME)
            }
            Eintrag("Was ist neu", "Version ${BuildConfig.VERSION_NAME}", Icons.Outlined.NewReleases, extern = false) {
                zeigeNeuigkeiten = ladeNeuigkeiten(context)
            }
            Eintrag("Datenschutz", null, Icons.Outlined.PrivacyTip, extern = true) {
                oeffneHilfe(context, HilfeThema.DATENSCHUTZ)
            }
        }
    }

    zeigeNeuigkeiten?.let { versionen ->
        NeuigkeitenDialog(
            titel = "Was ist neu",
            versionen = versionen,
            onDismiss = { zeigeNeuigkeiten = null }
        )
    }
}

@Composable
private fun Eintrag(
    titel: String,
    text: String?,
    icon: ImageVector,
    extern: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(titel) },
        supportingContent = text?.let { { Text(it) } },
        // dekorativ: der Titel daneben sagt es
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (extern) {
            { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Öffnet im Browser") }
        } else null
    )
}
