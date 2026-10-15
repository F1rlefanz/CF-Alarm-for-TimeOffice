package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht

import android.provider.Settings
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzLand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.Etikett
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.EtikettTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.OrtBildschirmZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.StandortFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TageslichtViewModel

/**
 * Einstieg mit ViewModel und Berechtigungsdialog (#140).
 *
 * Die Berechtigung (nur `ACCESS_COARSE_LOCATION`) wird AUSSCHLIESSLICH auf den Knopfdruck
 * angefragt, und der Begruendungssatz steht direkt ueber dem Knopf - Play verlangt die Begruendung
 * im Zusammenhang VOR dem Systemdialog. Ist sie schon erteilt, wird sofort abgefragt.
 */
@Composable
fun OrtFuerTageslichtRoute(
    onNavigateBack: () -> Unit,
    viewModel: TageslichtViewModel = hiltViewModel()
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Einmal je Besuch (nicht je Drehung): das ViewModel haengt an der Activity und traegt sonst
    // Fehler und Eingaben des letzten Besuchs mit (siehe TageslichtViewModel.beimOeffnen)
    var geoeffnet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!geoeffnet) {
            viewModel.beimOeffnen()
            geoeffnet = true
        }
    }
    val berechtigung = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erteilt ->
        if (erteilt) viewModel.standortUebernehmen() else viewModel.standortAbgelehnt()
    }
    OrtFuerTageslichtScreen(
        zustand = zustand,
        onNavigateBack = onNavigateBack,
        onStandortUebernehmen = {
            val erteilt = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (erteilt) {
                viewModel.standortUebernehmen()
            } else {
                berechtigung.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        },
        onStandortEntfernen = viewModel::standortEntfernen,
        onLandWahl = viewModel::landWahl,
        onPlzEingabe = viewModel::plzEingabe
    )
}

