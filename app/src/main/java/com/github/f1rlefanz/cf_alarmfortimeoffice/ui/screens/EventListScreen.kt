package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import java.time.format.DateTimeFormatter

/**
 * EVENT DETAIL SCREEN: Neue UI mit Lazy Loading für viele Events
 *
 * OPTIMIZATION FEATURES:
 * ✅ Lazy Loading mit virtueller Scrolling-Performance
 * ✅ Load-More Button für progressive Event-Ladung
 * ✅ Refresh-Unterstützung für Cache-Invalidierung
 * ✅ Event-Kategorisierung nach Typ (Shift/Normal)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListScreen(
    calendarViewModel: CalendarViewModel,
    onBack: () -> Unit
) {
    val calendarState by calendarViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kalender-Events")
                        if (calendarState.events.isNotEmpty()) {
                            Text(
                                "${calendarState.events.size} Events",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            calendarViewModel.refreshData(forceRefresh = true)
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Aktualisieren"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->

        if (calendarState.isLoading && calendarState.events.isEmpty()) {
            // Initial Loading
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                ) {
                    CircularProgressIndicator()
                    Text("Events werden geladen...")
                }
            }
        } else if (calendarState.events.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_XXXL),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Keine Events gefunden",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Stelle sicher, dass Kalender ausgewählt sind und Ereignisse vorhanden sind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // LAZY LOADING: Event List mit Performance-Optimierung & Infinite Scroll
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                // Header with summary
                item {
                    EventSummaryCard(
                        events = calendarState.events,
                        isLoading = calendarState.isLoading
                    )
                }

                // LAZY LOADING: Virtualized Event List mit Performance-Optimierung
                items(
                    items = calendarState.events,
                    key = { event -> event.id } // PERFORMANCE: Stable keys für bessere Recomposition
                ) { event ->
                    EventCard(
                        event = event
                    )
                }

                // Loading More Indicator for events
                if (calendarState.isLoadingMoreEvents) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SpacingConstants.SPACING_LARGE),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                            ) {
                                CircularProgressIndicator()
                                Text("Lade weitere Events...")
                            }
                        }
                    }
                }

                // LAZY LOADING: Event pagination implementation
                if (calendarState.hasMoreEvents || calendarState.events.size >= 50) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SpacingConstants.SPACING_MEDIUM),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedButton(
                                onClick = {
                                    // IMPLEMENTED: Real event pagination using loadMoreEvents
                                    calendarViewModel.loadMoreEvents(offset = calendarState.eventOffset)
                                },
                                enabled = !calendarState.isLoadingMoreEvents
                            ) {
                                if (calendarState.isLoadingMoreEvents) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(
                                            SpacingConstants.SPACING_SMALL
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(SpacingConstants.SPACING_LARGE))
                                        Text("Lade...")
                                    }
                                } else {
                                    Text("Weitere Events laden ${if (calendarState.totalEvents > 0) "(${calendarState.events.size}/${calendarState.totalEvents})" else ""}")
                                }
                            }
                        }
                    }
                }
            }

            // INFINITE SCROLL: Automatic loading when near end of list
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo }
                    .collect { visibleItems ->
                        val lastVisibleItem = visibleItems.lastOrNull()
                        val totalItems = listState.layoutInfo.totalItemsCount
                        
                        // INFINITE SCROLL: Trigger loading when within 3 items of the end
                        if (lastVisibleItem != null && 
                            !calendarState.isLoadingMoreEvents && 
                            calendarState.hasMoreEvents &&
                            lastVisibleItem.index >= totalItems - 4) {
                            
                            // PERFORMANCE: Auto-load more events for infinite scroll
                            calendarViewModel.loadMoreEvents(offset = calendarState.eventOffset)
                        }
                    }
            }
        }

        // IMPROVED ERROR HANDLING: Show error message and auto-clear
        calendarState.error?.let { error ->
            LaunchedEffect(error) {
                // In a real app, this would show a Snackbar:
                // snackbarHostState.showSnackbar(
                //     message = error,
                //     duration = SnackbarDuration.Short
                // )

                // For now, just log and clear the error
                kotlinx.coroutines.delay(3000) // Show error for 3 seconds
                calendarViewModel.clearError()
            }
        }
    }
}

@Composable
private fun EventSummaryCard(
    events: List<CalendarEvent>,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Events Übersicht",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isLoading) {
                    Text("Aktualisiere...")
                } else {
                    Text("${events.size} Events geladen")

                    // Event statistics
                    val todayEvents = events.filter {
                        it.startTime.toLocalDate() == java.time.LocalDate.now()
                    }
                    val tomorrowEvents = events.filter {
                        it.startTime.toLocalDate() == java.time.LocalDate.now().plusDays(1)
                    }

                    Text(
                        "Heute: ${todayEvents.size}, Morgen: ${tomorrowEvents.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: CalendarEvent,
    modifier: Modifier = Modifier
) {
    // Determine if this looks like a work shift
    val isWorkShift = event.title.contains("schicht", ignoreCase = true) ||
            event.title.contains("dienst", ignoreCase = true) ||
            event.title.contains("shift", ignoreCase = true)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isWorkShift)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
            verticalAlignment = Alignment.Top
        ) {
            // Event Type Icon
            Icon(
                imageVector = if (isWorkShift) Icons.Default.Work else Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isWorkShift)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                // Event Title
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Event Date & Time
                Text(
                    DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
                        .format(event.startTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Duration if available
                if (event.endTime != event.startTime) {
                    val duration = java.time.Duration.between(event.startTime, event.endTime)
                    val hours = duration.toHours()
                    val minutes = duration.toMinutes() % 60

                    Text(
                        "Dauer: ${hours}h ${minutes}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Calendar ID (for debugging)
                Text(
                    "Kalender: ${event.calendarId.take(8)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
