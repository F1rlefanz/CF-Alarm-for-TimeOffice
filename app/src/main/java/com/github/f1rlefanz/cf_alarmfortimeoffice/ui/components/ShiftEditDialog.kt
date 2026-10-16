package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.istZeichenmuster
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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    var isOnCall by remember { mutableStateOf(shift?.isOnCall ?: false) }
    var isSickDay by remember { mutableStateOf(shift?.isSickDay ?: false) }

    /**
     * Wird hier gerade eine BESTEHENDE Schicht umbenannt? Massstab ist der Vergleich, den die
     * Regelsuche selbst anlegt (`equals(ignoreCase = true)`) - eine reine Schreibweisenaenderung
     * bricht dort nichts und braucht deshalb auch keinen Hinweis. Getrimmt, weil genau das
     * gespeichert wird.
     */
    val istUmbenennung = shift != null &&
        name.trim().isNotBlank() &&
        !name.trim().equals(shift.name, ignoreCase = true)

    val timeFormatter = remember { DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY) }
    val parsedAlarmTime = remember(alarmTimeString) {
        runCatching { LocalTime.parse(alarmTimeString, timeFormatter) }.getOrNull()
    }
    
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
                        fontWeight = FontWeight.Bold,
                        // weight(1f): sonst lief der Titel bei 320 dp + Schrift 1,3 bis unter das X
                        modifier = Modifier.weight(1f)
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
                            isError = name.isBlank(),
                            // Keine unbedingte Zusage: planeSchichtUmbenennungen() kann den Nachzug
                            // blockieren, das Ergebnis meldet regelNachzugHinweis - Hergang schichterkennung.md.
                            supportingText = if (istUmbenennung) {
                                {
                                    Text(
                                        text = "Dimmer- und Hue-Regeln, die auf \"${shift.name}\" " +
                                            "zeigen, werden beim Speichern nach Möglichkeit auf " +
                                            "den neuen Namen umgestellt. Klappt das nicht – etwa " +
                                            "weil der neue Name schon vergeben ist –, sagt die " +
                                            "App es dir; dann wählst du die Schicht in der " +
                                            "betroffenen Regel neu aus.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                null
                            }
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
                                "eigenes Wort vorkommt (\"FD\" trifft \"FD Station 3\", nicht " +
                                "\"FD2\"). Der Schichtname oben zählt ab zwei Zeichen " +
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
                                    // Warnen statt verbieten: einbuchstabige Muster treffen
                                    // fremde Termine - schichterkennung.md. #142: bei einer
                                    // Krankmeldung ist die Folge die UMGEKEHRTE (ein fehlender
                                    // Wecker), der Text folgt dem Schalter.
                                    supportingText = ShiftDefinitionTexte.einBuchstabeWarnung(keyword, isSickDay)?.let { warnung ->
                                        {
                                            Text(
                                                text = warnung,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
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

                    // #142: direkt unter den Mustern, weil er entscheidet, ob die Felder darunter
                    // (Weckzeit, Stille Schicht, Rufbereitschaft) ueberhaupt etwas bedeuten.
                    item {
                        SwitchRow(
                            title = ShiftDefinitionTexte.KRANKMELDUNG_TITEL,
                            description = ShiftDefinitionTexte.KRANKMELDUNG_HINWEIS,
                            checked = isSickDay,
                            onCheckedChange = { isSickDay = it },
                            titleFontWeight = FontWeight.Normal
                        )
                    }

                    // #142: ein Krankmeldungs-Typ stellt nie einen Wecker - Weckzeit, Stille und
                    // Rufbereitschaft waeren Einstellungen ohne Wirkung und bleiben verborgen.
                    if (!isSickDay) item {
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
                                isError = parsedAlarmTime == null
                            )
                            
                            Text(
                                text = "Format: 06:30",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // SwitchRow statt drei handgebauter Zeilen (#132, G2-05): weight(1f) am Text und
                    // 12 dp Abstand - vorher stiess der Text am Schalter an (Bildvergleich 01.10.2026).
                    if (!isSickDay) item {
                        SwitchRow(
                            title = "Stille Schicht",
                            description = "Kein Ton/Vibration/Vollbild-Wecker - die Zeit bleibt als Anker für Dimmer/DND erhalten",
                            checked = isSilent,
                            onCheckedChange = { isSilent = it },
                            titleFontWeight = FontWeight.Normal
                        )
                    }

                    if (!isSickDay) item {
                        // Der Text erklaert das WARUM, nicht nur das Was: welches Kuerzel eine
                        // Rufbereitschaft ist, weiss nur der Nutzer seiner Station - die App
                        // kann es nicht erraten, und ohne die Begruendung ("kein Stups vom
                        // Kalender") wirkt der stuendliche Abruf wie Batterieverschwendung.
                        SwitchRow(
                            title = "Rufbereitschaft",
                            description = ShiftDefinitionTexte.RUFBEREITSCHAFT_HINWEIS,
                            checked = isOnCall,
                            onCheckedChange = { isOnCall = it },
                            titleFontWeight = FontWeight.Normal
                        )
                    }

                    item {
                        // Der Schalter daneben hat eine Erklaerung, dieser hatte keine - und
                        // genau daran ist am 19.08.2026 eine Rufbereitschaft gescheitert: sie
                        // wurde AUSGESCHALTET angelegt, damit sie nicht klingelt, sollte aber
                        // weiterhin "Nicht stoeren" steuern. Ausschalten beendet jedoch die
                        // ERKENNUNG, und ohne erkannte Schicht gibt es keine Schichtspanne -
                        // also auch kein DND- und kein Dimmer-Fenster. Wer "kein Wecker, aber
                        // Zeitfenster" will, braucht "Stille Schicht" daruber.
                        SwitchRow(
                            title = "Schichtdefinition aktiviert",
                            // #142: der Stille-Schicht-Verweis passt nicht auf eine Krankmeldung -
                            // dort kostet "aus" die Tagesfreigabe, nicht ein Zeitfenster.
                            description = if (isSickDay) {
                                ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS
                            } else {
                                "Aus heißt: wird gar nicht erkannt — kein Wecker, aber " +
                                    "auch kein Dimmer- und kein DND-Fenster. Für „kein Wecker, " +
                                    "Zeitfenster trotzdem“ ist „Stille Schicht“ der richtige Schalter."
                            },
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it },
                            titleFontWeight = FontWeight.Normal
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                
                // Actions - KnopfZeile + CompactButton statt Row + weight(1f) + Button: bei 320 dp
                // mit Schrift 1,3 blieben im rechten Knopf 68 dp fuer "Speichern" (Wortbruch).
                KnopfZeile(
                    modifier = Modifier.fillMaxWidth(),
                    abstand = SpacingConstants.SPACING_SMALL
                ) {
                    CompactOutlinedButton(
                        onClick = onDismiss,
                        text = "Abbrechen"
                    )
                    
                    CompactButton(
                        text = if (isNewShift) "Erstellen" else "Speichern",
                        onClick = {
                            // Trimmen ist Pflicht (Wortgrenzen-Regex), distinct gegen Doppel - schichterkennung.md.
                            val validKeywords = keywords
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                            // #142: bei Krankmeldung ist die Weckzeit formal (Serialisierung), darf also
                            // nicht blockieren; Stille/Rufbereitschaft waeren Aussagen ueber einen
                            // Wecker, den es nie gibt.
                            val zeit = if (isSickDay) {
                                parsedAlarmTime ?: shift?.alarmTime ?: LocalTime.MIDNIGHT
                            } else {
                                parsedAlarmTime
                            }

                            if (name.isNotBlank() && validKeywords.isNotEmpty() && zeit != null) {
                                onSave(
                                    ShiftDefinition(
                                        id = shift?.id ?: UUID.randomUUID().toString(),
                                        name = name.trim(),
                                        keywords = validKeywords,
                                        alarmTime = zeit,
                                        isEnabled = isEnabled,
                                        isSilent = isSilent && !isSickDay,
                                        isOnCall = isOnCall && !isSickDay,
                                        isSickDay = isSickDay
                                    )
                                )
                                onDismiss()
                            }
                        },
                        // #142: das verborgene Zeitfeld darf das Speichern nicht grundlos sperren.
                        enabled = name.isNotBlank() &&
                                 keywords.any { it.isNotBlank() } &&
                                 (isSickDay || parsedAlarmTime != null)
                    )
                }
            }
        }
    }
}

/**
 * Nutzertexte des Schicht-Editors und der Schicht-Karten - als Konstanten, damit ein Test
 * festhalten kann, dass jeder Text nur die Wirkungen nennt, die der Schalter WIRKLICH hat (beim
 * Rufbereitschaft-Schalter: stuendliche Kalender-Abfrage, DND-Cutoff), und nichts verspricht,
 * was es nicht gibt.
 */
object ShiftDefinitionTexte {
    // #142: Krankmeldung. "Eigene Wecker bleiben" ist tragend - die Freigabe nimmt manuelle Wecker
    // aus; ein Text ohne diesen Satz liesse fuerchten, dass auch sie entfallen.
    const val KRANKMELDUNG_TITEL = "Krankmeldung"
    const val KRANKMELDUNG_HINWEIS =
        "Ein Termin dieses Typs gibt den ganzen Tag frei: kein Wecker aus dem Dienstplan, " +
            "nachts gilt die Regel für freie Tage. Eigene Wecker bleiben."
    const val KRANKMELDUNG_AKTIVIERT_HINWEIS =
        "Aus heißt: wird nicht erkannt – ein Termin dieses Typs gibt dann keinen Tag frei."
    const val KRANKMELDUNG_STATUS = "Krankmeldung – gibt den Tag frei"
    const val KRANKMELDUNG_AUSGESCHALTET = "Ausgeschaltet – wird nicht erkannt, gibt keinen Tag frei"

    /** Einbuchstabiges Muster bei einer Schicht: ein fremder Termin erzeugt einen Wecker zu viel. */
    const val EIN_BUCHSTABE_SCHICHT =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Kino mit F\") und weckt " +
            "dich dann an freien Tagen."

    /**
     * #142: dasselbe bei einer Krankmeldung - hier fehlt der Wecker. "K" ist der TimeOffice-Code
     * und damit der Hauptfall; ein fremder Termin mit "K" als eigenem Wort gibt einen DIENSTTAG
     * frei. Die Schicht-Warnung ("weckt dich an freien Tagen") nennte das Gegenteil des Risikos.
     */
    const val EIN_BUCHSTABE_KRANK =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Termin bei Dr. K\") und " +
            "gibt dann einen Diensttag frei – kein Wecker aus dem Dienstplan."

    /**
     * PURE, TESTBAR: die Warnung unter einem einbuchstabigen Muster - oder null. Ein einzelnes
     * ZEICHEN ("+") bekommt keine: es trifft seit #150 nur den ganzen Titel, nie fremde Termine.
     */
    fun einBuchstabeWarnung(muster: String, isSickDay: Boolean): String? {
        val m = muster.trim()
        if (m.length != 1 || istZeichenmuster(m)) return null
        return if (isSickDay) EIN_BUCHSTABE_KRANK else EIN_BUCHSTABE_SCHICHT
    }

    /** #142: Loesch-Rueckfrage fuer einen Krankmeldungs-Typ - er hat keine Weckzeit, die entfiele,
     * wohl aber Tage, die er freigegeben hat und die ihre Wecker zurueckbekommen. */
    fun krankmeldungLoeschenText(name: String): String =
        "\"$name\" wird mit allen Mustern gelöscht. Tage, die ein solcher Termin freigegeben " +
            "hat, bekommen ihre Wecker aus dem Dienstplan zurück. Das lässt sich nicht " +
            "rückgängig machen."

    const val RUFBEREITSCHAFT_HINWEIS =
        "An Tagen mit dieser Schicht kannst du kurzfristig zu einem Dienst abgerufen werden. " +
            "CF-Alarm fragt den Kalender dann stündlich ab statt alle 6 Stunden, damit ein " +
            "nachgetragener Dienst noch rechtzeitig einen Wecker bekommt – Google Kalender und " +
            "TimeOffice melden Änderungen nicht von selbst. Außerdem endet „Nicht stören“ an " +
            "diesen Tagen zur Rufbereitschafts-Uhrzeit (einstellbar unter „Nicht stören“)."
}
