package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel

/**
 * CalendarSelectionScreen - REFACTORED für Single Source of Truth
 * 
 * ✅ CODE CLEANUP: Updated deprecated Material Icons
 * ✅ PERFORMANCE: Direct imports from theme package
 * ✅ STATE MANAGEMENT: Single Source of Truth pattern
 * ✅ MEMORY: Eliminated temporary state objects
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSelectionScreen(
    calendarViewModel: CalendarViewModel,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val calendarState by calendarViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kalender auswählen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Abbrechen"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave, // SIMPLIFIED: Kein manuelles Save mehr - alles automatisch
                        enabled = calendarState.selectedCalendarIds.isNotEmpty()
                    ) {
                        Text("Speichern")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            Text(
                "Wähle die Kalender aus, die für Schichtalarme überwacht werden sollen.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (calendarState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (calendarState.availableCalendars.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "Keine Kalender verfügbar",
                        modifier = Modifier.padding(SpacingConstants.SPACING_LARGE),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    items(calendarState.availableCalendars) { calendar ->
                        val isSelected = calendarState.selectedCalendarIds.contains(calendar.id)
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                // SINGLE SOURCE OF TRUTH: Direkte Aktualisierung über ViewModel
                                // Kein lokaler State - alles geht durch Repository
                                calendarViewModel.toggleCalendarSelection(calendar.id)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingConstants.SPACING_LARGE),
                                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        calendar.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    calendar.accountName?.let { accountName ->
                                        Text(
                                            accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Ausgewählt",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    // PAGINATION: Load More Button
                    if (calendarState.hasMoreCalendars) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = SpacingConstants.SPACING_MEDIUM),
                                contentAlignment = Alignment.Center
                            ) {
                                if (calendarState.isLoadingMore) {
                                    CircularProgressIndicator()
                                } else {
                                    OutlinedButton(
                                        onClick = { calendarViewModel.loadMoreCalendars() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Weitere Kalender laden")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
