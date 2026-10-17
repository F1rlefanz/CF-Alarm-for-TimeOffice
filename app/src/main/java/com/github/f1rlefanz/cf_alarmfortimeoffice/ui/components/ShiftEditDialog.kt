package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.istZeichenmuster
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.KategorieMarke
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.SonderfallMarke
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
 * Der Formularstand des Typ-Editors als EIN Wert (Umbau #187). Vorher sieben einzelne
 * `remember`-Zustaende im Dialog; als Wert teilen ihn der Dialog (Neuanlage) und die Typ-Seite
 * (Bearbeiten), und die Seite kann ihn als Entwurf ueber Drehen, Dunkelmodus und Schriftgroesse
 * retten ([alsSpeicherListe]). Die Weckzeit bleibt der eingetippte TEXT - ein halb getipptes
 * "06:" ist ein gueltiger Zwischenstand, kein Fehler.
 */
internal data class TypEntwurf(
    val name: String,
    val muster: List<String>,
    val weckzeitText: String,
    val isEnabled: Boolean,
    val isSilent: Boolean,
    val weckerAn: Boolean,
    val kategorie: ShiftKategorie
) {
    val isSickDay: Boolean get() = kategorie == ShiftKategorie.KRANK

    /** Die eingetippte Weckzeit - `null`, solange sie kein gueltiges HH:mm ist. */
    val weckzeit: LocalTime? get() = runCatching { LocalTime.parse(weckzeitText, ZEIT_ANZEIGE) }.getOrNull()

    /**
     * Wird hier gerade ein BESTEHENDER Typ ([bestehenderName], `null` = Neuanlage) umbenannt?
     * Massstab ist der Vergleich, den die Regelsuche selbst anlegt (`equals(ignoreCase = true)`) -
     * eine reine Schreibweisenaenderung bricht dort nichts und braucht deshalb keinen Hinweis.
     * Getrimmt, weil genau das gespeichert wird.
     */
    fun istUmbenennungVon(bestehenderName: String?): Boolean =
        bestehenderName != null &&
            name.trim().isNotBlank() &&
            !name.trim().equals(bestehenderName, ignoreCase = true)

    /** Darf gespeichert werden? Die Weckzeit sperrt nur, wenn sie etwas bewirkt (#142). */
    val speicherbar: Boolean
        get() = name.isNotBlank() &&
            muster.any { it.isNotBlank() } &&
            (!ShiftEditFormular.weckzeitPflicht(kategorie, weckerAn) || weckzeit != null)

    /**
     * Der Typ, der gespeichert wird: [ausgang] (der bearbeitete oder der vorbelegte Typ) mit den
     * Formularwerten, oder `null`, wenn das Formular nicht speicherbar ist. Trimmen ist Pflicht
     * (Wortgrenzen-Regex), distinct gegen Doppel - schichterkennung.md.
     */
    fun alsDefinition(ausgang: ShiftDefinition?): ShiftDefinition? {
        val bereinigt = ShiftEditFormular.bereinigteMuster(muster)
        // #142: bei Krankmeldung ist die Weckzeit formal (Serialisierung), darf also nicht
        // blockieren - ebenso bei Wecker aus und Ignorieren (E2: der Wert bleibt).
        val zeit = ShiftEditFormular.zeitZumSpeichern(kategorie, weckerAn, weckzeit, ausgang)
        if (name.isBlank() || bereinigt.isEmpty() || zeit == null) return null
        return ShiftEditFormular.speichern(
            ausgang = ausgang,
            name = name,
            muster = bereinigt,
            alarmTime = zeit,
            isEnabled = isEnabled,
            weckerAn = weckerAn,
            isSilent = isSilent,
            kategorie = kategorie
        )
    }

    /** Fuer `rememberSaveable`: nur Typen, die ein Bundle kennt (Text, Wahrheitswert, Liste). */
    fun alsSpeicherListe(): List<Any> =
        listOf(name, ArrayList(muster), weckzeitText, isEnabled, isSilent, weckerAn, kategorie.schluessel)

    companion object {
        /** Der Ausgangsstand des Formulars: der Typ, wie er ist, oder die Vorgaben einer Neuanlage. */
        fun aus(ausgang: ShiftDefinition?): TypEntwurf = TypEntwurf(
            name = ausgang?.name ?: "",
            // Ein Typ ohne Muster (frisch angelegter Ignorieren-Typ, #169) bekommt ein leeres Feld
            // statt einer leeren Liste - sonst gaebe es nichts zum Eintippen und das Speichern
            // bliebe gesperrt.
            muster = ausgang?.keywords?.ifEmpty { listOf("") } ?: listOf(""),
            // TIME_ONLY (ANZEIGE), NICHT PERSIST_TIME: diese beiden Stellen zeigen die vom Nutzer
            // eingetippte Weckzeit an und parsen sie zurueck. Persistiert wird sie danach ueber
            // LocalTimeSerializer, der bewusst PERSIST_TIME benutzt. Die Formate sind entkoppelt -
            // wer sie spaeter zusammenlegt, koppelt die Eingabe-Interpretation an ein
            // Persistenzformat.
            weckzeitText = ausgang?.alarmTime?.format(ZEIT_ANZEIGE)
                ?: String.format(Locale.ROOT, "%02d:%02d", AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE),
            isEnabled = ausgang?.isEnabled ?: true,
            isSilent = ausgang?.isSilent ?: false,
            weckerAn = ausgang?.weckerAn ?: true,
            // Die Kategorie ersetzt die Schalter „Krankmeldung“ und „Rufbereitschaft“ (Umbau E1).
            // Beim Speichern setzt `mitKategorie` beide Flags mit - die lesen alle heutigen Verbraucher.
            kategorie = ausgang?.kategorie ?: ShiftKategorie.DIENST
        )

        /** Gegenstueck zu [alsSpeicherListe]; `null` bei einem Stand, den es nicht lesen kann. */
        fun ausSpeicherListe(liste: List<Any?>): TypEntwurf? = runCatching {
            TypEntwurf(
                name = liste[0] as String,
                muster = (liste[1] as List<*>).map { it as String },
                weckzeitText = liste[2] as String,
                isEnabled = liste[3] as Boolean,
                isSilent = liste[4] as Boolean,
                weckerAn = liste[5] as Boolean,
                kategorie = ShiftKategorie.vonSchluessel(liste[6] as String) ?: ShiftKategorie.DIENST
            )
        }.getOrNull()
    }
}

private val ZEIT_ANZEIGE: DateTimeFormatter = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)

