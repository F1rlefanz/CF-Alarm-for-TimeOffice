package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TageslichtEnde
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only Vorschau der naechsten Dimm-Abschnitte - identische Berechnung wie der echte
 * Scheduler ([DimScheduleUseCase.previewTimeline]), aber ohne jeden Seiteneffekt. Loest das
 * "ich muss die Anker-Logik im Kopf simulieren"-Problem: hier steht direkt, was passieren wird.
 */
@Composable
fun DimmerPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshTimeline() }

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = stringResource(R.string.dimmer_preview_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (timeline.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dimmer_preview_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(timeline) { interval ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(
                                R.string.dimmer_preview_row,
                                formatRange(interval.range),
                                interval.strength
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // #140: warum dieser Abschnitt frueher endet als "Weckzeit + Nachlauf".
                        interval.tageslicht?.let { grund ->
                            Text(
                                text = tageslichtGrundZeile(grund),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// Locale.GERMAN bleibt zwingend: ohne das Argument haengt der Wochentagsname an der Systemsprache.
private val DAY_TIME_FORMAT = DateTimeFormatter.ofPattern(DateTimeFormats.DAY_TIME, Locale.GERMAN)

private val TIME_FORMAT = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY, Locale.GERMAN)

/**
 * "endet bei Sonnenaufgang 07:31" - oder, wenn es schon vor dem Wecken hell war, "an der
 * Weckzeit, Sonnenaufgang war schon um 07:31" (das Fenster endet dann an der Weckzeit, nie davor).
 */
@Composable
private fun tageslichtGrundZeile(grund: TageslichtEnde): String {
    val uhrzeit = Instant.ofEpochMilli(grund.hellZeitpunkt).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
    val ereignis = tageslichtEreignisName(grund.hellAb)
    return if (grund.vorWeckzeit) {
        stringResource(R.string.tageslicht_dimmer_grund_weckzeit, ereignis, uhrzeit)
    } else {
        stringResource(R.string.tageslicht_vorschau_endet, ereignis, uhrzeit)
    }
}

/**
 * Nutzername des Ereignisses hinter einer [HellAb]-Wahl ("Dämmerung"/"Sonnenaufgang") - fuer
 * Vorschau und Editor-Beispiel. [HellAb.IMMER] kuerzt nie und taucht hier nicht auf; faellt es
 * doch hinein, ist "Sonnenaufgang" die harmlose Beschriftung.
 */
@Composable
internal fun tageslichtEreignisName(hellAb: HellAb): String = stringResource(
    when (hellAb) {
        HellAb.DAEMMERUNG -> R.string.tageslicht_ereignis_daemmerung
        HellAb.SONNENAUFGANG, HellAb.IMMER -> R.string.tageslicht_ereignis_sonnenaufgang
    }
)

private fun formatRange(range: LongRange): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(range.first).atZone(zone).format(DAY_TIME_FORMAT)
    val end = Instant.ofEpochMilli(range.last).atZone(zone).format(DAY_TIME_FORMAT)
    return "$start → $end"
}
