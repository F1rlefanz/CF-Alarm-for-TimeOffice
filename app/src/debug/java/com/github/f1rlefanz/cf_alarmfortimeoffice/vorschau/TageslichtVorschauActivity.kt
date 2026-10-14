package com.github.f1rlefanz.cf_alarmfortimeoffice.vorschau

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RuleModeCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ShiftPatternCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.DunkelheitEtikett
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.NachlaufBisHellWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.OrtBildschirmZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.OrtFuerTageslichtScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.PlzLand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.TageslichtKarte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme

/**
 * WEGWERF-Vorschau fuer #140 - nur im Debug-Build, wird vor dem Merge geloescht.
 *
 * Fuettert die echten, zustandslosen Bausteine aus `ui/screens/tageslicht/` mit festen Beispielen.
 * Die Sonnenzeiten sind ECHT (NOAA, München 48,14° N / 11,58° O, 15.10.2026 bzw. 21.06.2026),
 * nur eben nicht live gerechnet.
 *
 *   adb shell am start -n <paket>/com.github.f1rlefanz.cf_alarmfortimeoffice.vorschau.TageslichtVorschauActivity \
 *       --es seite ort|dimmer|hue --es variante <siehe unten>
 */
class TageslichtVorschauActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val seite = intent.getStringExtra("seite") ?: "ort"
        val variante = intent.getStringExtra("variante") ?: ""
        setContent {
            CFAlarmForTimeOfficeTheme {
                when (seite) {
                    "dimmer" -> DimmerSeite(variante)
                    "hue" -> HueSeite(variante)
                    else -> OrtSeite(variante)
                }
            }
        }
    }
}

// --- Ort -----------------------------------------------------------------------------------

@Composable
private fun OrtSeite(variante: String) {
    val start = when (variante) {
        "plz" -> OrtBildschirmZustand(
            genutzt = OrtQuelle.PLZ, daemmerungHeute = "07:00", sonnenaufgangHeute = "07:31",
            standortUebernommenAm = null, land = PlzLand.DE, plzEingabe = "80", plzRegion = "München",
            plzUnbekannt = false, zeitzone = "Europe/Berlin", zeitzoneOrt = "Erfurt"
        )
        "zeitzone" -> OrtBildschirmZustand(
            genutzt = OrtQuelle.ZEITZONE, daemmerungHeute = "07:06", sonnenaufgangHeute = "07:39",
            standortUebernommenAm = null, land = PlzLand.DE, plzEingabe = "", plzRegion = null,
            plzUnbekannt = false, zeitzone = "Europe/Berlin", zeitzoneOrt = "Erfurt"
        )
        "keiner" -> OrtBildschirmZustand(
            genutzt = null, daemmerungHeute = null, sonnenaufgangHeute = null,
            standortUebernommenAm = null, land = PlzLand.DE, plzEingabe = "00", plzRegion = null,
            plzUnbekannt = true, zeitzone = "America/Sao_Paulo", zeitzoneOrt = null
        )
        else -> OrtBildschirmZustand(
            genutzt = OrtQuelle.STANDORT, daemmerungHeute = "07:00", sonnenaufgangHeute = "07:31",
            standortUebernommenAm = "14.10.2026", land = PlzLand.DE, plzEingabe = "80", plzRegion = "München",
            plzUnbekannt = false, zeitzone = "Europe/Berlin", zeitzoneOrt = "Erfurt"
        )
    }
    var zustand by remember { mutableStateOf(start) }
    OrtFuerTageslichtScreen(
        zustand = zustand,
        onNavigateBack = {},
        onStandortUebernehmen = {},
        onStandortEntfernen = {},
        onLandWahl = { zustand = zustand.copy(land = it) },
        onPlzEingabe = { zustand = zustand.copy(plzEingabe = it) }
    )
}

// --- Dimmer: Ausschnitt "Ende" des Fenster-Editors, Anker "Weckzeit, spaetestens" ------------

