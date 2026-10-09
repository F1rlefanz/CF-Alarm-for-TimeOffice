package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Liest den beigelegten Changelog-Ausschnitt. Fehlt er, ist das ein Build-Fehler - kein Absturz. */
fun ladeNeuigkeiten(context: Context): List<NeuigkeitenVersion> = try {
    context.assets.open(Neuigkeiten.ASSET).bufferedReader(Charsets.UTF_8).use {
        Neuigkeiten.parse(it.readText())
    }
} catch (e: Exception) {
    Logger.w(LogTags.UI, "Changelog-Ausschnitt nicht lesbar", e)
    emptyList()
}

/**
 * Merkt sich, fuer welche Version "Was ist neu" schon gezeigt wurde.
 *
 * SharedPreferences statt DataStore: es ist eine reine Anzeige-Notiz, gelesen nur bei offener
 * Oberflaeche (also entsperrt) und ohne Folgen, wenn sie verloren geht - dann erscheint der
 * Dialog eben einmal mehr. Ein Store am Application-Graphen waere dafuer zu schwer.
 */
private object NeuigkeitenGedaechtnis {
    private const val DATEI = "neuigkeiten"
    private const val KEY_GEZEIGT = "gezeigt_fuer_version"

    /** Die Neuigkeit, die noch zu zeigen ist - oder `null`. */
    fun offen(context: Context): NeuigkeitenVersion? {
        val prefs = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val aktuell = BuildConfig.VERSION_NAME
        if (prefs.getString(KEY_GEZEIGT, null) == aktuell) return null
        // Wer die App gerade erst installiert hat, braucht keine Liste von Aenderungen an etwas,
        // das er noch nie gesehen hat. Ein Update auf diese Version zeigt sie dagegen - auch das
        // allererste, in dem es den Merker noch nicht gab.
        val eintrag = if (istFrischInstalliert(context)) null
        else Neuigkeiten.zurVersion(ladeNeuigkeiten(context), aktuell)
        if (eintrag == null) gesehen(context)
        return eintrag
    }

    fun gesehen(context: Context) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit {
            putString(KEY_GEZEIGT, BuildConfig.VERSION_NAME)
        }
    }

    private fun istFrischInstalliert(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (e: Exception) {
        false
    }
}

/** Zeigt nach einem Update einmal, was diese Version mitbringt. */
@Composable
fun NeuigkeitenNachUpdate() {
    val context = LocalContext.current
    var offen by remember { mutableStateOf<NeuigkeitenVersion?>(null) }
    LaunchedEffect(Unit) {
        offen = withContext(Dispatchers.IO) { NeuigkeitenGedaechtnis.offen(context) }
    }
    offen?.let { version ->
        NeuigkeitenDialog(
            titel = "Neu in ${version.titel}",
            versionen = listOf(version),
            onDismiss = {
                NeuigkeitenGedaechtnis.gesehen(context)
                offen = null
            }
        )
    }
}

@Composable
fun NeuigkeitenDialog(titel: String, versionen: List<NeuigkeitenVersion>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titel) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
            ) {
                if (versionen.isEmpty()) {
                    Text("Die Liste der Änderungen ist in dieser Version nicht enthalten.")
                }
                versionen.forEach { version ->
                    // Bei einer einzigen Version steht sie schon im Titel.
                    if (versionen.size > 1) {
                        Text(
                            listOfNotNull(version.titel, version.stand).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    version.zusammenfassung?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    version.rubriken.forEach { rubrik ->
                        if (rubrik.titel.isNotEmpty()) {
                            Text(rubrik.titel, style = MaterialTheme.typography.titleSmall)
                        }
                        rubrik.punkte.forEach { punkt ->
                            Text(
                                buildAnnotatedString {
                                    append("• ")
                                    punkt.kurz?.let {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) }
                                        append(": ")
                                    }
                                    append(punkt.text)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = { oeffneHilfe(context, HilfeThema.ALLE_AENDERUNGEN) }) {
                Text("Alle Änderungen")
            }
        }
    )
}
