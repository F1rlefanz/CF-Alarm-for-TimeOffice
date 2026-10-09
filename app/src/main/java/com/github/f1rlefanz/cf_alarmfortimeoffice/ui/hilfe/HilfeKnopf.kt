package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Das "?" in der Kopfzeile: fuehrt zu dem Abschnitt der Anleitung, der genau diesen Bildschirm
 * erklaert. Die Beschriftung sagt, dass es die App verlaesst - das Symbol allein sagt das nicht.
 */
@Composable
fun HilfeKnopf(thema: HilfeThema) {
    val context = LocalContext.current
    IconButton(onClick = { oeffneHilfe(context, thema) }) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = "Anleitung im Browser öffnen"
        )
    }
}

/** "Mehr dazu" unter einer Warnung: fuehrt zur Problemhilfe fuer genau diese Warnung. */
@Composable
fun MehrDazuKnopf(thema: HilfeThema) {
    val context = LocalContext.current
    TextButton(onClick = { oeffneHilfe(context, thema) }) {
        Text("Mehr dazu")
    }
}
