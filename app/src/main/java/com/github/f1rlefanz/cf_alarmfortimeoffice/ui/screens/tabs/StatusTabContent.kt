package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SettingsLinkButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.util.concurrent.TimeUnit

@Composable
fun StatusTabContent(
    authState: AuthState,
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    calendarViewModel: CalendarViewModel?,
    authViewModel: AuthViewModel,
    onShowCalendarSelection: () -> Unit
) {
    val context = LocalContext.current

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

        // Auth Status. Bewusst OHNE Aktions-Button: "Nicht angemeldet" ist hier ein
        // architektonisch unerreichbarer Zustand - MainActivity zeigt bei !isSignedIn bereits
        // root-seitig den Login-Screen statt MainScreen/MainContentScreen (Teil davon ist dieser
        // Status-Tab). Ein Button fuer einen Zustand, der nie sichtbar wird, waere irrefuehrender
        // toter Code.
        StatusCard(
            title = "Authentifizierung",
            isOk = authState.isSignedIn,
            details = if (authState.isSignedIn) {
                "Angemeldet als ${authState.userEmail ?: "Unbekannt"}"
            } else {
                "Nicht angemeldet"
            }
        )

        // Kalender Status: zwei echte, unterscheidbare Fehlerzustaende - je einer bekommt seine
        // eigene Aktion (nicht gleichzeitig moeglich, siehe Bedingungen unten). "Keine Kalender
        // verfuegbar" (leeres Google-Konto) bleibt ohne Button - nichts, wohin man von hier aus
        // springen koennte.
        val calendarActionLabel: String?
        val onCalendarAction: (() -> Unit)?
        when {
            !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() -> {
                calendarActionLabel = "Neu anmelden"
                onCalendarAction = {
                    authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                }
            }
            calendarState.selectedCalendarIds.isEmpty() -> {
                calendarActionLabel = "Kalender wählen"
                onCalendarAction = onShowCalendarSelection
            }
            else -> {
                calendarActionLabel = null
                onCalendarAction = null
            }
        }
        StatusCard(
            title = "Kalender",
            isOk = calendarState.selectedCalendarIds.isNotEmpty() && calendarState.calendarAuthorizationValid,
            details = when {
                !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() ->
                    "⚠️ Kalender-Autorisierung verloren - Bitte neu anmelden"
                calendarState.selectedCalendarIds.isEmpty() -> "Kein Kalender ausgewählt"
                calendarState.availableCalendars.isEmpty() -> "Keine Kalender verfügbar"
                else -> "${calendarState.selectedCalendarIds.size} Kalender ausgewählt, API-Zugriff OK"
            },
            actionLabel = calendarActionLabel,
            onAction = onCalendarAction,
            actionEnabled = !authState.calendarOps.calendarsLoading
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

        // Vollbild-Berechtigung: ohne sie kommt der Weck-Screen nie hoch
        NotificationsEnabledCard()

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

        FullScreenIntentCard()

        // Akku-Ausnahme: ohne sie darf Android die 6h-Wartung und die exakten Wecker-Alarme
        // im Doze/Standby einfrieren — die zweite OS-Berechtigung, an der die Hintergrund-
        // Zuverlaessigkeit haengt, direkt neben dem Vollbild-Wecker.
        BatteryOptimizationCard()

        // "App bei Nichtnutzung pausieren": am 20.07.2026 live nachgewiesen, dass dieser
        // Schalter die App per Force-Stop killt und dabei alle gesetzten Wecker-Alarme
        // loescht — unabhaengig von der Akku-Ausnahme oben (separater Mechanismus).
        UnusedAppRestrictionsCard()

        // TimeOffice-Zuverlaessigkeit: CFAlarms Alarme haengen an einem Kalender, den TimeOffice
        // (de.pradtke.timeoffice) lokal befuellt. Live am 30.07.2026 nachgewiesen: dieselben zwei
        // OS-Einschraenkungen wie oben, aber auf TimeOffice selbst, legten den Dienstplan-Sync
        // tagelang lahm. Rendert nichts, wenn TimeOffice nicht installiert ist.
        TimeOfficeHealthCard()

        // Schicht-Dimmer: laeuft der Bedienungshilfen-Dienst? Nur dann kann das Dimm-Overlay
        // ueberhaupt erscheinen. Der Status stand frueher im Dimmer-Tab; hier neben den anderen
        // OS-Berechtigungen ist er dauerhaft ablesbar und der Dienst von einer Stelle aus aktivierbar.
        DimmerAccessibilityCard()

        // DND-Steuerung: Freigabe-Status fuer ACCESS_NOTIFICATION_POLICY, analog zur
        // Bedienungshilfen-Karte des Dimmers.
        DndPermissionCard()

        // Letzter Hintergrund-Sync (6h-Wartung: Token -> Kalender -> Wecker)
        LastSyncCard(calendarViewModel = calendarViewModel)

        // Debug-Informationen
        DebugInfoCard()
        
        // Cache-Statistiken und Offline-Status
        CacheStatusCard(calendarViewModel = calendarViewModel)
    }
}