/**
 * Der Schicht-Editor als Dialog - seit Umbau #187 nur noch fuer die NEUANLAGE; einen bestehenden
 * Typ bearbeitet die Typ-Seite. [vorlage] ist ein noch NICHT gespeicherter, vorbelegter Typ (Umbau:
 * aus „Im Kalender gefunden“, `SchichttypEinordnen.neuerTypAusCode`): der Editor zeigt seine
 * Werte, meldet sich aber als Neuanlage - `onSave` bekommt ihn mit seiner eigenen `id`, und der
 * Aufrufer haengt ihn an. Das Formular selbst ist [typFormularFelder]; die Typ-Seite teilt davon nur
 * [typWeckerFelder] und [AktiviertSchalter] - Name, Muster und Kategorie zeigt sie als Karte „Typ“
 * (`TypAbschnitt`, Umbau #188).
 */
@Composable
fun ShiftEditDialog(
    onDismiss: () -> Unit,
    onSave: (ShiftDefinition) -> Unit,
    vorlage: ShiftDefinition? = null
) {
    var entwurf by remember { mutableStateOf(TypEntwurf.aus(vorlage)) }

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
                        text = "Neue Schichtdefinition",
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
                    typFormularFelder(
                        entwurf = entwurf,
                        bestehenderName = null,
                        aendere = { aenderung -> entwurf = aenderung(entwurf) }
                    )
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
                        text = "Erstellen",
                        onClick = {
                            entwurf.alsDefinition(vorlage)?.let { definition ->
                                onSave(definition)
                                onDismiss()
                            }
                        },
                        // #142: das verborgene Zeitfeld darf das Speichern nicht grundlos sperren.
                        enabled = entwurf.speicherbar
                    )
                }
            }
        }
    }
}

/**
 * Die Felder des Neuanlage-Dialogs, in dieser Reihenfolge: [typKopfFelder], [typWeckerFelder],
 * [AktiviertSchalter]. Die Typ-Seite (Bearbeiten, Umbau #187/#188) setzt sie anders zusammen: Karte
 * „Typ“, dann nur noch [typWeckerFelder]. Der Aufrufer besitzt den Zustand: [entwurf] wird
 * angezeigt, jede Eingabe laeuft als Umformung durch [aendere]. Eine Umformung statt eines
 * fertigen Werts, damit zwei Eingaben im selben Frame sich nicht gegenseitig mit einem
 * veralteten Stand ueberschreiben. [bestehenderName] ist der gespeicherte Name des bearbeiteten
 * Typs (`null` = Neuanlage) - nur er loest den Umbenennungs-Hinweis aus.
 */
