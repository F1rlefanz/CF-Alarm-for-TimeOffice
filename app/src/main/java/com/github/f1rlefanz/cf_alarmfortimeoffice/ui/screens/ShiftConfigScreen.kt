package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.SchichttypEinordnen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.AufklappHinweis
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.EinordnenDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.EinordnenTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.OhneEintragDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.OhneEintragTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftEditDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtFolgen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypBlock
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.kurzform
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.listenCode
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.schichttypBloecke

/**
 * Die Beispiele, die [SHIFT_RECOGNITION_HINT] nennt - bewusst als Listen und nicht als Prosa im
 * Satz: so behauptet der Hinweistext ueberpruefbar etwas ueber die echte
 * [ShiftConfig.getDefaultConfig], und `ShiftConfigScreenTextTest` schlaegt fehl, sobald ein hier
 * genanntes Muster dort nicht mehr vorkommt. Genau diese Drift hatte der Text schon einmal: er
 * nannte nur die Stationskuerzel als "die Standardmuster", obwohl die Vorgaben laengst zusaetzlich
 * allgemeine Bezeichnungen enthielten. Seit #128 gibt es in den Vorgaben keine Stationskuerzel mehr.
 */
internal val SHIFT_HINT_GENERIC_EXAMPLES = listOf("Frühdienst", "Spätdienst", "Nachtdienst")

/** Siehe [SHIFT_HINT_GENERIC_EXAMPLES]. */
internal val SHIFT_HINT_SHORT_CODE_EXAMPLES = listOf("F", "S", "N", "ZD")

/**
 * Der erste Satz - die eigentliche Regel. Er steht IMMER da.
 *
 * Warum geteilt: siehe [SchichterkennungsHinweis].
 *
 * Der Text muss sagen, was [ShiftDefinition.matchesKeywords] wirklich tut (der Name zaehlt ab zwei
 * Zeichen als Muster mit) - Hergang ui-texte-und-layout.md.
 *
 * Der Text nennt deshalb KEINE vollstaendige Musterliste (die driftet mit jeder Aenderung der
 * Vorgaben), sondern verweist auf die Karten darunter - dort steht pro Schicht, welche Muster
 * wirklich gelten.
 */
internal val SHIFT_RECOGNITION_HINT_KURZ: String =
    "Erkannt wird über die Muster oder den Schichtnamen (ab zwei Zeichen): eines davon muss im " +
        "Titel deines Kalendertermins als eigenes Wort vorkommen."

/** Die Beispiele und der Stationshinweis - aufklappbar, siehe [SHIFT_RECOGNITION_HINT_KURZ]. */
internal val SHIFT_RECOGNITION_HINT_DETAIL: String =
    "Welche Muster ein Typ hat, siehst du, wenn du ihn in der Liste darunter antippst – die Zeile " +
        "selbst zeigt nur das erste als Code. Die Vorgaben enthalten kurze Codes (" +
        SHIFT_HINT_SHORT_CODE_EXAMPLES.joinToString(", ") +
        ") und ausgeschriebene Bezeichnungen (" +
        SHIFT_HINT_GENERIC_EXAMPLES.joinToString(", ") +
        "). Nutzt dein Dienstplan andere Kürzel, trage sie im Typ ein; Kürzel aus deinem Kalender, " +
        "die noch keiner Schicht gehören, schlägt die App dir zum Zuordnen vor. Ohne " +
        "passendes Muster und ohne passenden Namen wird keine Schicht erkannt und es klingelt " +
        "kein Wecker."

/**
 * Der vollstaendige Hinweis. Bleibt als EIN Text bestehen, damit
 * `ShiftConfigScreenTextTest` ihn weiterhin gegen die echte Standardkonfiguration pruefen kann -
 * die Aufteilung ist eine Frage der Darstellung, nicht des Inhalts.
 */
internal val SHIFT_RECOGNITION_HINT: String =
    SHIFT_RECOGNITION_HINT_KURZ + " " + SHIFT_RECOGNITION_HINT_DETAIL

/** Texte der Typ-Liste, die ein Test festhaelt (Beschriftungen wortgleich zum Editor). */
internal object ShiftConfigTexte {
    /** Nennt auch die Wahl der festen Zeile: [resetToDefaultsPreservingAutoAlarm] setzt sie zurueck. */
    const val RESET_RUECKFRAGE =
        "Alle Schichtdefinitionen werden durch die Standardwerte ersetzt – eigene Namen, " +
            "Erkennungsmuster, Kategorien, Weckzeiten, die Schalter „Wecker an diesem Tag“ und " +
            "„Stummer Wecker“ sowie die Wahl bei „Ohne Kalendereintrag“ gehen verloren. Alle " +
            "kommenden Wecker werden mit den Standard-Weckzeiten neu gesetzt. Der Schalter " +
            "„Automatische Alarme“ im Bereich „Wecker“ bleibt dabei unverändert. Das lässt sich " +
            "nicht rückgängig machen."

