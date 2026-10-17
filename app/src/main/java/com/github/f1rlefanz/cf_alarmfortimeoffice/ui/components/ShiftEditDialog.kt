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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Der Schicht-Editor. [shift] ist der BESTEHENDE Typ, den er bearbeitet (`null` = Neuanlage).
 * [vorlage] ist ein noch NICHT gespeicherter, vorbelegter Typ (Umbau: aus „Im Kalender gefunden“,
 * `SchichttypEinordnen.neuerTypAusCode`): der Editor zeigt seine Werte, meldet sich aber als
 * Neuanlage - `onSave` bekommt ihn mit seiner eigenen `id`, und der Aufrufer haengt ihn an.
 */
@Composable
fun ShiftEditDialog(
    shift: ShiftDefinition?,
    onDismiss: () -> Unit,
    onSave: (ShiftDefinition) -> Unit,
    vorlage: ShiftDefinition? = null
) {
    val isNewShift = shift == null
    val ausgang = shift ?: vorlage

    var name by remember { mutableStateOf(ausgang?.name ?: "") }
    // Ein Typ ohne Muster (frisch angelegter Ignorieren-Typ, #169) bekommt ein leeres Feld statt
    // einer leeren Liste - sonst gaebe es nichts zum Eintippen und das Speichern bliebe gesperrt.
    var keywords by remember { mutableStateOf(ausgang?.keywords?.ifEmpty { listOf("") } ?: listOf("")) }
    // TIME_ONLY (ANZEIGE), NICHT PERSIST_TIME: diese beiden Stellen zeigen die vom Nutzer
    // eingetippte Weckzeit an und parsen sie zurueck. Persistiert wird sie danach ueber
    // LocalTimeSerializer, der bewusst PERSIST_TIME benutzt. Die Formate sind entkoppelt - wer
    // sie spaeter zusammenlegt, koppelt die Eingabe-Interpretation an ein Persistenzformat.
    var alarmTimeString by remember {
        mutableStateOf(ausgang?.alarmTime?.format(DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY))
            ?: String.format(Locale.ROOT, "%02d:%02d", AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE))
    }
    var isEnabled by remember { mutableStateOf(ausgang?.isEnabled ?: true) }
    var isSilent by remember { mutableStateOf(ausgang?.isSilent ?: false) }
    var weckerAn by remember { mutableStateOf(ausgang?.weckerAn ?: true) }
    // Die Kategorie ersetzt die Schalter „Krankmeldung“ und „Rufbereitschaft“ (Umbau E1). Beim
    // Speichern setzt `mitKategorie` beide Flags mit - die lesen alle heutigen Verbraucher.
    var kategorie by remember { mutableStateOf(ausgang?.kategorie ?: ShiftKategorie.DIENST) }
    val isSickDay = kategorie == ShiftKategorie.KRANK

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
                                    supportingText = ShiftDefinitionTexte.einBuchstabeWarnung(keyword, kategorie, weckerAn)?.let { warnung ->
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

                    // Umbau E1: die Kategorie steht direkt unter den Mustern, weil sie entscheidet,
                    // was darunter etwas bedeutet (#142: Krank stellt nie einen Wecker). Sieben
                    // Zeilen mit Kurzbeschreibung statt der Schalter „Krankmeldung“ und
                    // „Rufbereitschaft“; ein Wechsel behaelt alle Einstellungen (E2).
                    item {
                        Text(
                            text = ShiftDefinitionTexte.KATEGORIE_TITEL,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = ShiftDefinitionTexte.KATEGORIE_HINWEIS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                            ShiftKategorie.entries.forEach { option ->
                                KategorieZeile(
                                    option = option,
                                    gewaehlt = kategorie == option,
                                    onWahl = { kategorie = option }
                                )
                                // Der Text erklaert das WARUM, nicht nur das Was: welches Kuerzel eine
                                // Rufbereitschaft ist, weiss nur der Nutzer seiner Station - die App
                                // kann es nicht erraten, und ohne die Begruendung ("kein Stups vom
                                // Kalender") wirkt der stuendliche Abruf wie Batterieverschwendung.
                                if (kategorie == option && option == ShiftKategorie.RUFBEREITSCHAFT) {
                                    Text(
                                        text = ShiftDefinitionTexte.RUFBEREITSCHAFT_HINWEIS,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = KATEGORIE_TEXT_EINZUG,
                                            bottom = SpacingConstants.SPACING_SMALL
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (ShiftEditFormular.zeigtWeckerBlock(kategorie)) {
                        item {
                            Text(
                                text = ShiftDefinitionTexte.WECKER_ABSCHNITT_TITEL,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        item {
                            SwitchRow(
                                title = ShiftDefinitionTexte.WECKER_AN_TITEL,
                                description = if (weckerAn) {
                                    ShiftDefinitionTexte.WECKER_AN_HINWEIS
                                } else {
                                    ShiftDefinitionTexte.WECKER_AUS_HINWEIS
                                },
                                checked = weckerAn,
                                onCheckedChange = { weckerAn = it },
                                titleFontWeight = FontWeight.Normal
                            )
                        }

                        // Weckzeit und „Stummer Wecker“ wirken nur bei eingeschaltetem Wecker: sie
                        // bleiben sichtbar, aber ausgegraut (wie im Prototyp), ihre Werte bleiben stehen.
                        item {
                            Text(
                                text = ShiftDefinitionTexte.WECKZEIT_TITEL,
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
                                    enabled = weckerAn,
                                    isError = weckerAn && parsedAlarmTime == null
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
                        item {
                            Column(modifier = Modifier.alpha(if (weckerAn) 1f else AUSGEGRAUT_ALPHA)) {
                                SwitchRow(
                                    title = ShiftDefinitionTexte.STUMMER_WECKER_TITEL,
                                    description = ShiftDefinitionTexte.STUMMER_WECKER_HINWEIS,
                                    checked = isSilent,
                                    onCheckedChange = { isSilent = it },
                                    enabled = weckerAn,
                                    titleFontWeight = FontWeight.Normal
                                )
                            }
                        }
                    } else {
                        // #142/#167: statt der Weckzeit der Hinweis, was dieser Typ tatsaechlich tut -
                        // ein Feld ohne Wirkung wuerde einen Wecker zeigen, den es nie gibt.
                        item {
                            Text(
                                text = if (isSickDay) {
                                    ShiftDefinitionTexte.KRANKMELDUNG_HINWEIS
                                } else {
                                    ShiftDefinitionTexte.IGNORIEREN_STATT_WECKER
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    item {
                        // Der Schalter daneben hat eine Erklaerung, dieser hatte keine - und
                        // genau daran ist am 19.08.2026 eine Rufbereitschaft gescheitert: sie
                        // wurde AUSGESCHALTET angelegt, damit sie nicht klingelt, sollte aber
                        // weiterhin "Nicht stoeren" steuern. Ausschalten beendet jedoch die
                        // ERKENNUNG, und ohne erkannte Schicht gibt es keine Schichtspanne -
                        // also auch kein DND- und kein Dimmer-Fenster. Wer "kein Wecker, aber
                        // Zeitfenster" will, schaltet „Wecker an diesem Tag“ aus (#168).
                        SwitchRow(
                            title = "Schichtdefinition aktiviert",
                            // #142: der Wecker-aus-Verweis passt nicht auf eine Krankmeldung -
                            // dort kostet "aus" die Tagesfreigabe, nicht ein Zeitfenster.
                            description = when {
                                isSickDay -> ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS
                                // Ignorieren ergibt nie einen Wecker, und der Schalter „Wecker an diesem
                                // Tag“ ist dort ausgeblendet - der allgemeine Hinweis verwiese ins Leere.
                                kategorie == ShiftKategorie.IGNORIEREN ->
                                    ShiftDefinitionTexte.IGNORIEREN_AKTIVIERT_HINWEIS
                                else -> ShiftDefinitionTexte.AKTIVIERT_HINWEIS
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
                            val validKeywords = ShiftEditFormular.bereinigteMuster(keywords)
                            // #142: bei Krankmeldung ist die Weckzeit formal (Serialisierung), darf also
                            // nicht blockieren - ebenso bei Wecker aus und Ignorieren (E2: der Wert bleibt).
                            val zeit = ShiftEditFormular.zeitZumSpeichern(kategorie, weckerAn, parsedAlarmTime, ausgang)

                            if (name.isNotBlank() && validKeywords.isNotEmpty() && zeit != null) {
                                onSave(
                                    ShiftEditFormular.speichern(
                                        ausgang = ausgang,
                                        name = name,
                                        muster = validKeywords,
                                        alarmTime = zeit,
                                        isEnabled = isEnabled,
                                        weckerAn = weckerAn,
                                        isSilent = isSilent,
                                        kategorie = kategorie
                                    )
                                )
                                onDismiss()
                            }
                        },
                        // #142: das verborgene Zeitfeld darf das Speichern nicht grundlos sperren.
                        enabled = name.isNotBlank() &&
                                 keywords.any { it.isNotBlank() } &&
                                 (!ShiftEditFormular.weckzeitPflicht(kategorie, weckerAn) || parsedAlarmTime != null)
                    )
                }
            }
        }
    }
}

/** Einzug der Zeilentexte unter dem Radio-Knopf: 24 dp Knopf + 8 dp Abstand, wie in [KategorieZeile]. */
private val KATEGORIE_TEXT_EINZUG = 32.dp

/** Deckkraft einer ausgegrauten Zeile: unter Material-Standard (0,38), damit Text lesbar bleibt. */
private const val AUSGEGRAUT_ALPHA = 0.6f

/**
 * Eine Zeile der Kategorie-Wahl: Radio-Knopf, Name ([titel]) und Kurzbeschreibung. Mindestens
 * 48 dp hoch (`RadioButton` mit `onClick = null` bringt das Touchziel nicht selbst mit).
 */
@Composable
private fun KategorieZeile(
    option: ShiftKategorie,
    gewaehlt: Boolean,
    onWahl: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = gewaehlt, onClick = onWahl, role = Role.RadioButton)
            .padding(vertical = SpacingConstants.SPACING_SMALL),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = gewaehlt, onClick = null)
        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        // weight(1f): sonst laeuft die Beschreibung bei 320 dp + Schrift 1,3 unter den Knopf
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.titel(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ShiftDefinitionTexte.kategorieBeschreibung(option),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

    /**
     * Dieselbe Warnung, wo KEIN Wecker gestellt wird (Ignorieren, Wecker aus): "weckt dich an freien
     * Tagen" waere dort falsch (Pruefer #174). Gesagt wird nur, was bleibt: fremde Termine landen
     * beim Typ.
     */
    const val EIN_BUCHSTABE_OHNE_WECKER =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Kino mit F\") und ordnet " +
            "sie diesem Typ zu."

    /**
     * Wie [einBuchstabeWarnung], aber nach Kategorie und Wecker-Schalter: Krank behaelt seinen Text,
     * Ignorieren und "Wecker aus" bekommen [EIN_BUCHSTABE_OHNE_WECKER], nur ein Typ, der wirklich
     * einen Wecker stellt, die Schicht-Warnung.
     */
    fun einBuchstabeWarnung(muster: String, kategorie: ShiftKategorie, weckerAn: Boolean): String? {
        val warnung = einBuchstabeWarnung(muster, kategorie == ShiftKategorie.KRANK) ?: return null
        val stelltWecker = ShiftEditFormular.weckzeitPflicht(kategorie, weckerAn)
        return if (kategorie == ShiftKategorie.KRANK || stelltWecker) warnung else EIN_BUCHSTABE_OHNE_WECKER
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

    // Umbau E1: die Kurzbeschreibungen der Kategorien (Prototyp, `KAT` in Main.dc.html). Ohne `else`:
    // eine achte Kategorie muss hier bewusst beschrieben werden. Der Name der Kategorie kommt aus
    // `ShiftKategorie.titel()`. Abweichung vom Prototyp: bei Urlaub fehlt „und Dimmer“ - der Dimmer
    // haengt bis Etappe 3 an Regeln, nicht am Typ, und „Wecker aus“ nimmt ihn nicht weg.
    fun kategorieBeschreibung(kategorie: ShiftKategorie): String = when (kategorie) {
        ShiftKategorie.DIENST -> "Arbeitstage nach Dienstplan."
        ShiftKategorie.RUFBEREITSCHAFT ->
            "Bereitschaftsdienst. Wird ein Einsatz als Dienst nachgetragen, gilt dessen Wecker."
        ShiftKategorie.FREI -> "Dienstfreie Tage, z. B. „+“."
        ShiftKategorie.URLAUB -> "Urlaubstage – auf Wunsch ganz ohne Wecker."
        ShiftKategorie.FORTBILDUNG -> "Intern oder extern, Kongress, Uni."
        ShiftKategorie.KRANK -> "Hebt einen Dienst am selben Tag auf."
        ShiftKategorie.IGNORIEREN -> "Steht im Kalender, ist für die App bedeutungslos."
    }

    const val KATEGORIE_TITEL = "Kategorie"
    const val KATEGORIE_HINWEIS =
        "Die Kategorie sortiert nur. Wecker, Weckzeit und alles Weitere behältst du, wenn du sie wechselst."

    // Umbau E2: der Schalter heisst im Prototyp (BILDSCHIRME.md B3) „Wecker an diesem Tag“. Die
    // Hinweise sagen nur, was der Typ TUT: einen Wecker stellen oder nicht. Ob er klingelt, regelt
    // „Stummer Wecker“ darunter.
    const val WECKER_ABSCHNITT_TITEL = "Wecker"
    const val WECKER_AN_TITEL = "Wecker an diesem Tag"
    const val WECKER_AN_HINWEIS = "Stellt an jedem Tag dieses Typs einen Wecker."
    const val WECKER_AUS_HINWEIS = "Aus – an diesen Tagen stellt dieser Typ keinen Wecker."
    const val WECKZEIT_TITEL = "Weckzeit"

    // „Stummer Wecker“ (B3). Der Prototyp verspricht hier auch „Licht … richtet sich trotzdem nach
    // der Weckzeit – ein Lichtwecker“. Heute stimmt das NICHT: der AlarmReceiver und der
    // HueSmartScheduler ueberspringen bei einer stillen Schicht jede Hue-Regel. Der Satz kommt mit
    // Etappe 4 (E8), wenn Hue stillen Weckern folgt.
    const val STUMMER_WECKER_TITEL = "Stummer Wecker"
    const val STUMMER_WECKER_HINWEIS =
        "Kein Ton, keine Vibration, kein Vollbild. Dimmer und „Nicht stören“ richten sich " +
            "trotzdem nach der Weckzeit. Eine Hue-Lichtregel startet dann nicht."

    /** Ein Ignorieren-Typ ergibt keine Schicht (#167); ein Dienst im selben Termin gewinnt. */
    const val IGNORIEREN_STATT_WECKER =
        "Dieser Eintrag wird übergangen. Er stellt keinen Wecker; steht im selben Termin ein " +
            "Dienst-Code, gilt der Dienst."

    /** Wie `SchichttypTexte.IGNORIEREN_AUSGESCHALTET` der Typ-Liste: aus heisst „wird nicht mehr uebergangen“. */
    const val IGNORIEREN_AKTIVIERT_HINWEIS =
        "Aus heißt: wird nicht mehr übergangen – ein Termin mit diesem Muster zählt dann wie jeder andere Termin."

    // Der Schalter „Schichtdefinition aktiviert“ verwies auf „Stille Schicht“ - die heisst jetzt
    // „Stummer Wecker“, und fuer „kein Wecker, Zeitfenster trotzdem“ ist seit #168 „Wecker an diesem
    // Tag“ aus der richtige Schalter (der Typ wird erkannt, die Spanne bleibt).
    const val AKTIVIERT_HINWEIS =
        "Aus heißt: wird gar nicht erkannt — kein Wecker, aber auch kein Dimmer- und kein " +
            "DND-Fenster. Für „kein Wecker, Zeitfenster trotzdem“ schaltest du „$WECKER_AN_TITEL“ aus."
}

/**
 * Was der Schicht-Editor beim Speichern aus seinem Formular macht - ohne Compose, damit ein Test
 * es festhalten kann (Umbau Etappe 1, #174).
 *
 * Der Editor geht immer vom BESTEHENDEN Typ aus ([speichern]: `copy` statt neuem Konstruktoraufruf).
 * Ein neu gebautes `ShiftDefinition(...)` liesse jedes Feld, das der Editor nicht kennt (Kategorie
 * Frei/Urlaub, Wecker aus, spaeter Dimm-Fenster und DND), still auf seinen Default fallen - ein Typ
 * "Urlaub, Wecker aus" aus einem Import wuerde beim Umbenennen zu "Dienst, Wecker an".
 */
internal object ShiftEditFormular {

    /** Getrimmt (Pflicht fuer die Wortgrenzen-Regex), ohne Leere, ohne Doppelte. */
    fun bereinigteMuster(muster: List<String>): List<String> =
        muster.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Zeigt der Editor Wecker, Weckzeit und stummen Wecker? Nein bei Krank (gibt den Tag frei, #142)
     * und Ignorieren (ergibt keine Schicht, #167): dort wuerde jedes dieser Felder einen Ablauf
     * zeigen, den es nicht gibt. Die Werte bleiben im Formular und im Typ stehen (E2).
     */
    fun zeigtWeckerBlock(kategorie: ShiftKategorie): Boolean =
        kategorie != ShiftKategorie.KRANK && kategorie != ShiftKategorie.IGNORIEREN

    /** Muss die Weckzeit gueltig sein, damit gespeichert werden darf? Nur wenn sie etwas bewirkt. */
    fun weckzeitPflicht(kategorie: ShiftKategorie, weckerAn: Boolean): Boolean =
        zeigtWeckerBlock(kategorie) && weckerAn

    /**
     * Die Weckzeit, die gespeichert wird - oder `null`, wenn das Formular nicht speicherbar ist.
     * Wirkt sie nicht (Wecker aus, Krank, Ignorieren), sperrt ein kaputter Eintrag im verborgenen
     * oder ausgegrauten Feld das Speichern nicht: es bleibt die bisherige Zeit, sonst die Vorgabe
     * (06:00, nicht 00:00 - schaltet der Nutzer den Wecker spaeter ein, soll er nicht um Mitternacht klingeln).
     */
    fun zeitZumSpeichern(
        kategorie: ShiftKategorie,
        weckerAn: Boolean,
        eingegeben: LocalTime?,
        ausgang: ShiftDefinition?
    ): LocalTime? =
        if (weckzeitPflicht(kategorie, weckerAn)) {
            eingegeben
        } else {
            eingegeben ?: ausgang?.alarmTime
                ?: LocalTime.of(AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE)
        }

    /**
     * Der Typ, den der Editor speichert: [ausgang] (der bearbeitete oder der vorbelegte, noch nicht
     * gespeicherte Typ) mit den Formularwerten, oder bei `null` ein frischer Typ. Die Kategorie
     * kommt ueber [ShiftDefinition.mitKategorie] - sie setzt `isSickDay` und `isOnCall` mit, die
     * alle heutigen Leser (Erkennung, Ruf-Abfrage, DND) lesen.
     */
    fun speichern(
        ausgang: ShiftDefinition?,
        name: String,
        muster: List<String>,
        alarmTime: LocalTime,
        isEnabled: Boolean,
        weckerAn: Boolean,
        isSilent: Boolean,
        kategorie: ShiftKategorie,
        neueId: () -> String = { UUID.randomUUID().toString() }
    ): ShiftDefinition =
        (ausgang ?: ShiftDefinition(id = neueId(), name = name, keywords = muster, alarmTime = alarmTime))
            .copy(
                name = name.trim(),
                keywords = muster,
                alarmTime = alarmTime,
                isEnabled = isEnabled,
                isSilent = isSilent,
                weckerAn = weckerAn
            )
            .mitKategorie(kategorie)
}
