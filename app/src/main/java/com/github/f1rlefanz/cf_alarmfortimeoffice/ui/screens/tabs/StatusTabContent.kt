package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.UnusedAppRestrictionsHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.util.concurrent.TimeUnit

@Composable
fun StatusTabContent(
    authState: AuthState,
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    calendarViewModel: CalendarViewModel?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        Text(
            "System-Status",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Auth Status
        StatusCard(
            title = "Authentifizierung",
            isOk = authState.isSignedIn,
            details = if (authState.isSignedIn) {
                "Angemeldet als ${authState.userEmail ?: "Unbekannt"}"
            } else {
                "Nicht angemeldet"
            }
        )

        // Kalender Status
        StatusCard(
            title = "Kalender",
            isOk = calendarState.selectedCalendarIds.isNotEmpty() && calendarState.calendarAuthorizationValid,
            details = when {
                !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() -> 
                    "⚠️ Kalender-Autorisierung verloren - Bitte neu anmelden"
                calendarState.selectedCalendarIds.isEmpty() -> "Kein Kalender ausgewählt"
                calendarState.availableCalendars.isEmpty() -> "Keine Kalender verfügbar"
                else -> "${calendarState.selectedCalendarIds.size} Kalender ausgewählt, API-Zugriff OK"
            }
        )

        // Schicht-Konfiguration Status
        StatusCard(
            title = "Schicht-Konfiguration",
            isOk = shiftState.currentShiftConfig != null,
            details = if (shiftState.currentShiftConfig != null) {
                "${shiftState.currentShiftConfig.definitions.size} Schichttypen definiert"
            } else {
                "Keine Konfiguration verfügbar"
            }
        )

        // Schicht-Erkennung Status
        StatusCard(
            title = "Schicht-Erkennung",
            isOk = shiftState.recognizedShifts.isNotEmpty(),
            details = when {
                shiftState.recognizedShifts.isEmpty() -> "Keine Schichten erkannt"
                else -> "${shiftState.recognizedShifts.size} Schichten erkannt"
            }
        )

        // Alarm Status
        StatusCard(
            title = "Alarme",
            isOk = alarmState.hasActiveAlarms,
            details = when {
                !alarmState.hasActiveAlarms -> "Keine aktiven Alarme"
                else -> "${alarmState.activeAlarms.size} Alarme gesetzt"
            }
        )

        // Vollbild-Berechtigung: ohne sie kommt der Weck-Screen nie hoch
        FullScreenIntentCard()

        // Akku-Ausnahme: ohne sie darf Android die 6h-Wartung und die exakten Wecker-Alarme
        // im Doze/Standby einfrieren — die zweite OS-Berechtigung, an der die Hintergrund-
        // Zuverlaessigkeit haengt, direkt neben dem Vollbild-Wecker.
        BatteryOptimizationCard()

        // "App bei Nichtnutzung pausieren": am 20.07.2026 live nachgewiesen, dass dieser
        // Schalter die App per Force-Stop killt und dabei alle gesetzten Wecker-Alarme
        // loescht — unabhaengig von der Akku-Ausnahme oben (separater Mechanismus).
        UnusedAppRestrictionsCard()

        // Letzter Hintergrund-Sync (6h-Wartung: Token -> Kalender -> Wecker)
        LastSyncCard()

        // Debug-Informationen
        DebugInfoCard()
        
        // Cache-Statistiken und Offline-Status
        CacheStatusCard(calendarViewModel = calendarViewModel)
    }
}

/**
 * Meldet, ob die App eine Vollbild-Benachrichtigung zeigen darf — und fuehrt bei Bedarf direkt
 * in die zustaendige Systemeinstellung.
 *
 * Warum eine eigene Karte statt einer Zeile in den Empfehlungen: Seit Android 14 entzieht der
 * Play Store USE_FULL_SCREEN_INTENT nach der Installation allen Apps, die er nicht als Wecker-
 * oder Telefonie-App einstuft. Ohne die Berechtigung degradiert Android den Full-Screen-Intent
 * still zu einem Banner — der Wecker klingelt, aber der Weck-Screen kommt nie hoch, und nichts
 * weist darauf hin. Ein reiner Hinweistext ohne Absprung waere hier wertlos.
 *
 * Der Zustand wird bei jedem Aufruf frisch gelesen (kein remember), damit die Karte nach der
 * Rueckkehr aus den Einstellungen sofort umspringt.
 */
@Composable
private fun FullScreenIntentCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bei jedem ON_RESUME neu pruefen: der Nutzer kann die Berechtigung ausserhalb der App
    // aendern, und danach muss die Karte stimmen.
    var canUseFsi by remember { mutableStateOf(checkFullScreenIntentAllowed(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canUseFsi = checkFullScreenIntentAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (canUseFsi) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (canUseFsi)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Vollbild-Wecker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (canUseFsi) {
                        "Der Weck-Bildschirm darf angezeigt werden"
                    } else {
                        "⚠️ Nicht erlaubt — der Wecker erscheint nur als Banner, " +
                            "der Weck-Bildschirm kommt nicht von selbst hoch"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!canUseFsi) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    TextButton(
                        onClick = { openFullScreenIntentSettings(context) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Einstellung öffnen")
                    }
                }
            }
        }
    }
}

