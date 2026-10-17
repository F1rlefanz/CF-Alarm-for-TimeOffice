package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.EinordnenTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.StatusTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.tonPaar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel

/** Nutzertexte der Typ-Seite, als Konstanten (ein Test haelt sie fest). */
internal object TypSeiteTexte {
    const val SPEICHERN = "Speichern"
    const val VERWERFEN_TITEL = "Änderungen verwerfen?"

    // Sagt, was passiert (verloren geht, was noch nicht gespeichert ist) - und nichts ueber das,
    // was NICHT betroffen ist: der gespeicherte Typ und seine Wecker bleiben, wie sie sind.
    const val VERWERFEN_TEXT =
        "Deine Änderungen an diesem Typ sind noch nicht gespeichert. Gehst du zurück, gehen sie " +
            "verloren. Der gespeicherte Typ und seine Wecker bleiben, wie sie sind."
    const val VERWERFEN = "Verwerfen"
    const val WEITER_BEARBEITEN = "Weiter bearbeiten"

    // NEUANLAGE (#190): der Typ ist erst mit „Erstellen“ gespeichert. Alle Texte sagen das - ein „angelegt“
    // vor dem Tipp waere eine Aussage ueber einen Zustand, den es nicht gibt (der Prototyp legt sofort an).
    const val ERSTELLEN = "Erstellen"
    const val NEUER_TYP_TITEL = "Neuer Typ"
    const val NEUER_TYP_VERWERFEN_TITEL = "Neuen Typ verwerfen?"

    /** Was Zurueck ohne „Erstellen“ bedeutet; bei einem Fund-Code sagt der Satz, wo der Code bleibt. */
    fun neuerTypVerwerfenText(fundCode: String?): String =
        "Dieser Typ ist noch nicht angelegt. Gehst du zurück, wird er nicht angelegt." +
            if (fundCode != null) {
                " „$fundCode“ bleibt unter „${EinordnenTexte.FUND_TITEL}“."
            } else {
                ""
            }

    // Banner nach dem Einordnen (SPEC 3.3 Punkt 1, Prototyp `typ.neuBanner`). Der zweite Satz ist der des
    // Betreuers: darunter ist in Etappe 2 nur der Wecker einstellbar, der Text verspricht kein Dimmen und
    // kein Licht. Der erste Satz sagt „Neuer Typ“ statt „Neu angelegt“, der dritte nennt den Knopf.
    const val BANNER_ANFANG = "Neuer Typ aus dem Kalender-Code "
    const val BANNER_VORSTELLUNG = "Alles darunter ist eine Voreinstellung – stell es ein, wie du an diesen Tagen leben willst."
    const val BANNER_ERSTELLEN = "Angelegt ist der Typ erst mit „$ERSTELLEN“."

    /** Der Banner-Text; der Code steht fett (wie im Prototyp). [kategorie] ist die GERADE gewaehlte. */
    fun banner(code: String, kategorie: ShiftKategorie): AnnotatedString = buildAnnotatedString {
        append(BANNER_ANFANG)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(code) }
        append(" als „${kategorie.titel()}“. $BANNER_VORSTELLUNG $BANNER_ERSTELLEN")
    }
}

/** Wie ein Speichern-Versuch der Typ-Seite ausging ([TypSeiteSpeicherung.speichere]). */
internal enum class SpeicherErgebnis {
    /** Genau einmal in die Konfiguration geschrieben. */
    GESCHRIEBEN,

    /** Ein zweiter Tipp auf „Speichern“, bevor die Seite verschwunden ist: nichts geschrieben. */
    SCHON_GESCHRIEBEN,

    /** Der Typ ist nicht (mehr) in der Konfiguration: nichts geschrieben, nie neu angelegt. */
    TYP_FEHLT,

    /** Name, Muster oder Weckzeit sind nicht gueltig: nichts geschrieben. */
    NICHT_SPEICHERBAR,

    /** Es ist keine Konfiguration gelesen (noch laedt sie oder sie ist nicht lesbar): nichts geschrieben. */
    KEINE_KONFIGURATION
}