@Composable
fun OrtFuerTageslichtScreen(
    zustand: OrtBildschirmZustand,
    onNavigateBack: () -> Unit,
    onStandortUebernehmen: () -> Unit,
    onStandortEntfernen: () -> Unit,
    onLandWahl: (PlzLand) -> Unit,
    onPlzEingabe: (String) -> Unit
) {
    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                stringResource(R.string.tageslicht_ort_titel),
                onNavigateBack,
                hilfe = HilfeThema.ANLEITUNG_TAGESLICHT
            )
        }
    ) { innen ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innen),
            contentPadding = PaddingValues(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
        ) {
            item {
                Text(
                    stringResource(R.string.tageslicht_ort_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { AktuellKarte(zustand) }
            item {
                StufenKarte(
                    nummer = 1,
                    titel = stringResource(R.string.tageslicht_stufe_standort),
                    genutzt = zustand.genutzt == OrtQuelle.STANDORT,
                    vonGenauererVerdeckt = false
                ) {
                    zustand.standortUebernommenAm?.let {
                        Text(stringResource(R.string.tageslicht_standort_uebernommen, it), style = MaterialTheme.typography.bodyMedium)
                    }
                    // Der Begruendungssatz steht DIREKT ueber dem Knopf, der den Systemdialog
                    // ausloest (Play: Begruendung im Zusammenhang, vor der Anfrage). Nichts dazwischen.
                    Text(
                        stringResource(R.string.tageslicht_stufe_standort_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Untereinander, linksbuendig (Skill UI: Knoepfe an Listenzeilen)
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
                        if (zustand.standortUebernommenAm == null) {
                            CompactButton(
                                onClick = onStandortUebernehmen,
                                text = stringResource(R.string.tageslicht_standort_uebernehmen),
                                icon = Icons.Default.LocationOn,
                                enabled = !zustand.standortLaeuft
                            )
                        } else {
                            CompactOutlinedButton(
                                onClick = onStandortUebernehmen,
                                text = stringResource(R.string.tageslicht_standort_uebernehmen),
                                icon = Icons.Default.LocationOn,
                                enabled = !zustand.standortLaeuft
                            )
                        }
                        // Status und Fehler direkt unter DEM Knopf, den sie betreffen (Skill UI)
                        StandortHinweis(zustand)
                        if (zustand.standortUebernommenAm != null) {
                            CompactOutlinedButton(
                                onClick = onStandortEntfernen,
                                text = stringResource(R.string.tageslicht_standort_entfernen),
                                enabled = !zustand.standortLaeuft
                            )
                        }
                    }
                }
            }
            item {
                StufenKarte(
                    nummer = 2,
                    titel = stringResource(R.string.tageslicht_stufe_hand),
                    genutzt = zustand.genutzt == OrtQuelle.PLZ,
                    vonGenauererVerdeckt = zustand.genutzt == OrtQuelle.STANDORT && zustand.plzRegion != null
                ) {
                    Text(
                        stringResource(R.string.tageslicht_stufe_hand_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
                        PlzLand.entries.forEach { land ->
                            FilterChip(
                                selected = zustand.land == land,
                                onClick = { onLandWahl(land) },
                                label = { Text(landName(land)) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = zustand.plzEingabe,
                        onValueChange = { eingabe -> onPlzEingabe(eingabe.filter(Char::isDigit).take(5)) },
                        label = { Text(stringResource(R.string.tageslicht_plz)) },
                        singleLine = true,
                        isError = zustand.plzUnbekannt,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = when {
                            zustand.plzUnbekannt -> {
                                { Text(stringResource(R.string.tageslicht_plz_unbekannt)) }
                            }
                            zustand.plzRegion != null -> {
                                { Text(stringResource(R.string.tageslicht_plz_region, zustand.plzRegion)) }
                            }
                            else -> null
                        }
                    )
                    // Namensnennung CC BY 4.0 (GeoNames) - an der Stelle, die die Daten nutzt
                    Text(
                        stringResource(R.string.tageslicht_plz_quelle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                StufenKarte(
                    nummer = 3,
                    titel = stringResource(R.string.tageslicht_stufe_zeitzone),
                    genutzt = zustand.genutzt == OrtQuelle.ZEITZONE,
                    vonGenauererVerdeckt = zustand.genutzt == OrtQuelle.STANDORT || zustand.genutzt == OrtQuelle.PLZ
                ) {
                    Text(
                        zustand.zeitzoneOrt?.let { stringResource(R.string.tageslicht_stufe_zeitzone_text, zustand.zeitzone, it) }
                            ?: stringResource(R.string.tageslicht_stufe_zeitzone_unbekannt, zustand.zeitzone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AktuellKarte(zustand: OrtBildschirmZustand) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                stringResource(R.string.tageslicht_aktuell),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when (zustand.genutzt) {
                    OrtQuelle.STANDORT -> stringResource(R.string.tageslicht_quelle_standort)
                    OrtQuelle.PLZ -> stringResource(
                        R.string.tageslicht_quelle_plz,
                        zustand.genutztPlz ?: zustand.plzEingabe,
                        (zustand.genutztName ?: zustand.plzRegion).orEmpty()
                    )
                    OrtQuelle.ZEITZONE -> stringResource(R.string.tageslicht_quelle_zeitzone, zustand.zeitzone, zustand.zeitzoneOrt.orEmpty())
                    null -> stringResource(R.string.tageslicht_quelle_keine)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (zustand.genutzt == null) {
                Text(stringResource(R.string.tageslicht_keine_folge), style = MaterialTheme.typography.bodyMedium)
            } else if (zustand.daemmerungHeute != null && zustand.sonnenaufgangHeute != null) {
                Text(
                    stringResource(R.string.tageslicht_heute, zustand.daemmerungHeute, zustand.sonnenaufgangHeute),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Laufende Abfrage oder der Grund, warum nichts uebernommen wurde - jeweils mit Ausweg. */
@Composable
private fun StandortHinweis(zustand: OrtBildschirmZustand) {
    val fehler = zustand.standortFehler.takeUnless { zustand.standortLaeuft }
    val text = when {
        zustand.standortLaeuft -> stringResource(R.string.tageslicht_standort_laeuft)
        fehler == StandortFehler.ABGELEHNT -> stringResource(R.string.tageslicht_standort_abgelehnt)
        fehler == StandortFehler.ABGESCHALTET -> stringResource(R.string.tageslicht_standort_abgeschaltet)
        fehler == StandortFehler.FEHLGESCHLAGEN -> stringResource(R.string.tageslicht_standort_fehlgeschlagen)
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (fehler != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Ausweg dorthin, wo es sich aendern laesst: nach einer DAUERHAFTEN Ablehnung zeigt Android den
    // Berechtigungsdialog nie wieder, "Standort übernehmen" scheitert dann still sofort - ohne diesen
    // Knopf gaebe es aus der App heraus keinen Weg mehr zur Freigabe.
    val context = LocalContext.current
    when (fehler) {
        StandortFehler.ABGELEHNT -> CompactOutlinedButton(
            onClick = {
                oeffneEinstellung(
                    context,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
            text = stringResource(R.string.tageslicht_app_einstellungen)
        )
        StandortFehler.ABGESCHALTET -> CompactOutlinedButton(
            onClick = { oeffneEinstellung(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            text = stringResource(R.string.tageslicht_standort_einstellungen)
        )
        else -> Unit
    }
}

/**
 * Ein Hilfeknopf darf die App nicht beenden: fehlt fuer die Action eine Activity (eingeschraenktes
 * oder Arbeitsprofil, abgespeckte Hersteller-ROM), wirft `startActivity` eine
 * ActivityNotFoundException aus dem onClick - dieselbe Konvention wie `oeffneExactAlarmEinstellung`
 * in StatusPermissionCards.
 */
private fun oeffneEinstellung(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Logger.w(LogTags.TAGESLICHT, "Einstellungsbildschirm nicht erreichbar: ${intent.action}", e)
    }
}

/**
 * Eine Stufe der Kette. Die genutzte Stufe wird ADDITIV markiert (Rand + Etikett), nie durch
 * Wegnehmen der Flaeche (Skill UI); eine Stufe, die eine genauere ueberdeckt, sagt das in Worten.
 */
@Composable
private fun StufenKarte(
    nummer: Int,
    titel: String,
    genutzt: Boolean,
    vonGenauererVerdeckt: Boolean,
    inhalt: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (genutzt) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Text(
                    "$nummer · $titel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (genutzt) Etikett(stringResource(R.string.tageslicht_aktiv), EtikettTon.ERFOLG)
            }
            inhalt()
            if (vonGenauererVerdeckt) {
                Text(
                    stringResource(R.string.tageslicht_genauer_oben),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun landName(land: PlzLand): String = stringResource(
    when (land) {
        PlzLand.DE -> R.string.tageslicht_land_de
        PlzLand.AT -> R.string.tageslicht_land_at
        PlzLand.CH -> R.string.tageslicht_land_ch
    }
)
