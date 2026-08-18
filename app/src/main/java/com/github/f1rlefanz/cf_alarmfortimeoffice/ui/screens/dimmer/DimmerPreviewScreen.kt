package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DimmerPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    // collectAsStateWithLifecycle, nicht collectAsState: die Zeitleiste ist ein reiner
    // Anzeige-Zustand ohne Seiteneffekt (berechnet wird sie ausschliesslich vom LaunchedEffect
    // darunter) - das Abo darf unterhalb von STARTED ruhen.
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
                    Text(
                        text = stringResource(
                            R.string.dimmer_preview_row,
                            formatRange(interval.range),
                            interval.strength
                        ),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// Locale.GERMAN bleibt zwingend: ohne das Argument haengt der Wochentagsname an der Systemsprache.
private val DAY_TIME_FORMAT = DateTimeFormatter.ofPattern(DateTimeFormats.DAY_TIME, Locale.GERMAN)

private fun formatRange(range: LongRange): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(range.first).atZone(zone).format(DAY_TIME_FORMAT)
    val end = Instant.ofEpochMilli(range.last).atZone(zone).format(DAY_TIME_FORMAT)
    return "$start → $end"
}