/**
 * Die Rechnung der Typ-Seite, ohne Compose - damit ein Test festhalten kann, dass ein Entwurf die
 * Konfiguration NICHT anfasst, ein Speichern genau einmal schreibt und ein verschwundener Typ nie
 * neu entsteht (Umbau #187).
 */
internal object TypSeiteEntwurf {

    /** Wurde im Formular etwas geaendert? Mass ist der Stand beim Oeffnen, nicht der gespeicherte. */
    fun hatAenderungen(ausgang: TypEntwurf, entwurf: TypEntwurf): Boolean = ausgang != entwurf

    /**
     * Die Konfiguration mit dem gespeicherten Entwurf - oder `null`, wenn [definitionId] nicht darin
     * steht oder das Formular nicht speicherbar ist. Das ist der Schreibweg des Dialogs, 1:1
     * umgezogen (`config.copy(definitions = definitions.map { … })`): nur der Typ mit dieser id wird
     * ersetzt, die Reihenfolge entscheidet die Erkennung und bleibt. Einen fehlenden Typ HAENGT
     * dieser Weg nie an (der Dialog hat das bei der Neuanlage getan, das ist hier ausgeschlossen).
     *
     * Der Entwurf geht auf den GERADE gespeicherten Typ, nicht auf den vom Oeffnen: was das Formular
     * nicht kennt, bleibt so auf dem aktuellen Stand.
     */
    fun speichernIn(config: ShiftConfig, definitionId: String, entwurf: TypEntwurf): ShiftConfig? {
        val aktuell = config.definitions.firstOrNull { it.id == definitionId } ?: return null
        val neu = entwurf.alsDefinition(aktuell) ?: return null
        return config.copy(definitions = config.definitions.map { if (it.id == definitionId) neu else it })
    }
}

/**
 * Schreibt den Entwurf hoechstens EINMAL. [schreibe] ist `ShiftViewModel.updateShiftConfig` - es
 * speichert und stoesst den Alarm-Sync an, deshalb ruft die Seite es nie je Tastendruck, sondern
 * nur hier. Ein zweiter Tipp auf „Speichern“ vor dem naechsten Frame (die Seite ist dann schon
 * als verlassen markiert, aber noch nicht weg) schreibt nicht ein zweites Mal.
 */
internal class TypSeiteSpeicherung(private val schreibe: (ShiftConfig) -> Unit) {
    private var geschrieben = false

    fun speichere(config: ShiftConfig?, definitionId: String, entwurf: TypEntwurf): SpeicherErgebnis {
        if (geschrieben) return SpeicherErgebnis.SCHON_GESCHRIEBEN
        if (config == null) return SpeicherErgebnis.KEINE_KONFIGURATION
        if (config.definitions.none { it.id == definitionId }) return SpeicherErgebnis.TYP_FEHLT
        val neueConfig = TypSeiteEntwurf.speichernIn(config, definitionId, entwurf)
            ?: return SpeicherErgebnis.NICHT_SPEICHERBAR
        geschrieben = true
        schreibe(neueConfig)
        return SpeicherErgebnis.GESCHRIEBEN
    }

    /**
     * „Erstellen“ bei der NEUANLAGE (#190): schreibt den Typ aus [vorlage] mit den Werten des Entwurfs,
     * hoechstens einmal fuer BEIDE Wege. Mit [fundCode] geht er ueber [legeAusCodeAn]
     * (`ShiftViewModel.legeTypAusCodeAn`: nimmt den Code bei allen anderen Typen heraus und stellt
     * `isEnabled` auf den Wert der Seite zurueck, #176) - sonst haengt er an und schreibt ueber `schreibe`.
     * Steht die `id` schon in der Konfiguration (Doppeltipp ueber zwei Frames), wird nichts geschrieben.
     */
    fun erstelle(
        config: ShiftConfig?,
        vorlage: ShiftDefinition,
        fundCode: String?,
        entwurf: TypEntwurf,
        legeAusCodeAn: (String, ShiftDefinition) -> Unit
    ): SpeicherErgebnis {
        if (geschrieben) return SpeicherErgebnis.SCHON_GESCHRIEBEN
        if (config == null) return SpeicherErgebnis.KEINE_KONFIGURATION
        if (config.definitions.any { it.id == vorlage.id }) return SpeicherErgebnis.SCHON_GESCHRIEBEN
        val neuerTyp = entwurf.alsDefinition(vorlage) ?: return SpeicherErgebnis.NICHT_SPEICHERBAR
        geschrieben = true
        if (fundCode != null) {
            legeAusCodeAn(fundCode, neuerTyp)
        } else {
            schreibe(config.copy(definitions = config.definitions + neuerTyp))
        }
        return SpeicherErgebnis.GESCHRIEBEN
    }
}