    /** contentDescription des Symbols an einer Zeile mit stummem Wecker. */
    const val STUMM_SYMBOL = "Stummer Wecker – kein Ton, keine Vibration, kein Vollbild"
}

/**
 * PURE, TESTBAR: Was "Auf Standardwerte zuruecksetzen" wirklich schreibt.
 *
 * WARUM NICHT EINFACH [ShiftConfig.getDefaultConfig]: Die Standardkonfiguration enthaelt
 * `autoAlarmEnabled = true`. Wer die automatischen Alarme im Wecker-Tab bewusst ausgeschaltet hat
 * (laut CLAUDE.md eine ECHTE, sofortige Pause) und danach hier seine Schichtdefinitionen aufraeumt,
 * haette diese Pause unwissentlich aufgehoben - `updateShiftConfig()` persistiert sofort und ruft
 * `triggerAlarmCreationFromConfigUpdate()`, es wuerden also im selben Moment wieder Wecker
 * angelegt. Dieser Screen konfiguriert Schichtdefinitionen; der Automatik-Schalter gehoert dem
 * Wecker-Tab und bleibt deshalb unangetastet - dieselbe Trennung, die auch die Master-Pause
 * einhaelt (sie ruehrt `autoAlarmEnabled` bewusst nicht an).
 *
 * Nur wenn noch gar keine Konfiguration geladen ist, gelten die Standardwerte vollstaendig - dann
 * gibt es keinen Nutzerwunsch, der erhalten werden koennte.
 */