/**
 * Meldet, ob die App von der Akku-Optimierung ausgenommen ist — die Grundvoraussetzung dafuer,
 * dass die 6h-Wartung UND die exakten Wecker-Alarme im Hintergrund ueberhaupt feuern duerfen.
 *
 * Warum eine eigene Statuskarte: Ist die App NICHT ausgenommen, darf Android sie im Doze/Standby
 * einfrieren — dann werden keine neuen Schichten mehr abgeholt und der Wecker bleibt still, ohne
 * dass etwas darauf hinweist. Der Settings-Tab hat zwar eine Warnkarte, aber die verschwindet,
 * sobald man ausgenommen ist; hier ist der Zustand DAUERHAFT ablesbar (gruen = ok), genau wie
 * beim Vollbild-Wecker daneben.
 *
 * Wie [FullScreenIntentCard] wird der Zustand bei jedem ON_RESUME frisch gelesen (kein remember
 * ueber den Lebenszyklus hinweg), damit die Karte nach der Rueckkehr aus dem System-Dialog sofort
 * auf gruen springt. Der Knopf loest Androids System-Dialog aus ("Zulassen, dass die App immer im
 * Hintergrund laeuft?") — kein Einstellungs-Menue, deshalb verspricht der Text auch keinen Ablauf.
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isExempt by remember { mutableStateOf(BatteryOptimizationHelper.isExempted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = BatteryOptimizationHelper.isExempted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExempt) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isExempt)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Akku-Ausnahme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isExempt) {
                        "Der Wecker darf jederzeit im Hintergrund laufen"
                    } else {
                        "⚠️ Android darf die App einfrieren — dann werden keine Schichten mehr " +
                            "abgeholt und der Wecker bleibt still"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isExempt) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    TextButton(
                        onClick = {
                            (context as? android.app.Activity)?.let {
                                BatteryOptimizationHelper.requestExemption(it)
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Ausnahme erlauben")
                    }
                }
            }
        }
    }
}

/**
 * Meldet, ob Android "App bei Nichtnutzung pausieren" fuer diese App aktiv haelt — ein
 * eigenstaendiger Mechanismus, unabhaengig von der Akku-Ausnahme oben (die schuetzt nur vor
 * Doze/Standby, nicht vor diesem Force-Stop-Pfad). Live am 20.07.2026 als Ursache eines
 * ausgebliebenen Weckers nachgewiesen: aktiv geht die App per Force-Stop unter, alle gesetzten
 * Alarme werden dabei lautlos geloescht.
 *
 * Der Check ist async (ListenableFuture, kein synchroner Getter wie bei Battery) - deshalb hier
 * ueber [LaunchedEffect] statt eines synchronen `remember`-Initializers, per [refreshTrigger]
 * bei jedem ON_RESUME neu angestossen.
 */
@Composable
private fun UnusedAppRestrictionsCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isOk by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isOk = !UnusedAppRestrictionsHelper.isRestricted(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isOk)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nicht verwendete Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isOk) {
                        "\"Bei Nichtnutzung pausieren\" ist aus - der Wecker bleibt aktiv"
                    } else {
                        "⚠️ Android darf die App pausieren — dabei gehen alle gesetzten " +
                            "Wecker-Alarme verloren"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isOk) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    UnusedAppRestrictionsHelper.createSettingsIntent(context)
                                )
                            } catch (e: Exception) {
                                Logger.e(
                                    LogTags.UNUSED_APP_RESTRICTIONS,
                                    "Failed to open unused-app-restrictions settings",
                                    e
                                )
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Einstellung öffnen")
                    }
                }
            }
        }
    }
}

private fun checkFullScreenIntentAllowed(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    } else {
        // < API 34: Die Berechtigung wird mit der Installation gewaehrt und nicht entzogen.
        true
    }

private fun openFullScreenIntentSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                "package:${context.packageName}".toUri()
            )
        )
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "❌ Einstellung für Vollbild-Benachrichtigungen nicht erreichbar", e)
    }
}