/** Der Entwurf ueber Drehen, Dunkelmodus und Schriftgroesse (siehe [TypEntwurf.alsSpeicherListe]). */
private val EntwurfSaver: Saver<TypEntwurf, Any> = Saver(
    save = { it.alsSpeicherListe() },
    restore = { gespeichert -> (gespeichert as? List<*>)?.let { TypEntwurf.ausSpeicherListe(it) } }
)

/**
 * Die Typ-Seite (Umbau, SPEC 3.3): ein Schichttyp als eigener Bildschirm statt des Dialogs.
 * Oben die Karte „Typ“ ([TypAbschnitt]: Name, Muster, Kategorie), darunter der Sonderfall-Kasten
 * ([SonderfallKasten], nur Rufbereitschaft, Krank, Ignorieren) und die Karte „Wecker“
 * ([WeckerAbschnitt], nicht bei Krank und Ignorieren); Tagesleiste, Dimmen, DND und Licht kommen mit
 * eigenen Paketen.
 *
 * ENTWURF STATT SOFORT-SCHREIBEN (bewusste Abweichung vom Prototyp, der keinen Speichern-Knopf
 * hat): `updateShiftConfig()` speichert und stoesst den Alarm-Sync an - je Tastendruck im Namen
 * waere das ein Sync und ein Umbenennungs-Nachzug. Geschrieben wird erst mit „Speichern“, ueber
 * [TypSeiteSpeicherung].
 *
 * ZURUECK: Die Aufloesung des Ziels bleibt allein bei `NavigationViewModel.navigateBackFrom`
 * (aufgerufen ueber [onZurueck], auch vom `BackHandler` in `MainScreen`). Der `BackHandler`
 * hier GATET nur: solange es ungespeicherte Aenderungen gibt, faengt er Zurueck ab und fragt;
 * ohne Aenderungen ist er aus, und der `BackHandler` in `MainScreen` greift unveraendert. Er
 * entscheidet nie ueber das Ziel.
 *
 * NEUANLAGE (#190): mit [vorlage] zeigt die Seite einen noch NICHT gespeicherten Typ - aus „+“ in der
 * Liste ([fundCode] `null`) oder aus „Oder als neuer Typ“ im Einordnen-Dialog ([fundCode] = der Fund-Code,
 * dazu der Banner). Titel „Neuer Typ“, Knopf „Erstellen“; gespeichert wird erst damit
 * ([TypSeiteSpeicherung.erstelle]), Zurueck ohne „Erstellen“ legt nichts an und fragt wie sonst.
 * Der Typ ist nicht in der Konfiguration, also gibt es hier kein „verschwunden“.
 *
 * @param onZurueck verlaesst die Seite zur Typ-Liste (`navigateBackFrom(state)`).
 */