@Composable
private fun DimmerSeite(variante: String) {
    var wahl by remember {
        mutableStateOf(
            when (variante) {
                "immer" -> HellAb.IMMER
                "daemmerung" -> HellAb.DAEMMERUNG
                else -> HellAb.SONNENAUFGANG
            }
        )
    }
    val ohneOrt = variante == "ohneort"
    val sonnenaufgang = stringResource(R.string.tageslicht_ereignis_sonnenaufgang)
    val daemmerung = stringResource(R.string.tageslicht_ereignis_daemmerung)
    // Drei echte Lagen (München): Winter-Fruehdienst, Spaetdienst mit Daemmerung im Nachlauf, Sommer.
    val (beispiel, vorschauZeile, vorschauGrund) = when {
        variante == "sommer" -> Triple(
            stringResource(R.string.tageslicht_dimmer_beispiel, "So 21.06. 05:15", "05:15") + " (" +
                stringResource(R.string.tageslicht_dimmer_grund_weckzeit, sonnenaufgang, "05:13") + ")",
            "Sa 22:30 → So 05:15",
            stringResource(R.string.tageslicht_vorschau_endet, sonnenaufgang, "05:13")
        )
        wahl == HellAb.DAEMMERUNG -> Triple(
            stringResource(R.string.tageslicht_dimmer_beispiel, "Do 06:45", "07:01") + " (" +
                stringResource(R.string.tageslicht_dimmer_grund_hell, daemmerung, "07:01") + ")",
            "Mi 22:30 → Do 07:01",
            stringResource(R.string.tageslicht_vorschau_endet, daemmerung, "07:01")
        )
        else -> Triple(
            stringResource(R.string.tageslicht_dimmer_beispiel, "Do 05:15", "06:00") + " (" +
                stringResource(R.string.tageslicht_dimmer_grund_nachlauf, 45, sonnenaufgang, "07:32") + ")",
            "Mi 22:30 → Do 06:00",
            null
        )
    }

    Scaffold(topBar = { SimpleBackTopAppBar("Regel bearbeiten", {}) }) { innen ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Nachbau der unveraenderten Zeilen aus WindowEditor (dort private)
                        Text(stringResource(R.string.dimmer_window_end), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.dimmer_end_clock)) }
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.dimmer_end_alarm)) }
                            OutlinedButton(onClick = {}) { Text(stringResource(R.string.dimmer_anchor_shiftend)) }
                            Button(onClick = {}) { Text(stringResource(R.string.dimmer_end_alarm_sonst_clock)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.dimmer_clock_time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {}) { Text("08:00") }
                        }
                        Text(stringResource(R.string.dimmer_end_alarm_sonst_clock_hinweis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = "45", onValueChange = {}, singleLine = true,
                            label = { Text(stringResource(R.string.dimmer_end_nachlauf)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(stringResource(R.string.dimmer_end_nachlauf_hinweis, 120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // NEU (#140)
                        NachlaufBisHellWahl(
                            wahl = wahl,
                            onWahl = { wahl = it },
                            ohneOrt = ohneOrt,
                            beispiel = beispiel,
                            onOrtEinstellen = {}
                        )
                        TextButton(onClick = {}) { Text(stringResource(R.string.dimmer_window_remove)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.dimmer_preview_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                // Zeile der Dimm-Vorschau, ergaenzt um den Grund, wenn Tageslicht das Ende setzt
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dimmer_preview_row, vorschauZeile, 80), style = MaterialTheme.typography.bodyMedium)
                        if (wahl != HellAb.IMMER && !ohneOrt && vorschauGrund != null) {
                            Text(vorschauGrund, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// --- Hue: Regel-Editor mit Modus "Sonnenaufgang" UND der neuen Tageslicht-Karte --------------

@Composable
private fun HueSeite(variante: String) {
    var wahl by remember {
        mutableStateOf(
            when (variante) {
                "immer" -> HellAb.IMMER
                "daemmerung" -> HellAb.DAEMMERUNG
                else -> HellAb.SONNENAUFGANG
            }
        )
    }
    val ohneOrt = variante == "ohneort"
    val sonnenaufgang = stringResource(R.string.tageslicht_ereignis_sonnenaufgang)
    val daemmerung = stringResource(R.string.tageslicht_ereignis_daemmerung)
    val naechster = when {
        variante == "sommer" -> stringResource(R.string.tageslicht_hue_naechster_aus, "So 21.06. 05:15", sonnenaufgang, "05:13")
        wahl == HellAb.DAEMMERUNG -> stringResource(R.string.tageslicht_hue_naechster_an, "Do 05:15", daemmerung, "07:01")
        else -> stringResource(R.string.tageslicht_hue_naechster_an, "Do 05:15", sonnenaufgang, "07:32")
    }

    Scaffold(topBar = { SimpleBackTopAppBar("Regel bearbeiten", {}) }) { innen ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ShiftPatternCard(
                    selectedShiftPattern = "Frühdienst",
                    onShiftPatternChange = {},
                    availableShiftPatterns = listOf("Frühdienst", "Spätdienst", "Nachtdienst"),
                    showValidationErrors = false
                )
            }
            item {
                TageslichtKarte(
                    wahl = wahl,
                    onWahl = { wahl = it },
                    ortText = if (ohneOrt) null else "Standort (gerundet)",
                    naechster = naechster,
                    onOrtAendern = {}
                )
            }
            item { RuleModeCard(modus = HueRuleModus.SONNENAUFGANG, onModusChange = {}) }
            item {
                // Ausschnitt Regel-Liste: Etikett an der Regelkarte
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Aufstehlicht Schlafzimmer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Frühdienst · Sonnenaufgang 30 Min", style = MaterialTheme.typography.bodyMedium)
                        if (wahl != HellAb.IMMER) DunkelheitEtikett()
                    }
                }
            }
        }
    }
}
