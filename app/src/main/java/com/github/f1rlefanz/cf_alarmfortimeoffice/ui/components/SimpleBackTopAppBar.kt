package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema

/**
 * TopAppBar mit Titel und Zurueck-Pfeil - Standardfall fuer Unterscreens. Einzige moegliche Action
 * ist das "?" zur passenden Anleitung ([hilfe]).
 *
 * Die `contentDescription` ist Pflicht und darf nicht auf `null` zurueckgedreht werden: Bei einem
 * IconButton ohne Textlabel ist das Icon der EINZIGE Traeger der Beschriftung — mit `null` liest
 * TalkBack nur "Schaltfläche" vor. Betrifft alle vier Screens, die diese Leiste nutzen
 * (Dimmer-Regeln, Dimmer-Regel-Editor, Dimmer-Vorschau, DND-Einstellungen). Alle handgebauten
 * Zurueck-Pfeile der App (z. B. HueSettingsScreen, ShiftConfigScreen) sagen ebenfalls "Zurück".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleBackTopAppBar(title: String, onNavigateBack: () -> Unit, hilfe: HilfeThema? = null) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
        },
        actions = { hilfe?.let { HilfeKnopf(it) } }
    )
}