@Composable
fun TypSeite(
    definitionId: String,
    shiftViewModel: ShiftViewModel,
    onZurueck: () -> Unit,
    vorlage: ShiftDefinition? = null,
    fundCode: String? = null
) {
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    val config = shiftState.currentShiftConfig
    val neu = vorlage != null
    val definition = vorlage ?: config?.definitions?.firstOrNull { it.id == definitionId }

    // Der Typ verschwindet, waehrend die Seite offen ist (Zuruecksetzen, Import, Loeschen auf
    // einem anderen Weg): zurueck zur Liste, statt ihn beim Speichern neu anzulegen. Nur wenn die
    // Konfiguration GELESEN ist - ohne sie (laedt noch, nicht lesbar) ist "kein Typ" keine Aussage.
    // Eine Neuanlage hat ihre Vorlage und steht nie in der Konfiguration: dort gilt das nicht.
    val verschwunden = !neu && config != null && definition == null
    val zurueckAktuell by rememberUpdatedState(onZurueck)
    LaunchedEffect(verschwunden) {
        if (verschwunden) {
            Logger.w(LogTags.UI, "Typ-Seite: Typ $definitionId nicht mehr vorhanden -> zurueck zur Liste")
            zurueckAktuell()
        }
    }

    if (definition == null || (!neu && config == null)) {
        // Nichts zu bearbeiten: der Pfeil bleibt, damit niemand auf einer leeren Seite festsitzt.
        Scaffold(topBar = { SimpleBackTopAppBar(title = "", onNavigateBack = onZurueck) }) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues))
        }
        return
    }

    TypSeiteFormular(
        definition = definition,
        config = config,
        neu = neu,
        fundCode = fundCode,
        schreibe = shiftViewModel::updateShiftConfig,
        legeAusCodeAn = shiftViewModel::legeTypAusCodeAn,
        onZurueck = onZurueck
    )
}

/**
 * Die Typ-Seite der festen Zeile „Ohne Kalendereintrag“ (Umbau #191, SPEC E5 und 3.3 Punkt 2, Prototyp
 * `typ.fest`): Erklaerung, die Wahl „Tage ohne Eintrag verhalten sich“ und - bei gewaehltem Typ - „<Name>
 * öffnen“ ([ohneEintragAbschnitt]). Sie ersetzt den Dialog der Etappe 1.
 *
 * KEIN ENTWURF, KEIN „Speichern“ (Festlegung des Betreuers, einzige Ausnahme vom Entwurf mit Speichern):
 * ein Tipp auf eine Wahl ist eine abgeschlossene Handlung und schreibt sofort ueber
 * `ShiftViewModel.waehleOhneEintrag()`. Deshalb gibt es auch keinen `BackHandler` hier - Zurueck gehoert
 * dem in `MainScreen` und fuehrt ueber `navigateBackFrom` in die Typ-Liste. In Etappe 2 hat die feste Seite
 * weder Tagesleiste noch Wecker-Abschnitt: „Eigene Einstellungen“ heisst kein Wecker, „Wie <Typ>“ verweist
 * auf den Typ.
 *
 * Ein Typ, der verschwindet, waehrend die Seite offen ist, muss hier nichts behandeln: die Wahl liest
 * [OhneEintragWahl.gewaehlterTyp], ein Verweis ins Leere ist „Eigene Einstellungen“.
 *
 * @param onZurueck verlaesst die Seite zur Typ-Liste (`navigateBackFrom(state)`).
 * @param onTypOeffnen oeffnet die Typ-Seite des gewaehlten Typs (dieselbe wie ein Tipp in der Liste).
 */
