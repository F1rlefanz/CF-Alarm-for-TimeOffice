package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackupUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Zustand der Export/Import-Karte. [result] ist die Rueckmeldung, die der Nutzer sehen MUSS -
 * ein stiller Import waere bei einer Funktion, die Konfiguration ueberschreibt, unzumutbar.
 */
data class ConfigBackupUiState(
    val busy: Boolean = false,
    val result: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class ConfigBackupViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val configBackupUseCase: ConfigBackupUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigBackupUiState())
    val uiState: StateFlow<ConfigBackupUiState> = _uiState.asStateFlow()

    /** Dateiname mit Datum - damit mehrere Sicherungen unterscheidbar bleiben. */
    fun suggestedFileName(): String =
        "cf-alarm-konfiguration-${LocalDateTime.now().format(FILE_DATE)}.json"

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = ConfigBackupUiState(busy = true)
            val outcome = runCatching {
                val content = configBackupUseCase.export(
                    appVersion = BuildConfig.VERSION_NAME,
                    createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ).getOrThrow()

                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("Datei konnte nicht zum Schreiben geoeffnet werden")
                }
                content.length
            }

            _uiState.value = outcome.fold(
                onSuccess = { size ->
                    Logger.business(LogTags.APP, "📤 EXPORT: $size Zeichen geschrieben")
                    ConfigBackupUiState(result = "Konfiguration exportiert.")
                },
                onFailure = { error ->
                    Logger.e(LogTags.APP, "❌ EXPORT fehlgeschlagen", error)
                    ConfigBackupUiState(
                        result = "Export fehlgeschlagen: ${error.message ?: "unbekannter Fehler"}",
                        isError = true
                    )
                }
            )
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = ConfigBackupUiState(busy = true)
            val outcome = runCatching {
                val content = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Datei konnte nicht gelesen werden")
                }
                configBackupUseCase.import(content).getOrThrow()
            }

            _uiState.value = outcome.fold(
                onSuccess = { summary ->
                    // Ehrliche Rueckmeldung mit Zahlen: "importiert" allein liesse offen, ob
                    // wirklich etwas angekommen ist - und abgelehnte Schluessel werden BENANNT,
                    // nicht verschwiegen.
                    ConfigBackupUiState(
                        result = buildString {
                            append("Importiert: ${summary.shiftDefinitions} Schichtdefinitionen, ")
                            append("${summary.settingsKeys} Einstellungen, ${summary.hueKeys} Hue-Werte.")
                            // Bridge-lokale Lampen-IDs reisen in der Datei mit und zeigen auf
                            // einer anderen Bridge ins Leere. `null` heisst "nicht geprueft" -
                            // das wird gesagt, nicht als "in Ordnung" ausgegeben.
                            summary.unresolvedHueTargets?.let { unresolved ->
                                if (unresolved > 0) {
                                    append("\n\n$unresolved Hue-Regel-Ziel(e) gibt es auf deiner ")
                                    append("Bridge nicht — im Hue-Bereich neu auswählen. ")
                                    append("Was sich über den Lampennamen wiederfinden ließ, ")
                                    append("wurde bereits zugeordnet.")
                                }
                            }
                            if (summary.rejectedKeys.isNotEmpty()) {
                                append("\n\nNicht übernommen (gehört zum Gerät oder ist ein Laufzeitwert): ")
                                append(summary.rejectedKeys.joinToString(", "))
                            }
                            append("\n\nHinweis: Anmeldung, Kalenderauswahl und die Hue-Bridge-Verbindung ")
                            append("sind absichtlich nicht Teil der Datei — die richtest du auf diesem Gerät ein.")
                        }
                    )
                },
                onFailure = { error ->
                    Logger.e(LogTags.APP, "❌ IMPORT fehlgeschlagen", error)
                    ConfigBackupUiState(
                        result = "Import fehlgeschlagen: ${error.message ?: "Datei nicht lesbar"}",
                        isError = true
                    )
                }
            )
        }
    }

    fun dismissResult() {
        _uiState.value = _uiState.value.copy(result = null, isError = false)
    }

    private companion object {
        val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern(DateTimeFormats.FILE_STAMP)
    }
}