@Composable
private fun StatusCard(
    title: String,
    isOk: Boolean,
    details: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isOk)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun CacheStatusCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    var cacheStats by remember { mutableStateOf("Cache-Statistiken laden...") }
    val isOffline by remember { 
        derivedStateOf { !isNetworkAvailable(context) }
    }
    
    // Nur einmal laden, nicht bei jeder Recomposition
    LaunchedEffect(calendarViewModel) {
        calendarViewModel?.let { viewModel ->
            try {
                viewModel.getCacheStats()
                cacheStats = "Cache-Statistiken in Log ausgegeben"
            } catch (_: Exception) {
                cacheStats = "Cache-Statistiken nicht verfügbar"
            }
        } ?: run {
            cacheStats = "Cache-Statistiken nicht verfügbar (kein ViewModel)"
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.CloudOff else Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = if (isOffline)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.success
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isOffline) "Offline-Modus" else "Cache-Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isOffline) "Offline - verwende gespeicherte Daten" else "Online - Cache aktiv",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Cache Actions
                if (calendarViewModel != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        IconButton(
                            onClick = { 
                                calendarViewModel.getCacheStats()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Cache-Stats aktualisieren",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider()
            
            // Cache Statistics
            Text(
                "Cache-Details:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                cacheStats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Cache Actions Row
            if (calendarViewModel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    OutlinedButton(
                        onClick = { calendarViewModel.clearEventCache() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cache leeren")
                    }
                    
                    Button(
                        onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Neu laden")
                    }
                }
            }
        }
    }
}

@Composable
private fun LastSyncCard() {
    val context = LocalContext.current
    var lastMaintenanceTime by remember { mutableStateOf(0L) }

    // Wartungszeit laden und alle 30s aktualisieren
    LaunchedEffect(Unit) {
        lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
        while (true) {
            kotlinx.coroutines.delay(30_000)
            lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
        }
    }

    val timeSinceLastMaintenance = if (lastMaintenanceTime > 0) {
        System.currentTimeMillis() - lastMaintenanceTime
    } else {
        -1L
    }

    val lastMaintenanceText = when {
        lastMaintenanceTime == 0L -> "Noch nie ausgeführt"
        timeSinceLastMaintenance < 0 -> "Unbekannt"
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(1) ->
            "Vor ${TimeUnit.MILLISECONDS.toMinutes(timeSinceLastMaintenance)} Minuten"
        timeSinceLastMaintenance < TimeUnit.DAYS.toMillis(1) ->
            "Vor ${TimeUnit.MILLISECONDS.toHours(timeSinceLastMaintenance)} Stunden"
        else ->
            "Vor ${TimeUnit.MILLISECONDS.toDays(timeSinceLastMaintenance)} Tagen"
    }

    val statusColor = when {
        lastMaintenanceTime == 0L -> MaterialTheme.colorScheme.tertiary
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(12) -> MaterialTheme.colorScheme.primary
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(24) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                tint = statusColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Letzter Sync",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    lastMaintenanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
                if (timeSinceLastMaintenance > TimeUnit.HOURS.toMillis(24)) {
                    Text(
                        "⚠️ Langer Zeitraum - bitte prüfen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugInfoCard() {
    val context = LocalContext.current
    var showEmailSuccess by remember { mutableStateOf(false) }
    var showEmailError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteResultMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                "Debug-Informationen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Logging-Beschreibung
            Text(
                "Wie funktioniert das Logging?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Die App schreibt Logs nun täglich in separate Dateien (z.B. debug_logs_2026-07-12.txt) und behält diese für genau 8 Tage, um eine vollständige Woche abbilden zu können. Ältere Dateien werden automatisch bereinigt. Beim Versenden werden alle vorhandenen Dateien angehängt.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Hinweis: Die Logs enthalten Diagnosedaten (Gerätemodell, App-Version, Zeitstempel, App-Ereignisse). Beim Senden öffnet sich der Teilen-Dialog – vorausgefüllt als E-Mail an cfischer@csj.de, oder du wählst eine andere App.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Log-Datei Info
            com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.getLogFileInfo(context)?.let { info ->
                HorizontalDivider()
                Text(
                    "Log-Datei:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Button zum E-Mail-Versand
            Button(
                onClick = {
                    val result = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.sendLogFileViaEmail(context)
                    if (result.isSuccess) {
                        showEmailSuccess = true
                    } else {
                        showEmailError = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.hasLogFile(context)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Logs an Entwickler senden")
            }
            
            // Erfolgs-/Fehlermeldungen
            if (showEmailSuccess) {
                Text(
                    "✅ E-Mail-App geöffnet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    showEmailSuccess = false
                }
            }

            showEmailError?.let { error ->
                Text(
                    "❌ $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(5000)
                    showEmailError = null
                }
            }

            // Button zum manuellen Aufraeumen - bewusst NICHT an den Versand oben gekoppelt
            // (siehe LogEmailUtil.deleteOldLogs-Doku): die heutige Datei bleibt garantiert
            // erhalten, unabhaengig davon, wann am Tag getippt wird.
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Alte Logs löschen")
            }

            deleteResultMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    deleteResultMessage = null
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Alte Logs löschen?") },
            text = {
                Text("Löscht alle Log-Dateien außer der von heute. Die heutige, noch aktive Datei bleibt erhalten.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.deleteOldLogs(context)
                    deleteResultMessage = "🗑️ $deleted Datei(en) gelöscht"
                    showDeleteConfirm = false
                }) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

/**
 * Überprüft die Netzwerkverbindung
 */
private fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } else {
        @Suppress("DEPRECATION")
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        @Suppress("DEPRECATION")
        activeNetworkInfo?.isConnectedOrConnecting == true
    }
}