@Composable
fun OhneEintragSeite(
    shiftViewModel: ShiftViewModel,
    onZurueck: () -> Unit,
    onTypOeffnen: (typId: String) -> Unit
) {
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    val config = shiftState.currentShiftConfig

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = OhneEintragTexte.TITEL,
                onNavigateBack = onZurueck,
                hilfe = HilfeThema.ANLEITUNG_SCHICHTEN
            )
        }
    ) { paddingValues ->
        // Die Wahl braucht die gelesene Konfiguration: ohne sie (laedt noch, nicht lesbar) steht nur der
        // Pfeil da, damit niemand festsitzt - eine Wahl auf einem Platzhalter wuerde nichts schreiben.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            contentPadding = PaddingValues(vertical = SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            if (config != null) {
                ohneEintragAbschnitt(
                    config = config,
                    onWaehle = shiftViewModel::waehleOhneEintrag,
                    onTypOeffnen = { typ -> onTypOeffnen(typ.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypSeiteFormular(
    definition: ShiftDefinition,
    config: ShiftConfig?,
    neu: Boolean,
    fundCode: String?,
    schreibe: (ShiftConfig) -> Unit,
    legeAusCodeAn: (String, ShiftDefinition) -> Unit,
    onZurueck: () -> Unit
) {
    // Der Ausgangsstand wird beim Oeffnen eingefroren und reist mit: jede Config-Emission (auch
    // die eigene nach dem Speichern, auch fremde Schreiber) liefert ein neues ShiftDefinition-
    // Objekt und darf den Entwurf nicht zuruecksetzen - deshalb KEIN Schluessel auf `definition`.
    val ausgang = rememberSaveable(saver = EntwurfSaver) { TypEntwurf.aus(definition) }
    var entwurf by rememberSaveable(stateSaver = EntwurfSaver) { mutableStateOf(ausgang) }
    var rueckfrageOffen by rememberSaveable { mutableStateOf(false) }
    // Das „+“-Feld der Karte „Typ“ und die offene Kategorie-Wahl gehoeren der Seite, nicht der Karte:
    // sie ueberstehen so Drehen, Dunkelmodus und das Scrollen der Liste (die Karte ist ein Listenelement).
    var neuesMuster by rememberSaveable { mutableStateOf("") }
    var kategorieWahlOffen by rememberSaveable { mutableStateOf(false) }
    val speicherung = remember { TypSeiteSpeicherung(schreibe) }
    val aendere: ((TypEntwurf) -> TypEntwurf) -> Unit = { aenderung -> entwurf = aenderung(entwurf) }

    // Gespeichert wird der Entwurf MIT dem Muster, das im „+“-Feld steht und noch nicht hinzugefuegt ist:
    // getippter Text ginge sonst beim Speichern stumm verloren. Ein Duplikat oder ein leeres Feld
    // aendert nichts (TypAbschnittLogik.musterHinzufuegen).
    val kandidat = TypAbschnittLogik.mitEingabe(entwurf, neuesMuster)
    val geaendert = TypSeiteEntwurf.hatAenderungen(ausgang, kandidat)
    val zurueck: () -> Unit = {
        if (geaendert) rueckfrageOffen = true else onZurueck()
    }

    // Gate, kein Ziel: siehe KDoc. Ein offener AlertDialog frisst Zurueck selbst (eigenes Fenster).
    BackHandler(enabled = geaendert) { rueckfrageOffen = true }

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = if (neu) TypSeiteTexte.NEUER_TYP_TITEL else definition.name,
                onNavigateBack = zurueck,
                hilfe = HilfeThema.ANLEITUNG_SCHICHTEN
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = SpacingConstants.PADDING_SCREEN_HORIZONTAL)
        ) {
            LazyColumn(
                // weight(1f) ist Pflicht, sonst schiebt die Liste den Speichern-Knopf aus dem
                // Bildschirm (ui-texte-und-layout.md).
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = SpacingConstants.SPACING_LARGE),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
            ) {
                if (neu && fundCode != null) {
                    item(key = "banner") { NeuAnlageBanner(fundCode, entwurf.kategorie) }
                }
                item(key = "typ") {
                    TypAbschnitt(
                        entwurf = entwurf,
                        // Eine Neuanlage hat keinen alten Namen, den ein Nachzug umstellen muesste.
                        bestehenderName = if (neu) null else definition.name,
                        neuesMuster = neuesMuster,
                        onNeuesMuster = { neuesMuster = it },
                        onKategorieAendern = { kategorieWahlOffen = true },
                        aendere = aendere
                    )
                }
                // Sonderfall-Kasten (SPEC 3.3 Punkt 4) VOR der Karte „Wecker“, wie im Prototyp. Bei Krank und
                // Ignorieren ersetzt er die Karte: er traegt dort den Hinweis, was der Typ stattdessen tut.
                val sonderfall = ShiftDefinitionTexte.sonderfall(
                    kategorie = entwurf.kategorie,
                    code = TypAbschnittLogik.sichtbareMuster(entwurf).firstOrNull(),
                    erkannt = entwurf.isEnabled
                )
                if (sonderfall != null) {
                    item(key = "sonderfall") { SonderfallKasten(entwurf.kategorie, sonderfall) }
                }
                if (WeckerAbschnittLogik.zeigtWeckerKarte(entwurf.kategorie)) {
                    item(key = "wecker") { WeckerAbschnitt(entwurf = entwurf, aendere = aendere) }
                }
            }

            Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))

            // KnopfZeile + CompactButton wie im Dialog (320 dp mit Schrift 1,3: Wortbruch bei Row + weight).
            KnopfZeile(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SpacingConstants.SPACING_LARGE),
                abstand = SpacingConstants.SPACING_SMALL
            ) {
                CompactButton(
                    text = if (neu) TypSeiteTexte.ERSTELLEN else TypSeiteTexte.SPEICHERN,
                    onClick = {
                        val ergebnis = if (neu) {
                            speicherung.erstelle(config, definition, fundCode, kandidat, legeAusCodeAn)
                        } else {
                            speicherung.speichere(config, definition.id, kandidat)
                        }
                        when (ergebnis) {
                            SpeicherErgebnis.GESCHRIEBEN -> {
                                Logger.d(LogTags.UI, "Typ-Seite: Typ ${definition.id} ${if (neu) "erstellt" else "gespeichert"} -> zurueck zur Liste")
                                onZurueck()
                            }
                            // Schon geschrieben: die Seite ist unterwegs weg, nichts zu tun.
                            SpeicherErgebnis.SCHON_GESCHRIEBEN -> Unit
                            else -> Logger.w(LogTags.UI, "Typ-Seite: Speichern von ${definition.id} abgelehnt ($ergebnis)")
                        }
                    },
                    // Nichts zu speichern, solange nichts geaendert ist; das verborgene Zeitfeld
                    // sperrt nicht grundlos (#142, steckt in `speicherbar`). Eine Neuanlage braucht
                    // keine Aenderung: die Vorlage aus dem Fund ist schon ein gueltiger Typ.
                    enabled = (neu || geaendert) && kandidat.speicherbar
                )
            }
        }
    }

    if (kategorieWahlOffen) {
        KategorieWahlDialog(
            // Bei einer Neuanlage ist `definition` die Vorlage (beim „+“ ohne Namen): es zaehlt, was getippt ist.
            name = if (neu) entwurf.name.trim() else definition.name,
            aktuell = entwurf.kategorie,
            onWahl = { neu ->
                aendere { TypAbschnittLogik.kategorieWechseln(it, neu) }
                kategorieWahlOffen = false
            },
            onDismiss = { kategorieWahlOffen = false }
        )
    }

    if (rueckfrageOffen) {
        AlertDialog(
            onDismissRequest = { rueckfrageOffen = false },
            title = { Text(if (neu) TypSeiteTexte.NEUER_TYP_VERWERFEN_TITEL else TypSeiteTexte.VERWERFEN_TITEL) },
            text = { Text(if (neu) TypSeiteTexte.neuerTypVerwerfenText(fundCode) else TypSeiteTexte.VERWERFEN_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        rueckfrageOffen = false
                        Logger.d(LogTags.UI, "Typ-Seite: ${if (neu) "neuer Typ" else "Aenderungen an"} ${definition.id} verworfen")
                        onZurueck()
                    }
                ) {
                    Text(TypSeiteTexte.VERWERFEN)
                }
            },
            dismissButton = {
                TextButton(onClick = { rueckfrageOffen = false }) {
                    Text(TypSeiteTexte.WEITER_BEARBEITEN)
                }
            }
        )
    }
}

/**
 * Der Banner nach dem Einordnen (Prototyp `.banner`, SPEC 3.3 Punkt 1): amber wie der Ignorieren-Kasten,
 * dessen Farben er teilt. [kategorie] ist die gerade gewaehlte - wechselt der Nutzer sie auf der Seite,
 * bleibt der Satz wahr.
 */
@Composable
private fun NeuAnlageBanner(code: String, kategorie: ShiftKategorie) {
    val dunkel = isSystemInDarkTheme()
    val paar = tonPaar(StatusTon.WARNUNG, dunkel)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = paar.flaeche
    ) {
        Text(
            text = TypSeiteTexte.banner(code, kategorie),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dunkel) paar.text else SCHRIFT_BANNER_HELL,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}
