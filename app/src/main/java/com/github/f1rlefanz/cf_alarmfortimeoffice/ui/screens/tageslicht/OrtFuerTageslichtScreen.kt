package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.Etikett
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.EtikettTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/** Laender mit eingebauter PLZ-Tabelle (#140). Unbekannte Laender fallen auf die Zeitzone zurueck. */
enum class PlzLand { DE, AT, CH }

/**
 * Alles, was der Ort-Bildschirm zeigt - fertig aufbereitet, damit der Bildschirm nichts rechnet.
 *
 * [genutzt] ist die Stufe, die gerade gilt (`null` = kein Ort). Die Uhrzeiten sind bereits
 * formatiert ("07:31"); `null` heisst: an diesem Tag gibt es das Ereignis nicht.
 */
data class OrtBildschirmZustand(
    val genutzt: OrtQuelle?,
    val daemmerungHeute: String?,
    val sonnenaufgangHeute: String?,
    val standortUebernommenAm: String?,
    val land: PlzLand,
    val plzEingabe: String,
    /** Aufgeloeste Region zur Eingabe; `null` bei leerer Eingabe. */
    val plzRegion: String?,
    val plzUnbekannt: Boolean,
    val zeitzone: String,
    /** `null`: fuer diese Zeitzone kennt die App keinen Ort. */
    val zeitzoneOrt: String?
)

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
        topBar = { SimpleBackTopAppBar(stringResource(R.string.tageslicht_ort_titel), onNavigateBack) }
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
                    Text(
                        stringResource(R.string.tageslicht_stufe_standort_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    zustand.standortUebernommenAm?.let {
                        Text(stringResource(R.string.tageslicht_standort_uebernommen, it), style = MaterialTheme.typography.bodyMedium)
                    }
                    // Untereinander, linksbuendig (Skill UI: Knoepfe an Listenzeilen)
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
                        if (zustand.standortUebernommenAm == null) {
                            CompactButton(
                                onClick = onStandortUebernehmen,
                                text = stringResource(R.string.tageslicht_standort_uebernehmen),
                                icon = Icons.Default.LocationOn
                            )
                        } else {
                            CompactOutlinedButton(
                                onClick = onStandortUebernehmen,
                                text = stringResource(R.string.tageslicht_standort_uebernehmen),
                                icon = Icons.Default.LocationOn
                            )
                            CompactOutlinedButton(
                                onClick = onStandortEntfernen,
                                text = stringResource(R.string.tageslicht_standort_entfernen)
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
                    OrtQuelle.PLZ -> stringResource(R.string.tageslicht_quelle_plz, zustand.plzEingabe, zustand.plzRegion.orEmpty())
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