internal fun resetToDefaultsPreservingAutoAlarm(current: ShiftConfig?): ShiftConfig {
    val defaults = ShiftConfig.getDefaultConfig()
    return current?.let { defaults.copy(autoAlarmEnabled = it.autoAlarmEnabled) } ?: defaults
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftConfigScreen(
    shiftViewModel: ShiftViewModel,
    onNavigateBack: () -> Unit
) {
    // collectAsStateWithLifecycle statt collectAsState: reiner Anzeige-Zustand. Sammeln pausiert
    // unterhalb von STARTED, es haengt also kein Seiteneffekt daran - der Zustand ist ein heisser
    // StateFlow im ViewModel und liegt beim Zurueckkehren sofort wieder aktuell an.
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    // Statuszeile je Schicht (#70). Das Abo HIER startet das Lesen der Dimmer-/Hue-/DND-Stores
    // (WhileSubscribed) - nicht schon der App-Start, an dem das ViewModel entsteht.
    val schichtFolgen by shiftViewModel.schichtFolgen.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingDefinition by remember { mutableStateOf<ShiftDefinition?>(null) }

    // Beide destruktiven Aktionen dieses Screens laufen ueber eine Rueckfrage. Ohne sie war ein
    // Fehlgriff nicht wiederherstellbar: `updateShiftConfig()` persistiert sofort UND ruft
    // `triggerAlarmCreationFromConfigUpdate()`, das die System-Alarme neu setzt bzw. den Alarm
    // der geloeschten Schicht cancelt. Es gibt kein Undo, und "Auf Standardwerte zuruecksetzen"
    // holt nur die Standardwerte zurueck, nicht die selbst getippten Muster/Weckzeiten.
    var pendingDelete by remember { mutableStateOf<ShiftDefinition?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    /** Der Fund-Code, fuer den gerade die Kategorie-Wahl offen ist (null = geschlossen). */
    var einordnenCode by remember { mutableStateOf<String?>(null) }

    /**
     * Die Wahl „Tage ohne Eintrag verhalten sich“ an der festen Zeile ist offen. Ueberlebt eine
     * Konfigurationsaenderung (Dunkelmodus, Schriftgroesse): sie schreibt sofort, der Nutzer soll
     * nicht mitten in der Wahl vor der Liste stehen.
     */
    var ohneEintragOffen by rememberSaveable { mutableStateOf(false) }

    /**
     * „Als neuer Typ“: der Editor ist mit dieser Vorlage offen. NICHTS davon ist gespeichert - erst
     * „Erstellen“ im Editor legt den Typ an ([ShiftViewModel.legeTypAusCodeAn]), Abbrechen verwirft ihn.
     */
    var neuerTypAusFund by remember { mutableStateOf<NeuerTypAusFund?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Ohne Ein-Zeilen-Kuerzung: bei 320 dp + Schrift 1,3 stand "Schicht-Kon…" da.
                    // Der Titel-Stil trennt jetzt (Hyphens.Auto), zwei Zeilen wie die uebrigen Kopfzeilen.
                    Text("Schicht-Konfiguration")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    HilfeKnopf(HilfeThema.ANLEITUNG_SCHICHTEN)
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Schicht hinzufügen"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            // Schichttypen
            Text(
                "Schichttypen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // REGEL-NACHZUG BEIM UMBENENNEN: Ist eine Dimmer-/Hue-Regel NICHT auf den neuen Namen
            // mitgezogen worden, tut eine bewusst eingerichtete Funktion ab jetzt nichts mehr.
            // Das muss der Nutzer DORT erfahren, wo er gerade steht - und beim Umbenennen steht er
            // auf genau diesem Screen. Bis zur Korrektur ging die Meldung in `ShiftUiState.error`
            // und damit an die Snackbar des MainContentScreen, den in diesem Moment niemand sieht;
            // die unmittelbar folgende Schichterkennung loeschte sie ausserdem sofort wieder.
            // Deshalb: eigenes Feld, bleibende Karte, und weg erst auf Bestaetigung.
            shiftState.regelNachzugHinweis?.let { hinweis ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(SpacingConstants.PADDING_CARD)) {
                        Text(
                            "Regeln prüfen",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            hinweis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(
                            onClick = { shiftViewModel.clearRegelNachzugHinweis() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Verstanden")
                        }
                    }
                }
            }

            // NICHT LESBARE KONFIGURATION ist etwas anderes als "noch keine angelegt".
            //
            // Seit die App eine vorhandene, aber nicht dekodierbare Konfiguration NICHT mehr mit
            // Standardwerten ueberschreibt (Rohdaten liegen als `shift_config_broken`), ist
            // `currentShiftConfig == null` ein echter, dauerhafter Zustand - und dieser Screen
            // rendert dann eine leere Liste, die aussieht wie "du hast noch nichts eingerichtet".
            // In diesem Projekt ist "leer" die gefaehrlichste Anzeige.
            if (shiftState.currentShiftConfig == null && !shiftState.isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(SpacingConstants.PADDING_CARD)) {
                        Text(
                            "Schicht-Konfiguration nicht lesbar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Deine gespeicherte Konfiguration konnte nicht gelesen werden. Sie wird " +
                                "NICHT überschrieben — die Rohdaten sind gesichert. Bestehende Wecker " +
                                "bleiben gestellt. Diese Liste ist deshalb leer, nicht weil du nichts " +
                                "eingerichtet hättest.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))
            }

            // GRUPPIERT WIRD NUR HIER, IN DER ANZEIGE (SPEC 3.1). `definitionen` bleibt, wie gespeichert:
            // die Reihenfolge entscheidet die Erkennung (erster Treffer gewinnt), und Bearbeiten und
            // Loeschen unten arbeiten auf genau dieser Liste. Eine NICHT lesbare Konfiguration (null)
            // bekommt keine Gruppen - sieben "Noch kein Typ"-Zeilen waeren dort eine Aussage ueber
            // Daten, die gar nicht gelesen werden konnten (die Karte oben sagt es richtig).
            val definitionen = shiftState.currentShiftConfig?.definitions
            val bloecke = remember(definitionen) {
                definitionen?.let { schichttypBloecke(it) }.orEmpty()
            }
            LazyColumn(
                // weight(1f) ist Pflicht, sonst schiebt die Liste den Reset-Knopf aus dem
                // Bildschirm (ui-texte-und-layout.md).
                modifier = Modifier.weight(1f),
                // Fussabstand: ohne ihn schneidet die Liste Rand und Schatten der letzten Karte an.
                contentPadding = PaddingValues(bottom = SpacingConstants.SPACING_SMALL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
            ) {
                // „Im Kalender gefunden, noch nicht eingeordnet“ (SPEC 3.1): Codes aus dem ECHTEN
                // Kalender, die kein Muster trifft - steht bewusst GANZ OBEN, denn wer diesen Screen
                // oeffnet, sucht meist genau das: warum eine Schicht nicht erkannt wird.
                if (!shiftState.codeSuggestions.isEmpty) {
                    item(key = "kalender-fund") {
                        KalenderFundKarte(
                            result = shiftState.codeSuggestions,
                            onEinordnen = { einordnenCode = it },
                            onIgnorieren = { shiftViewModel.ignoriereCode(it) }
                        )
                    }
                }

                // Warum IN der Liste: siehe SchichterkennungsHinweis. Ohne Liste gibt es nichts,
                // was er verdraengen koennte - und wer hier steht, braucht ihn am dringendsten.
                item(key = "erkennungs-hinweis") {
                    SchichterkennungsHinweis()
                }

                if (definitionen?.isEmpty() == true) {
                    item(key = "keine-typen") { KeineSchichttypenKarte() }
                }

                items(
                    bloecke,
                    key = { block ->
                        when (block) {
                            is SchichttypBlock.Gruppe -> "gruppe-${block.kategorie.schluessel}"
                            SchichttypBlock.FesteZeile -> "feste-zeile"
                        }
                    }
                ) { block ->
                    when (block) {
                        is SchichttypBlock.Gruppe -> SchichttypGruppeKarte(
                            gruppe = block,
                            folgen = schichtFolgen,
                            onEdit = { editingDefinition = it },
                            onDelete = { pendingDelete = it }
                        )
                        SchichttypBlock.FesteZeile -> FesteZeileKarte(
                            kurzform = shiftState.currentShiftConfig
                                ?.let { OhneEintragTexte.kurzform(it) }
                                ?: SchichttypBlock.FesteZeile.kurzform,
                            onClick = { ohneEintragOffen = true }
                        )
                    }
                }
            }

            // Die Liste darueber traegt weight(1f), ein zweiter Spacer mit weight wuerde ihr Platz
            // wegnehmen.
            OutlinedButton(
                onClick = { showResetConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Auf Standardwerte zurücksetzen")
            }
        }
    }

    // Add/Edit Dialog - auch der Editor fuer „Als neuer Typ“ aus dem Fund (Vorlage statt bestehendem Typ).
    if (showAddDialog || editingDefinition != null || neuerTypAusFund != null) {
        ShiftEditDialog(
            shift = editingDefinition,
            vorlage = neuerTypAusFund?.vorlage,
            onSave = { newDefinition ->
                val ausFund = neuerTypAusFund
                if (ausFund != null) {
                    // Eigener Weg: er raeumt den Code auch bei anderen Typen ab (siehe legeTypAusCodeAn).
                    shiftViewModel.legeTypAusCodeAn(ausFund.code, newDefinition)
                } else {
                    shiftState.currentShiftConfig?.let { config ->
                        val updatedDefinitions = if (editingDefinition != null) {
                            config.definitions.map {
                                if (it.id == editingDefinition?.id) newDefinition else it
                            }
                        } else {
                            config.definitions + newDefinition
                        }
                        shiftViewModel.updateShiftConfig(
                            config.copy(definitions = updatedDefinitions)
                        )
                    }
                }
                showAddDialog = false
                editingDefinition = null
                neuerTypAusFund = null
            },
            onDismiss = {
                showAddDialog = false
                editingDefinition = null
                neuerTypAusFund = null
            }
        )
    }

    // Rueckfrage vor dem Loeschen einer Schicht: der Muelleimer sitzt am rechten Rand einer
    // Karte, deren restliche Flaeche "Bearbeiten" ist - ein Fehlgriff ist strukturell
    // wahrscheinlich, und er nimmt Muster, Weckzeit UND den bereits gesetzten Alarm mit.
    pendingDelete?.let { definition ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Schicht löschen?") },
            text = {
                Text(
                    // #142: ein Krankmeldungs-Typ hat keine Weckzeit, die entfiele - wohl aber
                    // freigegebene Tage, die ihre Wecker zurueckbekommen.
                    (if (definition.isSickDay) {
                        ShiftDefinitionTexte.krankmeldungLoeschenText(definition.name)
                    } else {
                        "\"${definition.name}\" wird mit allen Mustern und der Weckzeit " +
                            "${definition.getAlarmTimeFormatted()} gelöscht. Der zugehörige Wecker " +
                            "entfällt damit sofort. Das lässt sich nicht rückgängig machen."
                    }) +
                        // Umbau #177: die feste Zeile verwies auf diesen Typ - sie faellt im selben
                        // Schreibvorgang zurueck, und der Nutzer soll das VORHER wissen.
                        if (shiftState.currentShiftConfig?.ohneKalendereintragWie == definition.id) {
                            "\n\n" + OhneEintragTexte.LOESCHEN_FOLGE
                        } else {
                            ""
                        }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Loeschen UND das Zuruecksetzen der festen Zeile in EINEM Schreibvorgang.
                        shiftViewModel.loescheSchichttyp(definition)
                        pendingDelete = null
                    }
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Rueckfrage vor dem Zuruecksetzen: der Knopf ist ganzbreit und sitzt am unteren Rand,
    // direkt dort, wo beim Scrollen getippt wird.
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Auf Standardwerte zurücksetzen?") },
            text = {
                Text(ShiftConfigTexte.RESET_RUECKFRAGE)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Nur die Definitionen zuruecksetzen, nicht den Automatik-Schalter -
                        // siehe resetToDefaultsPreservingAutoAlarm().
                        shiftViewModel.updateShiftConfig(
                            resetToDefaultsPreservingAutoAlarm(shiftState.currentShiftConfig)
                        )
                        showResetConfirmation = false
                    }
                ) {
                    Text("Zurücksetzen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Einordnen eines Fund-Codes (SPEC 3.2). Bewusst eine Wahl des Menschen und keine Automatik: welche
    // Schicht „AD1“ ist, weiss nur er - und eine falsche Zuordnung stellt einen Wecker auf die falsche
    // Uhrzeit. „Gehoert zu“ traegt den Code als Muster ein (und schaltet das Ziel ein), „neuer Typ“
    // oeffnet den Editor mit der Vorlage der Kategorie.
    // Die Wahl „Tage ohne Eintrag verhalten sich“ (SPEC 3.3 Punkt 2): wirkt sofort, der Dialog bleibt offen.
    if (ohneEintragOffen) {
        shiftState.currentShiftConfig?.let { config ->
            OhneEintragDialog(
                config = config,
                onWaehle = { shiftViewModel.waehleOhneEintrag(it) },
                onTypOeffnen = { typ ->
                    ohneEintragOffen = false
                    editingDefinition = typ
                },
                onDismiss = { ohneEintragOffen = false }
            )
        }
    }

    einordnenCode?.let { code ->
        EinordnenDialog(
            code = code,
            definitionen = shiftState.currentShiftConfig?.definitions.orEmpty(),
            onGehoertZu = { ziel ->
                shiftViewModel.assignCodeToDefinition(code, ziel.id)
                einordnenCode = null
            },
            onNeuerTyp = { kategorie ->
                SchichttypEinordnen.neuerTypAusCode(code, kategorie)?.let { vorlage ->
                    neuerTypAusFund = NeuerTypAusFund(code, vorlage)
                }
                einordnenCode = null
            },
            onDismiss = { einordnenCode = null }
        )
    }
}

/** Ein Fund-Code und der daraus vorbelegte, noch NICHT gespeicherte Typ (siehe [SchichttypEinordnen.neuerTypAusCode]). */
private data class NeuerTypAusFund(val code: String, val vorlage: ShiftDefinition)

/**
 * Der Erkennungs-Hinweis, aufklappbar.
 *
 * WARUM AUFKLAPPBAR UND WARUM IN DER LISTE: Der vollstaendige Text fuellte auf einem Geraet mit
 * grosser Schrift die halbe Seite - und weil die Karte ausserhalb der `LazyColumn` verankert war,
 * liess sie sich nicht wegscrollen. Fuer die Schichttypen, um die es auf diesem Bildschirm geht,
 * blieben ein bis zwei Karten Platz (vom Nutzer gemeldet, 21.08.2026).
 *
 * Der erste Satz bleibt sichtbar: er ist die eigentliche Regel, und wer auf einer anderen Station
 * arbeitet, bekommt ohne sie gar keinen Wecker. Die Beispiele dahinter sind Nachschlagewerk und
 * stehen hinter dem Umschalter. Aufbau: [AufklappHinweis].
 */
@Composable
private fun SchichterkennungsHinweis() {
    AufklappHinweis(
        kurz = SHIFT_RECOGNITION_HINT_KURZ,
        voll = SHIFT_RECOGNITION_HINT,
        aufklappText = "Beispiele und eigene Kürzel"
    )
}

/**
 * „Im Kalender gefunden, noch nicht eingeordnet“ (SPEC 3.1, Prototyp `zeigeListe`): die Codes, die im
 * Kalender des Nutzers stehen, aber von keinem Erkennungsmuster getroffen werden - haeufigste zuerst.
 * Je Code „Einordnen …“ (oeffnet die Kategorie-Wahl) und „Ignorieren“.
 *
 * WARUM DIESE KARTE EXISTIERT: Die Standardkonfiguration kann die Kuerzel einer fremden Station nur
 * raten, und Raten skaliert nicht - am echten Dienstplan ist genau das schiefgegangen. Was im
 * Kalender steht, weiss die App dagegen genau; sie muss es nur zeigen. Fuer jemanden auf einer
 * anderen Station ist das der Unterschied zwischen „laeuft nach zwei Minuten“ und „laeuft nie, und er
 * merkt es erst nach dem Verschlafen“.
 *
 * Die Deckelung wird BENANNT statt verschwiegen ([ShiftCodeSuggester.SuggestionResult.droppedCount]) -
 * eine stillschweigend gekuerzte Liste liest sich wie Vollstaendigkeit. Die Zeile nennt nur, was die
 * App weiss: wie oft und wann zuerst - nicht, in welchem Zeitraum gesucht wurde (der haengt an der
 * geladenen Liste).
 */
@Composable
private fun KalenderFundKarte(
    result: ShiftCodeSuggester.SuggestionResult,
    onEinordnen: (String) -> Unit,
    onIgnorieren: (String) -> Unit
) {
    // Prototyp `.fund`: weisse Karte mit gestricheltem Bernsteinrand.
    val rand = if (isSystemInDarkTheme()) Color(0xFFE0A24A) else Color(0xFFB26A00)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .gestrichelterRand(rand, 16.dp)
            .padding(horizontal = ZEILE_RAND, vertical = SpacingConstants.SPACING_LARGE),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
    ) {
        Text(
            EinordnenTexte.FUND_TITEL,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        result.suggestions.forEach { suggestion ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZEILE_ABSTAND),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CodeMarke(code = suggestion.code, kategorie = ShiftKategorie.IGNORIEREN)
                    Text(
                        EinordnenTexte.fundZeile(suggestion),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // KnopfZeile statt Row + weight(1f): bei 320 dp + grosser Schrift kuerzte die
                // Gewichtung die Beschriftung mit Auslassung (Skill ui-und-navigation).
                KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                    CompactButton(onClick = { onEinordnen(suggestion.code) }, text = EinordnenTexte.EINORDNEN)
                    CompactOutlinedButton(onClick = { onIgnorieren(suggestion.code) }, text = EinordnenTexte.IGNORIEREN)
                }
            }
        }
        if (result.droppedCount > 0) {
            Text(
                "… und ${result.droppedCount} weitere, hier nicht gezeigt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Die Karte fuer eine Konfiguration ohne einen einzigen Typ (nicht: nicht lesbar). */
@Composable
private fun KeineSchichttypenKarte() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Info,
                // dekorativ: "Keine Schichttypen definiert" darunter sagt es bereits
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_XXL),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
            Text(
                "Keine Schichttypen definiert",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Füge Schichttypen hinzu, um die automatische Erkennung zu aktivieren",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Eine Kategorie als Karte: Titel (mit "Sonderfall"-Marke), darunter ihre Typen in GESPEICHERTER
 * Reihenfolge, bei keinem Typ der Hinweis "Noch kein Typ in dieser Kategorie." (Prototyp
 * `zeigeListe`, SPEC 3.1).
 */
@Composable
private fun SchichttypGruppeKarte(
    gruppe: SchichttypBlock.Gruppe,
    /**
     * Was ein Typ ausserhalb des Weckers ausloest, nach `definition.id`; fehlt ein Eintrag, ist es
     * (noch) nicht gelesen. BEWUSST ohne Default (Skill cfalarm-ui-und-navigation: Anzeigepfad).
     */
    folgen: Map<String, SchichtFolgen>,
    onEdit: (ShiftDefinition) -> Unit,
    onDelete: (ShiftDefinition) -> Unit
) {
    ListenKarte {
        GruppenKopf(titel = gruppe.titel, sonderfall = gruppe.sonderfall)
        gruppe.typen.forEach { definition ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SchichttypZeile(
                definition = definition,
                folgen = folgen[definition.id],
                onEdit = { onEdit(definition) },
                onDelete = { onDelete(definition) }
            )
        }
        if (gruppe.leer) {
            Text(
                SchichttypTexte.LEERE_GRUPPE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = ZEILE_RAND,
                    end = ZEILE_RAND,
                    bottom = SpacingConstants.SPACING_MEDIUM
                )
            )
        }
    }
}

/**
 * "Ohne Kalendereintrag" (E5): eine feste Zeile fuer Tage, an denen im Kalender gar nichts steht.
 * Antippen oeffnet die Wahl "Tage ohne Eintrag verhalten sich" (Umbau #177); loeschbar ist die Zeile
 * nicht. Die Kurzform nennt, was an einem leeren Tag geschieht ([OhneEintragTexte.kurzform]) - nie
 * mehr, als der Sync tatsaechlich tut (Skill cfalarm-ui-und-navigation).
 */
@Composable
private fun FesteZeileKarte(kurzform: String, onClick: () -> Unit) {
    ListenKarte {
        GruppenKopf(titel = SchichttypBlock.FesteZeile.titel, sonderfall = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ZEILE_MINDESTHOEHE)
                .clickable(onClick = onClick)
                .padding(horizontal = ZEILE_RAND, vertical = SpacingConstants.SPACING_MEDIUM),
            horizontalArrangement = Arrangement.spacedBy(ZEILE_ABSTAND),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CodeMarke(code = SchichttypBlock.FesteZeile.code, kategorie = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    SchichttypBlock.FesteZeile.titel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    kurzform,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Wie die Zeilen des Prototyps (`.chev`): die Zeile fuehrt irgendwohin.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Dezente Karte der Liste: `surface` mit Schatten (Skill: nie `surfaceVariant` als Flaeche). */
@Composable
private fun ListenKarte(inhalt: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { inhalt() }
    }
}

/**
 * Kopf einer Gruppe. FlowRow statt Row: neben einem Namen umbricht die Marke "Sonderfall" bei
 * 320 dp und grosser Schrift unter den Titel, statt ihn zu quetschen.
 */
@Composable
private fun GruppenKopf(titel: String, sonderfall: Boolean) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZEILE_RAND,
                end = ZEILE_RAND,
                top = SpacingConstants.SPACING_LARGE,
                bottom = SpacingConstants.SPACING_SMALL
            )
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            titel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (sonderfall) SonderfallMarke()
    }
}

/** Die Marke „Sonderfall“ (Prototyp `.badge` = violett, wie die Marke „Rufbereitschaft“). */
@Composable
internal fun SonderfallMarke() {
    val farben = kategorieFarben(ShiftKategorie.RUFBEREITSCHAFT, isSystemInDarkTheme())
    Surface(shape = RoundedCornerShape(8.dp), color = farben.flaeche) {
        Text(
            SchichttypTexte.SONDERFALL,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = farben.text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Das kleine Farbquadrat einer Kategorie (Prototyp `.code` mit 16 x 16 dp und Radius 4). */
@Composable
internal fun KategorieMarke(kategorie: ShiftKategorie, modifier: Modifier = Modifier) {
    val farben = kategorieFarben(kategorie, isSystemInDarkTheme())
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)).background(farben.flaeche))
}

/** Gestrichelter Rand um eine abgerundete Flaeche (Prototyp `.fund`; die feste Zeile nutzt dasselbe). */
private fun Modifier.gestrichelterRand(farbe: Color, radius: Dp, breite: Dp = 1.5.dp): Modifier = drawBehind {
    drawRoundRect(
        color = farbe,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = breite.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
        )
    )
}

/**
 * Eine Zeile eines Typs: Code, Name, Statuszeile (Weckzeit bzw. warum es keine gibt) und Kurzform.
 * Antippen bearbeitet, das Papierkorb-Symbol loescht (mit Rueckfrage) - beides bleibt, bis die
 * Typ-Seite des Umbaus es uebernimmt.
 */
@Composable
private fun SchichttypZeile(
    definition: ShiftDefinition,
    folgen: SchichtFolgen?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ZEILE_MINDESTHOEHE)
            .clickable(onClick = onEdit)
            .padding(
                start = ZEILE_RAND,
                end = ZEILE_ENDE,
                top = SpacingConstants.SPACING_SMALL,
                bottom = SpacingConstants.SPACING_SMALL
            ),
        horizontalArrangement = Arrangement.spacedBy(ZEILE_ABSTAND),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CodeMarke(code = definition.listenCode(), kategorie = definition.kategorie)
        Column(modifier = Modifier.weight(1f)) {
            // Der Papierkorb steht in der NAMENSZEILE, nicht als dritte Spalte der Zeile: dort nahm er
            // der Status- und der Kurzform-Zeile 48 dp. Bei 320 dp blieben 146 statt der 208 dp der
            // alten Karte, und die Abschnitte der Kurzform ("Dimmen: allgemeine Regel", geschuetzte
            // Leerzeichen) brachen mitten im Wort (Emulator 17.10.2026, gemessen am uiautomator-Dump).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    definition.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDelete,
                    // Zurueck an den Kartenrand: die Zeile endet bei ZEILE_ENDE, der Knopf soll
                    // wie zuvor nur 4 dp davor stehen.
                    modifier = Modifier.offset(x = ZEILE_ENDE - SpacingConstants.SPACING_EXTRA_SMALL)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Löschen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            StatusZeile(definition)
            // Kurzform (SPEC 3.1): rein lesend, nicht eigens tippbar, keine Warnfarbe - sie
            // beschreibt die Konfiguration, sie mahnt nicht (#70).
            kurzform(definition, folgen)?.let { kurz ->
                Text(
                    kurz,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Die Zeile unter dem Namen, die sagt, OB und WANN der Typ weckt - oder warum nicht. Keine Zeile
 * bei einem Typ mit `weckerAn = false` (das sagt die Kurzform: "kein Wecker") und bei einem
 * aktiven Ignorieren-Typ ("wird übergangen").
 */
@Composable
private fun StatusZeile(definition: ShiftDefinition) {
    val ignoriert = definition.kategorie == ShiftKategorie.IGNORIEREN
    // EINE ANGEZEIGTE WECKZEIT, DIE NIE GESTELLT WIRD, IST DIE GEFAEHRLICHSTE ANZEIGE, DIE EINE
    // WECKER-APP HABEN KANN (ui-texte-und-layout.md). Deshalb steht "Alarm: HH:mm" nur bei einem
    // Typ, der wirklich einen Wecker stellt: erkannt (isEnabled), nicht Krank, nicht Ignorieren
    // und mit weckerAn (#168 - sonst luegt die Zeile neben "kein Wecker").
    //
    // Und der Ausgeschaltet-Text sagt seit v1.29.2, was "ausgeschaltet" WIRKLICH kostet: nicht nur
    // den Wecker, sondern die ganze Erkennung - damit auch die Schichtspanne, aus der Dimmer und DND
    // ihre Fenster ziehen. Der Schalter fuer "kein Wecker, Fenster trotzdem" heisst "Stille Schicht".
    //
    // #142: eine Krankmeldung hat keine Weckzeit - ihre Zeile sagt, was sie stattdessen tut.
    val text: String? = when {
        ignoriert -> if (definition.isEnabled) null else SchichttypTexte.IGNORIEREN_AUSGESCHALTET
        definition.isSickDay ->
            if (definition.isEnabled) {
                ShiftDefinitionTexte.KRANKMELDUNG_STATUS
            } else {
                ShiftDefinitionTexte.KRANKMELDUNG_AUSGESCHALTET
            }
        !definition.isEnabled ->
            "Ausgeschaltet — wird nicht erkannt: kein Wecker, kein Dimmer- " +
                "und kein DND-Fenster (${definition.getAlarmTimeFormatted()})"
        definition.weckerAn -> "Alarm: ${definition.getAlarmTimeFormatted()}"
        else -> null
    }
    if (text == null) return

    // weight(1f, fill = false): der lange Ausgeschaltet-Text bricht um und meldet dann die VOLLE
    // Restbreite - ungewichtet bekaemen die Marker-Icons dahinter 0 dp und verschwaenden genau an
    // der deaktivierten Schicht (Runde 4 zu #140). Gewichtet misst Row die Icons zuerst.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (definition.isSickDay && !ignoriert) {
            Icon(
                Icons.Default.Sick,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        }
        Text(
            modifier = Modifier.weight(1f, fill = false),
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (definition.isEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        if (definition.isSilent && !definition.isSickDay && !ignoriert) {
            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
            Icon(
                Icons.Default.NotificationsOff,
                contentDescription = ShiftConfigTexte.STUMM_SYMBOL,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (definition.isOnCall && !definition.isSickDay && !ignoriert) {
            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
            Icon(
                Icons.Default.PhoneInTalk,
                contentDescription = "Rufbereitschaft - Kalender wird an diesen Tagen stündlich abgefragt",
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Die Code-Marke einer Zeile (Prototyp `.code` + `.k-<kategorie>`). [kategorie] `null` = die feste
 * Zeile: ohne Flaeche, mit gestricheltem Rand. Hoechstens eine Zeile breit, damit ein langes erstes
 * Muster die Zeile nicht sprengt.
 */
@Composable
internal fun CodeMarke(code: String, kategorie: ShiftKategorie?) {
    val farben = kategorie?.let { kategorieFarben(it, isSystemInDarkTheme()) }
    val rand = MaterialTheme.colorScheme.onSurfaceVariant
    val form = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp, max = 96.dp)
            .height(32.dp)
            .clip(form)
            .then(
                if (farben != null) Modifier.background(farben.flaeche) else Modifier.gestrichelterRand(rand, 9.dp)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = farben?.text ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Flaeche und Schrift der Code-Marke einer Kategorie. */
internal data class KategorieFarben(val flaeche: Color, val text: Color)

/**
 * Hell: die Werte des Prototyps (`.k-dienst` usw.). Dunkel: dieselbe Farbfamilie, Flaeche abgedunkelt
 * und die helle Flaeche des Prototyps als Schrift. Jedes Paar erreicht mindestens 4,5:1 - das haelt
 * `ShiftConfigScreenTextTest` fest. Ohne `else`: eine achte Kategorie braucht bewusst eine Farbe.
 *
 * Noch HIER und nicht in `ui/theme/Color.kt`, obwohl der Einordnen-Dialog (#176) sie schon als zweiter
 * Verwender nutzt: das Theme liegt ausserhalb dieses Pakets. Mit der Typ-Seite (Etappe 2) gehoeren sie
 * dorthin.
 */
internal fun kategorieFarben(kategorie: ShiftKategorie, dunkel: Boolean): KategorieFarben = when (kategorie) {
    ShiftKategorie.DIENST ->
        if (dunkel) KategorieFarben(Color(0xFF5A1A29), Color(0xFFFBE1E4)) else KategorieFarben(Color(0xFFFBE1E4), Color(0xFF8E0F30))
    ShiftKategorie.RUFBEREITSCHAFT ->
        if (dunkel) KategorieFarben(Color(0xFF3F2A52), Color(0xFFEEE4F3)) else KategorieFarben(Color(0xFFEEE4F3), Color(0xFF4E2A6B))
    ShiftKategorie.FREI ->
        if (dunkel) KategorieFarben(Color(0xFF1F4A25), Color(0xFFE3F1E4)) else KategorieFarben(Color(0xFFE3F1E4), Color(0xFF1B5E20))
    ShiftKategorie.URLAUB ->
        if (dunkel) KategorieFarben(Color(0xFF15443F), Color(0xFFDDF0EE)) else KategorieFarben(Color(0xFFDDF0EE), Color(0xFF0F4F4A))
    ShiftKategorie.FORTBILDUNG ->
        if (dunkel) KategorieFarben(Color(0xFF243D5A), Color(0xFFE2EAF2)) else KategorieFarben(Color(0xFFE2EAF2), Color(0xFF24476B))
    ShiftKategorie.KRANK ->
        if (dunkel) KategorieFarben(Color(0xFF5A3A0C), Color(0xFFFBEBD3)) else KategorieFarben(Color(0xFFFBEBD3), Color(0xFF6E4100))
    ShiftKategorie.IGNORIEREN ->
        if (dunkel) KategorieFarben(Color(0xFF3E3B38), Color(0xFFEDEAE7)) else KategorieFarben(Color(0xFFEDEAE7), Color(0xFF5E5955))
}

/** Zeilenmasse der Liste (Prototyp `.row`: 60 px hoch, 18 px Rand, 14 px Abstand). */
private val ZEILE_MINDESTHOEHE = 60.dp
private val ZEILE_RAND = 18.dp
private val ZEILE_ABSTAND = 14.dp
private val ZEILE_ENDE = 12.dp