@Composable
private fun StatusCard(
    title: String,
    isOk: Boolean,
    details: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true
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
                // dekorativ: `details` daneben benennt den Zustand bereits in Worten
                // (z. B. "Nicht angemeldet", "Kein Kalender ausgewählt")
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

                if (!isOk && actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(onClick = onAction, text = actionLabel, enabled = actionEnabled)
                }
            }
        }
    }
}

@Composable
private fun CacheStatusCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    var cacheStats by remember { mutableStateOf("Cache-Statistiken laden...") }

    // KEIN derivedStateOf: Hier stand `remember { derivedStateOf { !isNetworkAvailable(context) } }`.
    // `derivedStateOf` invalidiert ausschliesslich, wenn ein im Block GELESENER Snapshot-State sich
    // aendert — `isNetworkAvailable()` liest aber den ConnectivityManager, keinen Snapshot-State.
    // Der Wert wurde also genau einmal berechnet und blieb fuer die gesamte Lebensdauer der
    // Composition stehen: Flugmodus an, Karte behauptet weiter "Online - Cache aktiv". In einem
    // Screen, dessen Zweck die Diagnose von "warum kam kein Wecker" ist, ist das genau in dem
    // Moment eine Falschaussage, in dem sie zaehlt.
    //
    // Bewusst der NetworkCallback statt des ON_RESUME-Musters der Nachbarkarten: Der gemeldete Fall
    // ist "Flugmodus an, WAEHREND der Tab offen ist" — ON_RESUME feuert dabei nie. Der Callback
    // deckt beides ab, deshalb bleibt es bei EINEM Mechanismus statt zwei.
    var isOffline by remember { mutableStateOf(!isNetworkAvailable(context)) }
    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                isOffline = !isNetworkAvailable(context)
            }

            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = refresh()
        }
        // Der Callback ist reine Diagnose-Anzeige: schlaegt die Registrierung fehl, bleibt es beim
        // Startwert, statt den Status-Tab mitzunehmen.
        val registered = try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            connectivityManager != null
        } catch (_: Exception) {
            false
        }
        onDispose {
            if (registered) {
                try {
                    connectivityManager?.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                    // bereits abgemeldet - nichts zu tun
                }
            }
        }
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
                    // dekorativ: "Offline-Modus"/"Cache-Status" samt Erklaerzeile steht daneben
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
                    // Zwei weight(1f)-Buttons in einer Kartenzeile: genau der Fall, fuer den es
                    // CompactActionButton gibt. Mit der Standard-ContentPadding (24dp pro Seite)
                    // bleiben auf einem 360dp-Geraet nur ~96dp fuer die Schrift — bei groesserer
                    // Systemschrift bricht Compose "Cache leeren" mitten im Wort um (dasselbe
                    // Symptom, das im HueSettingsScreen als "Bea/rbei/ten" gemeldet wurde).
                    CompactOutlinedButton(
                        onClick = { calendarViewModel.clearEventCache() },
                        text = "Cache leeren",
                        modifier = Modifier.weight(1f)
                    )

                    CompactButton(
                        onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                        text = "Neu laden",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastSyncCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    // mutableLongStateOf statt mutableStateOf(0L): kein Autoboxing des Zeitstempels bei jedem
    // 30s-Tick (Delegat-Nutzung unveraendert).
    var lastMaintenanceTime by remember { mutableLongStateOf(0L) }

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
                // dekorativ: "Letzter Sync" und der Abstand in Worten stehen daneben, die
                // Warnung bei >24h zusaetzlich als eigener Text
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
                    if (calendarViewModel != null) {
                        Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                        SettingsLinkButton(
                            onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                            text = "Jetzt synchronisieren"
                        )
                    }
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
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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

    // Kein SDK_INT-Zweig mehr: minSdk ist 26, der frühere else-Zweig (deprecated
    // activeNetworkInfo, nur < API 23) war unerreichbar.
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

    return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