internal fun LazyListScope.typFormularFelder(
    entwurf: TypEntwurf,
    bestehenderName: String?,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    typKopfFelder(entwurf, bestehenderName, aendere)
    typWeckerFelder(entwurf, aendere)
    item { AktiviertSchalter(entwurf, aendere) }
}

/**
 * Name, Erkennungsmuster und Kategorie als Textfelder und Radio-Zeilen - nur fuer den Neuanlage-Dialog.
 * Die Typ-Seite zeigt dasselbe als Karte „Typ“ (`TypAbschnitt`), mit Chips statt Textfeldern.
 */
internal fun LazyListScope.typKopfFelder(
    entwurf: TypEntwurf,
    bestehenderName: String?,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    val kategorie = entwurf.kategorie
    val weckerAn = entwurf.weckerAn

    item {
        OutlinedTextField(
            value = entwurf.name,
            onValueChange = { neu -> aendere { it.copy(name = neu) } },
            label = { Text("Schichtname") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = entwurf.name.isBlank(),
            // Keine unbedingte Zusage: planeSchichtUmbenennungen() kann den Nachzug
            // blockieren, das Ergebnis meldet regelNachzugHinweis - Hergang schichterkennung.md.
            supportingText = if (entwurf.istUmbenennungVon(bestehenderName)) {
                {
                    Text(
                        text = ShiftDefinitionTexte.umbenennungHinweis(bestehenderName.orEmpty()),
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
            text = ShiftDefinitionTexte.musterHinweis(namensfeld = "Schichtname"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Pattern inputs
    entwurf.muster.forEachIndexed { index, keyword ->
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { newValue ->
                        aendere { e ->
                            e.copy(muster = e.muster.toMutableList().apply {
                                if (index in indices) this[index] = newValue
                            })
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

                if (entwurf.muster.size > 1) {
                    IconButton(
                        onClick = {
                            aendere { e -> e.copy(muster = e.muster.filterIndexed { i, _ -> i != index }) }
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
            onClick = { aendere { it.copy(muster = it.muster + "") } },
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
                    onWahl = { aendere { it.copy(kategorie = option) } }
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
}

/**
 * Wecker, Weckzeit und stummer Wecker - oder bei Krank und Ignorieren der Hinweis, was der Typ
 * stattdessen tut. Dieselben Felder im Neuanlage-Dialog und auf der Typ-Seite (bis ein eigenes
 * Paket den Wecker-Abschnitt der Seite baut, SPEC 3.3 Punkt 5).
 */
internal fun LazyListScope.typWeckerFelder(
    entwurf: TypEntwurf,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    val kategorie = entwurf.kategorie
    val weckerAn = entwurf.weckerAn
    val isSickDay = entwurf.isSickDay

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
                onCheckedChange = { neu -> aendere { it.copy(weckerAn = neu) } },
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
                    value = entwurf.weckzeitText,
                    onValueChange = { newValue ->
                        // Validate time format
                        if (newValue.matches(Regex("^\\d{0,2}:?\\d{0,2}$"))) {
                            aendere { it.copy(weckzeitText = newValue) }
                        }
                    },
                    label = { Text("Zeit (HH:mm)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = weckerAn,
                    isError = weckerAn && entwurf.weckzeit == null
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
                    checked = entwurf.isSilent,
                    onCheckedChange = { neu -> aendere { it.copy(isSilent = neu) } },
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

}

/**
 * Der Schalter „Schichtdefinition aktiviert“ - im Neuanlage-Dialog die letzte Zeile, auf der Typ-Seite
 * das Ende der Karte „Typ“ (Betreuer #188: der Prototyp kennt ihn nicht, er bleibt bis Etappe 9, weil
 * „aus“ sonst eine Funktion ohne Oberflaeche waere).
 */
@Composable
internal fun AktiviertSchalter(
    entwurf: TypEntwurf,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    // Der Schalter daneben hat eine Erklaerung, dieser hatte keine - und
    // genau daran ist am 19.08.2026 eine Rufbereitschaft gescheitert: sie
    // wurde AUSGESCHALTET angelegt, damit sie nicht klingelt, sollte aber
    // weiterhin "Nicht stoeren" steuern. Ausschalten beendet jedoch die
    // ERKENNUNG, und ohne erkannte Schicht gibt es keine Schichtspanne -
    // also auch kein DND- und kein Dimmer-Fenster. Wer "kein Wecker, aber
    // Zeitfenster" will, schaltet „Wecker an diesem Tag“ aus (#168).
    SwitchRow(
        title = "Schichtdefinition aktiviert",
        description = ShiftDefinitionTexte.aktiviertHinweis(entwurf.kategorie),
        checked = entwurf.isEnabled,
        onCheckedChange = { neu -> aendere { it.copy(isEnabled = neu) } },
        titleFontWeight = FontWeight.Normal
    )
}

/** Deckkraft einer ausgegrauten Zeile: unter Material-Standard (0,38), damit Text lesbar bleibt. */
private const val AUSGEGRAUT_ALPHA = 0.6f

/** Einzug der Zeilentexte unter dem Radio-Knopf: 24 dp Knopf + 8 dp + 16 dp Farbmarke + 8 dp, wie in [KategorieZeile]. */
private val KATEGORIE_TEXT_EINZUG = 56.dp

/**
 * Eine Zeile der Kategorie-Wahl (Prototyp `katWahl`): Radio-Knopf, Farbmarke der Kategorie, Name
 * ([titel]) - bei Rufbereitschaft und Krank mit der Marke „Sonderfall“ - und Kurzbeschreibung. Mindestens
 * 48 dp hoch (`RadioButton` mit `onClick = null` bringt das Touchziel nicht selbst mit).
 * Geteilt vom Neuanlage-Dialog und der Kategorie-Wahl der Typ-Seite. [innenabstand] liegt INNERHALB
 * der antippbaren Flaeche, damit die ganze Zeilenbreite trifft.
 */
@Composable
internal fun KategorieZeile(
    option: ShiftKategorie,
    gewaehlt: Boolean,
    onWahl: () -> Unit,
    innenabstand: Dp = 0.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = gewaehlt, onClick = onWahl, role = Role.RadioButton)
            .padding(horizontal = innenabstand, vertical = SpacingConstants.SPACING_SMALL),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = gewaehlt, onClick = null)
        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        KategorieMarke(option, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        // weight(1f): sonst laeuft die Beschreibung bei 320 dp + Schrift 1,3 unter den Knopf
        Column(modifier = Modifier.weight(1f)) {
            // FlowRow: die Marke „Sonderfall“ bricht bei 320 dp unter den Namen, statt ihn zu quetschen.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = option.titel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // Dieselbe Regel wie `schichttypBloecke` und EinordnenDialog (SPEC E3): Rufbereitschaft und Krank.
                if (option == ShiftKategorie.RUFBEREITSCHAFT || option == ShiftKategorie.KRANK) {
                    SonderfallMarke()
                }
            }
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

    /**
     * Der Hinweis unter „Schichtdefinition aktiviert“, je nach Kategorie (Dialog und Typ-Seite).
     * #142: der Wecker-aus-Verweis passt nicht auf eine Krankmeldung - dort kostet „aus“ die
     * Tagesfreigabe, nicht ein Zeitfenster. Ignorieren ergibt nie einen Wecker, und der Schalter
     * „Wecker an diesem Tag“ ist dort ausgeblendet: der allgemeine Hinweis verwiese ins Leere.
     */
    fun aktiviertHinweis(kategorie: ShiftKategorie): String = when (kategorie) {
        ShiftKategorie.KRANK -> KRANKMELDUNG_AKTIVIERT_HINWEIS
        ShiftKategorie.IGNORIEREN -> IGNORIEREN_AKTIVIERT_HINWEIS
        else -> AKTIVIERT_HINWEIS
    }

    /**
     * Wie ein Muster im Kalender trifft (Dialog und Typ-Seite). [namensfeld] ist die Beschriftung
     * des Namensfelds an der jeweiligen Stelle: „Schichtname“ im Dialog, „Name“ in der Karte der
     * Typ-Seite - ein Hinweis nennt die Beschriftung wortgleich.
     */
    fun musterHinweis(namensfeld: String): String =
        "Ein Muster trifft, wenn es im Titel des Kalendertermins als eigenes Wort vorkommt " +
            "(\"FD\" trifft \"FD Station 3\", nicht \"FD2\"). Der $namensfeld oben zählt ab zwei " +
            "Zeichen ebenfalls als Muster."

    /**
     * Beim Umbenennen eines BESTEHENDEN Typs. Keine unbedingte Zusage: `planeSchichtUmbenennungen()`
     * kann den Nachzug blockieren, das Ergebnis meldet `regelNachzugHinweis`.
     */
    fun umbenennungHinweis(alterName: String): String =
        "Dimmer- und Hue-Regeln, die auf \"$alterName\" zeigen, werden beim Speichern nach " +
            "Möglichkeit auf den neuen Namen umgestellt. Klappt das nicht – etwa weil der neue " +
            "Name schon vergeben ist –, sagt die App es dir; dann wählst du die Schicht in der " +
            "betroffenen Regel neu aus."
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
