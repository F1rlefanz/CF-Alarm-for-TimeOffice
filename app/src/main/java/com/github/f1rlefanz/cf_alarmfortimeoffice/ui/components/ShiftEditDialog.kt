package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftEditDialog(
    shift: ShiftDefinition?,
    onDismiss: () -> Unit,
    onSave: (ShiftDefinition) -> Unit
) {
    val isNewShift = shift == null
    
    var name by remember { mutableStateOf(shift?.name ?: "") }
    var keywords by remember { mutableStateOf(shift?.keywords ?: listOf("")) }
    // TIME_ONLY (ANZEIGE), NICHT PERSIST_TIME: diese beiden Stellen zeigen die vom Nutzer
    // eingetippte Weckzeit an und parsen sie zurueck. Persistiert wird sie danach ueber
    // LocalTimeSerializer, der bewusst PERSIST_TIME benutzt. Die Formate sind entkoppelt - wer
    // sie spaeter zusammenlegt, koppelt die Eingabe-Interpretation an ein Persistenzformat.
    var alarmTimeString by remember {
        mutableStateOf(shift?.alarmTime?.format(DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY))
            ?: String.format(Locale.ROOT, "%02d:%02d", AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE))
    }
    var isEnabled by remember { mutableStateOf(shift?.isEnabled ?: true) }
    var isSilent by remember { mutableStateOf(shift?.isSilent ?: false) }

    // Time formatter (siehe Hinweis oben: ANZEIGE-Format, nicht das Persistenzformat)
    val timeFormatter = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(LayoutFractions.DIALOG_WIDTH)
                .fillMaxHeight(LayoutFractions.DIALOG_HEIGHT)
        ) {
            Column(
                modifier = Modifier
                .fillMaxSize()
                .padding(SpacingConstants.SPACING_EXTRA_LARGE)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isNewShift) "Neue Schichtdefinition" else "Schicht bearbeiten",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                
                // Content
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
                ) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Schichtname") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = name.isBlank()
                        )
                    }
                    
                    item {
                        Text(
                            text = "Erkennungsmuster",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ein Muster trifft, wenn es im Titel des Kalendertermins als " +
                                "eigenes Wort vorkommt (\"IMCF\" trifft \"IMCF Dienst\", nicht " +
                                "\"IMCF2\"). Der Schichtname oben zählt ab zwei Zeichen " +
                                "ebenfalls als Muster.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Pattern inputs
                    keywords.forEachIndexed { index, keyword ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = keyword,
                                    onValueChange = { newValue ->
                                        keywords = keywords.toMutableList().apply {
                                            this[index] = newValue
                                        }
                                    },
                                    label = { Text("Muster ${index + 1}") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    isError = keyword.isBlank(),
                                    // Ein einzelner Buchstabe ist kein Tippfehler, sondern eine
                                    // Falle: die Erkennung laeuft ueber ALLE ausgewaehlten
                                    // Kalender, und "Kino mit F" hat damit einen echten Wecker
                                    // um 05:30 erzeugt. Deshalb sichtbar warnen statt verbieten -
                                    // wer sein Kuerzel wirklich einbuchstabig braucht, darf das.
                                    supportingText = if (keyword.trim().length == 1) {
                                        {
                                            Text(
                                                text = "Ein einzelner Buchstabe trifft auch " +
                                                    "fremde Termine (z. B. \"Kino mit F\") und " +
                                                    "weckt dich dann an freien Tagen.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                                
                                if (keywords.size > 1) {
                                    IconButton(
                                        onClick = {
                                            keywords = keywords.filterIndexed { i, _ -> i != index }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Remove,
                                            contentDescription = "Muster entfernen",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    item {
                        TextButton(
                            onClick = { keywords = keywords + "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // dekorativ: "Weiteres Muster hinzufügen" steht als Knopftext daneben
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                            Text("Weiteres Muster hinzufügen")
                        }
                    }
                    
                    item {
                        Text(
                            text = "Weckzeit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = alarmTimeString,
                                onValueChange = { newValue ->
                                    // Validate time format
                                    if (newValue.matches(Regex("^\\d{0,2}:?\\d{0,2}$"))) {
                                        alarmTimeString = newValue
                                    }
                                },
                                label = { Text("Zeit (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = try {
                                    LocalTime.parse(alarmTimeString, timeFormatter)
                                    false
                                } catch (_: Exception) {
                                    true
                                }
                            )
                            
                            Text(
                                text = "Format: 06:30",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // weight(1f): siehe Kommentar bei "Schichtdefinition aktiviert" -
                            // ohne das schiebt der Text bei großer Systemschrift den Schalter raus.
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stille Schicht",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Kein Ton/Vibration/Vollbild-Wecker - die Zeit bleibt als Anker fuer Dimmer/DND erhalten",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isSilent,
                                onCheckedChange = { isSilent = it }
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // weight(1f): ohne das nimmt sich der Text bei großer Systemschrift die
                            // ganze (ohnehin schmale) Dialogbreite und schiebt den Schalter aus dem
                            // Bild - dieselbe Falle wie zuvor in den Hue-Karten.
                            Text(
                                text = "Schichtdefinition aktiviert",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { isEnabled = it }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    CompactOutlinedButton(
                        onClick = onDismiss,
                        text = "Abbrechen",
                        modifier = Modifier.weight(1f)
                    )
                    
                    Button(
                        onClick = {
                            // TRIMMEN ist Pflicht, nicht Kosmetik: ein per Tastatur/
                            // Autovervollstaendigung angehaengtes Leerzeichen wurde als " IMCF"
                            // gespeichert und legte die Schicht lautlos still (Wortgrenzen-Regex,
                            // siehe ShiftDefinition.matchesKeywords). `isNotBlank()` allein hat
                            // das durchgelassen, weil " IMCF" nicht blank ist. `distinct()`
                            // verhindert doppelte Muster nach dem Trimmen.
                            val validKeywords = keywords
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                            val parsedAlarmTime = try {
                                LocalTime.parse(alarmTimeString, timeFormatter)
                            } catch (_: Exception) {
                                null
                            }
                            
                            if (name.isNotBlank() && validKeywords.isNotEmpty() && parsedAlarmTime != null) {
                                onSave(
                                    ShiftDefinition(
                                        id = shift?.id ?: UUID.randomUUID().toString(),
                                        name = name.trim(),
                                        keywords = validKeywords,
                                        alarmTime = parsedAlarmTime,
                                        isEnabled = isEnabled,
                                        isSilent = isSilent
                                    )
                                )
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && 
                                 keywords.any { it.isNotBlank() } && 
                                 try {
                                     LocalTime.parse(alarmTimeString, timeFormatter)
                                     true
                                 } catch (_: Exception) {
                                     false
                                 }
                    ) {
                        Text(if (isNewShift) "Erstellen" else "Speichern")
                    }
                }
            }
        }
    }
}
