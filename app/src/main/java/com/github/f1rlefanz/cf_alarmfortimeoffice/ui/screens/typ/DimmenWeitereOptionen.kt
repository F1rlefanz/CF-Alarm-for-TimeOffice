package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.ZeitKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.pickTime
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.tageslichtEreignisName
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.BeispielGrund
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NachlaufBeispiel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeitereOptionenStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.nachlaufBeispiel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Das Blatt „Weitere Optionen“ der Karte „Bildschirm dimmen“ (Umbau Etappe 3, #224, SPEC E6 und 3.3 Punkt 6):
 * die Farbwaerme des Typs, je Fenster „Dimmen nach dem Wecker“ samt „Nachlauf bis es hell ist“ und fuer Fenster,
 * die nicht in „Wann“ passen, der Anker-Editor. Anker-Editor, Nachlauf und Tageslicht-Beispiel sind aus dem
 * Regel-Editor hierher umgezogen; der ruft sie bis zu seiner Entfernung (#226) von hier auf.
 */

// ── Anker-Editor: Listen und Feld-Zuordnung ──────────────────────────────────────────────────

/** Welches Eingabefeld ein Fenster-Rand braucht - eine Uhrzeit oder einen Minuten-Offset. */
internal enum class AnkerFeld { UHRZEIT, OFFSET }

/**
 * Die Anker, die der Anker-Editor am START anbietet.
 *
 * WARUM [DimAnchor.ALARM] HIER STEHEN MUSS: Seit dem Ein-Modell-Umbau ist ein Fenster
 * „Weckzeit −60 → Weckzeit" der einzige Weg zu einer Einschlafhilfe vor dem Dienst - die frühere
 * Wellness-Quelle gibt es nicht mehr. Genau das versprechen der Erklärtext im Dimmer-Tab
 * (`dimmer_rules_explain`) und die Modellmigration, die migrierten Nutzern ein solches Fenster
 * anlegt. Fehlte der Knopf, gäbe es die einzige Fähigkeit, die die entfallene Quelle ersetzen
 * soll, ohne Bedienelement - und ein migriertes Fenster wäre im Editor nicht einmal darstellbar.
 *
 * [DimAnchor.ALARM_SONST_CLOCK] fehlt bewusst: er ist ein reiner ENDE-Anker (siehe sein KDoc).
 */
internal val START_ANKER = listOf(DimAnchor.CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END)

/** Die Anker, die der Editor am ENDE anbietet - hier sind alle vier sinnvoll. */
internal val ENDE_ANKER =
    listOf(DimAnchor.CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END, DimAnchor.ALARM_SONST_CLOCK)

/**
 * Das Eingabefeld zu einem START-Anker.
 *
 * DIE ZUORDNUNG IST EINE ZUSICHERUNG, KEIN LAYOUT-DETAIL: Bekäme ein weckzeit-relativer Start ein
 * UHRZEIT-Feld, zeigte der Editor den unbeteiligten Feld-Default (20:00) statt des echten Ankers,
 * und ein einziger Tipp darauf schriebe den Anker dauerhaft auf [DimAnchor.CLOCK] um - aus „zwei
 * Stunden vor dem Aufstehen" würde „jede Nacht ab 20:00", vor einem Spätdienst also ein Fenster
 * von 16,5 Stunden.
 */
internal fun feldFuerStartAnker(anker: DimAnchor): AnkerFeld = when (anker) {
    DimAnchor.ALARM, DimAnchor.SHIFT_END -> AnkerFeld.OFFSET
    // ALARM_SONST_CLOCK ist am Start wie CLOCK (siehe DimWindowResolver.resolveShiftWindow) -
    // eingeschleust werden kann er nur aus Daten, angeboten wird er hier nicht.
    DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK -> AnkerFeld.UHRZEIT
}

// ── Die Rechnung des Blatts, ohne Compose ───────────────────────────────────────────────────

/** Was das Blatt zu „Dimmen nach dem Wecker“ eines darstellbaren Fensters sagt bzw. anbietet. */
internal enum class NachlaufLage {
    /** Das Fenster endet „Weckzeit, spaetestens“: der Nachlauf wirkt. */
    WIRKT,

    /** Wie [WIRKT], aber der Typ stellt selbst keinen Wecker - es braucht einen anderen in diesem Fenster. */
    WIRKT_NUR_MIT_FREMDEM_WECKER,

    /** Die Karte hat den Schalter „Schon beim Wecker enden“, und er ist aus. */
    BEIM_WECKER_AUS,

    /** Das Fenster endet nicht am Wecker, und die Karte hat dafuer keinen Schalter. */
    KEIN_NACHLAUF
}

/**
 * Jede Aenderung ist eine Umformung des Dimm-Teils im Entwurf der Typ-Seite; geschrieben wird erst mit
 * „Speichern“. Fenster werden nur ueber `copy` geaendert, nie neu gebaut: was das Blatt nicht zeigt, bleibt.
 */
internal object WeitereOptionenLogik {

    /** Die Fenster ohne „Wann“ (Stelle in der Liste) - fuer sie zeigt das Blatt den Anker-Editor. */
    fun ohneForm(dimmen: DimmenEntwurf): Set<Int> = dimmen.fenster.indices
        .filter { DimFensterAbbildung.deute(dimmen.fenster[it]) is DimFensterAnsicht.NichtDarstellbar }
        .toSet()

    /**
     * Bekommt das Fenster an [index] den Anker-Editor? [beimOeffnen] haelt ihn fest: wird ein Fenster durch eine
     * Wahl darstellbar, verschwindet der Editor nicht mitten im Bearbeiten.
     */
    fun zeigtAnkerEditor(f: TypDimmFenster, index: Int, beimOeffnen: Collection<Int>): Boolean =
        index in beimOeffnen || DimFensterAbbildung.deute(f) is DimFensterAnsicht.NichtDarstellbar

    fun fensterAendern(dimmen: DimmenEntwurf, index: Int, aenderung: (DimWindow) -> DimWindow): DimmenEntwurf =
        DimmenAbschnittLogik.fensterAendern(dimmen, index) { it.copy(fenster = aenderung(it.fenster)) }

    fun startAnkerWaehlen(w: DimWindow, anker: DimAnchor): DimWindow = w.copy(startAnchor = anker)

    /**
     * Die Uhrzeit am Start schreibt den festen Anker mit: ein aus Daten eingeschleustes
     * [DimAnchor.ALARM_SONST_CLOCK] wird beim ersten Anfassen normalisiert.
     */
    fun startUhrzeit(w: DimWindow, minuten: Int): DimWindow =
        w.copy(startAnchor = DimAnchor.CLOCK, startClockMinutes = minuten)

    /** Geklemmt wie im Resolver, damit das Feld nie einen Wert zeigt, der anders wirkt als angezeigt. */
    fun nachlaufSetzen(w: DimWindow, minuten: Int): DimWindow =
        w.copy(weckzeitNachlaufMinuten = minuten.coerceIn(0, DimWindowResolver.NACHLAUF_MAX_MINUTEN))

    /**
     * „Dimmen nach dem Wecker“: bis zu drei Ziffern, geklemmt. Liefert Feldtext und zu uebernehmenden Wert
     * (`null` = leeres Feld, nichts uebernehmen) - oder `null`, wenn die Eingabe nicht angenommen wird.
     */
    fun minutenEingabe(eingabe: String): Pair<String, Int?>? {
        if (eingabe.isEmpty()) return "" to null
        if (eingabe.length > 3 || !eingabe.all { it in '0'..'9' }) return null
        val wert = eingabe.toInt().coerceAtMost(DimWindowResolver.NACHLAUF_MAX_MINUTEN)
        return wert.toString() to wert
    }

    fun nachlaufLage(f: TypDimmFenster, weckerAn: Boolean): NachlaufLage {
        if (f.fenster.endAnchor == DimAnchor.ALARM_SONST_CLOCK) {
            return if (weckerAn) NachlaufLage.WIRKT else NachlaufLage.WIRKT_NUR_MIT_FREMDEM_WECKER
        }
        val form = (DimFensterAbbildung.deute(f) as? DimFensterAnsicht.Darstellbar)?.form
        return if (form != null && DimFensterAbbildung.zeigeBeimWecker(form.wann, weckerAn)) {
            NachlaufLage.BEIM_WECKER_AUS
        } else {
            NachlaufLage.KEIN_NACHLAUF
        }
    }

    /** Die Wahl, die das Blatt zeigt: `null` = „wie in den Einstellungen“, sonst der eigene Wert des Typs. */
    fun waerme(dimmen: DimmenEntwurf, gespeichert: Int?): Int? {
        val wahl = dimmen.farbwaerme
        val wert = if (wahl != null) wahl.eigene else gespeichert
        return wert?.coerceIn(0, DimOverlayPrefs.WARMTH_MAX)
    }

    /** Die Wahl aus dem Blatt. Waehlt sie wieder das Gespeicherte, ist am Entwurf nichts geaendert. */
    fun waermeWaehlen(dimmen: DimmenEntwurf, eigene: Int?, gespeichert: Int?): DimmenEntwurf {
        val wahl = FarbwaermeWahl(eigene?.coerceIn(0, DimOverlayPrefs.WARMTH_MAX))
        return dimmen.copy(farbwaerme = wahl.takeUnless { it.eigene == gespeichert })
    }
}

// ── Das Blatt ───────────────────────────────────────────────────────────────────────────

/**
 * Das Blatt als bildschirmfuellender Dialog: eigenes Fenster, Zurueck schliesst zuerst das Blatt, der
 * `BackHandler` in `MainScreen` bleibt unberuehrt. Zustandslos - der Entwurf gehoert der Typ-Seite.
 *
 * @param gespeicherteWaerme die Farbwaerme am GESPEICHERTEN Typ (`null` = wie in den Einstellungen).
 * @param stand Vorgabe-Waerme und Weckzeiten; `null` = noch nicht gelesen.
 * @param tageslicht Ort und Zone; `null` = noch nicht gelesen, dann steht weder Hinweis noch Beispiel da.
 * @param speicherKnopf die Beschriftung des Knopfs der Typ-Seite, der speichert.
 * @param ankerBeimOeffnen siehe [WeitereOptionenLogik.zeigtAnkerEditor].
 */
@Composable
internal fun WeitereOptionenBlatt(
    dimmen: DimmenEntwurf,
    weckerAn: Boolean,
    mitBlocktagen: Boolean,
    gespeicherteWaerme: Int?,
    stand: WeitereOptionenStand?,
    tageslicht: TageslichtStand?,
    speicherKnopf: String,
    ankerBeimOeffnen: Collection<Int>,
    aendere: ((DimmenEntwurf) -> DimmenEntwurf) -> Unit,
    onSchliessen: () -> Unit
) {
    Dialog(
        onDismissRequest = onSchliessen,
        // Ohne `decorFitsSystemWindows` liegen Status- und Navigationsleiste grau abgedunkelt neben dem Blatt;
        // die Raender uebernehmen `Scaffold` und Titelleiste.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        SystemleistenPassendZumThema()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = { SimpleBackTopAppBar(title = WeitereOptionenTexte.TITEL, onNavigateBack = onSchliessen) }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                    contentPadding = PaddingValues(vertical = SpacingConstants.SPACING_LARGE),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
                ) {
                    item(key = "hinweis") { Nebentext(WeitereOptionenTexte.speicherHinweis(speicherKnopf)) }
                    item(key = "waerme") {
                        WaermeKarte(
                            eigene = WeitereOptionenLogik.waerme(dimmen, gespeicherteWaerme),
                            vorgabe = stand?.vorgabeWaerme,
                            onWahl = { neu ->
                                aendere { WeitereOptionenLogik.waermeWaehlen(it, neu, gespeicherteWaerme) }
                            }
                        )
                    }
                    if (dimmen.fenster.isEmpty()) {
                        item(key = "keinfenster") { Nebentext(WeitereOptionenTexte.KEIN_FENSTER) }
                    }
                    dimmen.fenster.forEachIndexed { index, fenster ->
                        item(key = "fenster-$index") {
                            val aendereFenster: ((TypDimmFenster) -> TypDimmFenster) -> Unit = { aenderung ->
                                aendere { DimmenAbschnittLogik.fensterAendern(it, index, aenderung) }
                            }
                            val onWindow: ((DimWindow) -> DimWindow) -> Unit = { aenderung ->
                                aendere { WeitereOptionenLogik.fensterAendern(it, index, aenderung) }
                            }
                            Karte(DimFensterAbbildung.titel(index + 1, fenster, weckerAn)) {
                                val nachlauf: @Composable () -> Unit = {
                                    NachlaufTeil(fenster.fenster, weckerAn, stand, tageslicht, onWindow)
                                }
                                if (WeitereOptionenLogik.zeigtAnkerEditor(fenster, index, ankerBeimOeffnen)) {
                                    Nebentext(WeitereOptionenTexte.OHNE_WANN)
                                    AnkerEditor(window = fenster.fenster, onChange = onWindow, nachDerWeckzeit = nachlauf)
                                    ProzentZeile(prozent = fenster.staerke, an = true) { neu ->
                                        aendereFenster { it.copy(staerke = neu) }
                                    }
                                    if (mitBlocktagen) {
                                        BlockTageWahl(gewaehlt = fenster.fenster.blockPositionen, an = true) { chip ->
                                            onWindow {
                                                it.copy(blockPositionen = DimmenAbschnittLogik.blockTageWaehlen(it.blockPositionen, chip))
                                            }
                                        }
                                    }
                                } else {
                                    when (WeitereOptionenLogik.nachlaufLage(fenster, weckerAn)) {
                                        NachlaufLage.WIRKT -> nachlauf()
                                        NachlaufLage.WIRKT_NUR_MIT_FREMDEM_WECKER -> {
                                            Nebentext(WeitereOptionenTexte.OHNE_EIGENEN_WECKER)
                                            nachlauf()
                                        }
                                        NachlaufLage.BEIM_WECKER_AUS -> Nebentext(WeitereOptionenTexte.BEIM_WECKER_AUS)
                                        NachlaufLage.KEIN_NACHLAUF -> Nebentext(WeitereOptionenTexte.KEIN_NACHLAUF)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Die Symbole der Systemleisten im Dialogfenster: dunkel auf hellem Grund, hell im Dunkelmodus. */
@Composable
private fun SystemleistenPassendZumThema() {
    val view = LocalView.current
    val hell = !isSystemInDarkTheme()
    SideEffect {
        val fenster = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(fenster, view).apply {
            isAppearanceLightStatusBars = hell
            isAppearanceLightNavigationBars = hell
        }
    }
}

@Composable
private fun Karte(titel: String, inhalt: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                titel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() }
            )
            inhalt()
        }
    }
}

@Composable
private fun Nebentext(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Die Farbwaerme des Typs: genau zwei Wahlen. [eigene] `null` = „wie in den Einstellungen“. */
@Composable
private fun WaermeKarte(eigene: Int?, vorgabe: Int?, onWahl: (Int?) -> Unit) {
    Karte(WeitereOptionenTexte.WAERME_TITEL) {
        Column(modifier = Modifier.selectableGroup()) {
            WahlZeile(
                titel = WeitereOptionenTexte.WAERME_WIE_EINSTELLUNGEN,
                unterzeile = vorgabe?.let(WeitereOptionenTexte::waermeZurzeit),
                gewaehlt = eigene == null
            ) { onWahl(null) }
            WahlZeile(
                titel = WeitereOptionenTexte.WAERME_EIGENE,
                unterzeile = eigene?.let(WeitereOptionenTexte::prozent),
                gewaehlt = eigene != null
            ) {
                // Der eigene Wert beginnt bei dem, was gerade wirkt.
                if (eigene == null) onWahl(vorgabe ?: DimOverlayPrefs.DEFAULT_WARMTH)
            }
        }
        if (eigene != null) {
            Slider(
                value = eigene.toFloat(),
                onValueChange = { onWahl(it.toInt()) },
                valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat()
            )
        }
    }
}

/** Zeile mit Auswahlpunkt: die ganze Zeile ist das Ziel, mindestens 48 dp. */
@Composable
private fun WahlZeile(titel: String, unterzeile: String?, gewaehlt: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = gewaehlt, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = gewaehlt, onClick = null)
        Column {
            Text(titel, style = MaterialTheme.typography.bodyLarge)
            if (unterzeile != null) Nebentext(unterzeile)
        }
    }
}

/** „Dimmen nach dem Wecker“ und darunter - nur mit Nachlauf - „Nachlauf bis es hell ist“ samt Beispiel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NachlaufTeil(
    window: DimWindow,
    weckerAn: Boolean,
    stand: WeitereOptionenStand?,
    tageslicht: TageslichtStand?,
    onChange: ((DimWindow) -> DimWindow) -> Unit
) {
    MinutenZeile(minuten = window.weckzeitNachlaufMinuten) { neu ->
        onChange { WeitereOptionenLogik.nachlaufSetzen(it, neu) }
    }
    // Ohne Nachlauf gibt es nichts zu kuerzen; eine fruehere Wahl bleibt im Fenster stehen und wirkt nicht.
    if (window.weckzeitNachlaufMinuten <= 0) return

    Text(WeitereOptionenTexte.NACHLAUF_HELL_TITEL, style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HellAb.entries.forEach { option ->
            FilterChip(
                selected = window.nachlaufBisHell == option,
                onClick = { onChange { it.copy(nachlaufBisHell = option) } },
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                HellAb.IMMER -> R.string.tageslicht_wahl_immer
                                HellAb.DAEMMERUNG -> R.string.tageslicht_dimmer_bis_daemmerung
                                HellAb.SONNENAUFGANG -> R.string.tageslicht_dimmer_bis_sonnenaufgang
                            }
                        )
                    )
                },
                colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = window.nachlaufBisHell == option,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    selectedBorderWidth = 2.dp
                )
            )
        }
    }
    if (window.nachlaufBisHell == HellAb.IMMER || tageslicht == null) return

    if (tageslicht.ort == null) {
        Text(
            WeitereOptionenTexte.ohneOrt(
                stringResource(R.string.tageslicht_ohne_ort_dimmer),
                stringResource(R.string.tageslicht_ort_titel)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Nebentext(stringResource(R.string.tageslicht_dimmer_hinweis))
    // Ein Typ ohne Wecker bekommt das Beispiel auch: es nennt den naechsten Wecker, den das Fenster faengt.
    val beispiel = remember(window, weckerAn, stand, tageslicht) {
        stand?.let {
            nachlaufBeispiel(window, tageslicht::hellZeitpunkt, it.weckzeiten, System.currentTimeMillis(), tageslicht.zone)
        }
    }
    nachlaufBeispielText(beispiel, window.nachlaufBisHell)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Die Minuten nach dem Wecken; ein leeres Feld uebernimmt nichts und fuellt sich beim Verlassen wieder. */
@Composable
private fun MinutenZeile(minuten: Int, onMinuten: (Int) -> Unit) {
    var text by remember(minuten) { mutableStateOf(minuten.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(WeitereOptionenTexte.NACH_DEM_WECKER, style = MaterialTheme.typography.bodyLarge)
            Nebentext(WeitereOptionenTexte.NACH_DEM_WECKER_HINWEIS)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { eingabe ->
                WeitereOptionenLogik.minutenEingabe(eingabe)?.let { (neuerText, wert) ->
                    text = neuerText
                    if (wert != null) onMinuten(wert)
                }
            },
            modifier = Modifier
                .width(MINUTENFELD_BREITE)
                .onFocusChanged { if (!it.isFocused && text.isEmpty()) text = minuten.toString() },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

// ── Anker-Editor (umgezogen aus dem Regel-Editor) ──────────────────────────────────────────

/**
 * Start und Ende eines Fensters ueber seine Anker, mit dem Wert dazu. [nachDerWeckzeit] steht unter dem Ende
 * „Weckzeit, spaetestens“ - dort haengt der Aufrufer seine Nachlauf-Eingaben an.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnkerEditor(
    window: DimWindow,
    onChange: ((DimWindow) -> DimWindow) -> Unit,
    nachDerWeckzeit: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Start – feste Uhrzeit (Vorabend), relativ zur Weckzeit (Einschlafhilfe) ODER
    // relativ zum Schichtende (ND-Tagschlaf). Siehe [START_ANKER].
    Text(stringResource(R.string.dimmer_window_start), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        START_ANKER.forEach { anker ->
            AnkerKnopf(
                // ALARM_SONST_CLOCK loest am Start wie CLOCK auf - dann ist der
                // CLOCK-Knopf die ehrliche Anzeige. Sonst waere KEIN Knopf markiert.
                gewaehlt = window.startAnchor == anker ||
                    (anker == DimAnchor.CLOCK && window.startAnchor == DimAnchor.ALARM_SONST_CLOCK),
                label = ankerLabel(anker)
            ) { onChange { WeitereOptionenLogik.startAnkerWaehlen(it, anker) } }
        }
    }
    when (feldFuerStartAnker(window.startAnchor)) {
        AnkerFeld.OFFSET -> OffsetField(
            value = window.startOffsetMinutes,
            label = stringResource(
                if (window.startAnchor == DimAnchor.ALARM) R.string.dimmer_start_offset
                else R.string.dimmer_shiftend_offset
            )
        ) { neu -> onChange { it.copy(startOffsetMinutes = neu) } }

        // Der Anker wird hier mitgeschrieben ([WeitereOptionenLogik.startUhrzeit]) - fuer die beiden
        // OFFSET-Anker ist dieser Zweig unerreichbar, sie verlieren ihn also nicht.
        AnkerFeld.UHRZEIT -> UhrzeitFeld(minutes = window.startClockMinutes) {
            pickTime(context, window.startClockMinutes) { neu -> onChange { WeitereOptionenLogik.startUhrzeit(it, neu) } }
        }
    }

    // Ende – feste Uhrzeit, zur Weckzeit, „Weckzeit, spätestens" ODER relativ zum Schichtende
    Text(stringResource(R.string.dimmer_window_end), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ENDE_ANKER.forEach { anker ->
            AnkerKnopf(
                gewaehlt = window.endAnchor == anker,
                label = ankerLabel(anker)
            ) { onChange { it.copy(endAnchor = anker) } }
        }
    }
    val waehleEnde = {
        pickTime(context, window.endClockMinutes) { neu -> onChange { it.copy(endClockMinutes = neu) } }
    }
    when (window.endAnchor) {
        DimAnchor.CLOCK -> UhrzeitFeld(minutes = window.endClockMinutes, onClick = waehleEnde)

        DimAnchor.ALARM -> OffsetField(
            value = window.endOffsetMinutes,
            label = stringResource(R.string.dimmer_end_offset)
        ) { neu -> onChange { it.copy(endOffsetMinutes = neu) } }

        DimAnchor.SHIFT_END -> OffsetField(
            value = window.endOffsetMinutes,
            label = stringResource(R.string.dimmer_shiftend_offset)
        ) { neu -> onChange { it.copy(endOffsetMinutes = neu) } }

        // Derselbe Uhrzeit-Waehler wie bei CLOCK - die Uhrzeit ist hier die SPAETESTE
        // Moeglichkeit, nicht das feste Ende. Ohne den erklaerenden Satz daneben ist der
        // Unterschied zu "Feste Uhrzeit" am Knopf allein nicht zu erkennen, und genau
        // dieses Missverstaendnis war der Ausloeser des Ankers.
        DimAnchor.ALARM_SONST_CLOCK -> {
            UhrzeitFeld(minutes = window.endClockMinutes, onClick = waehleEnde)
            Nebentext(stringResource(R.string.dimmer_end_alarm_sonst_clock_hinweis))
            nachDerWeckzeit()
        }
    }
}

@Composable
private fun ankerLabel(anker: DimAnchor): String = when (anker) {
    DimAnchor.CLOCK -> stringResource(R.string.dimmer_end_clock)
    DimAnchor.ALARM -> stringResource(R.string.dimmer_end_alarm)
    DimAnchor.SHIFT_END -> stringResource(R.string.dimmer_anchor_shiftend)
    DimAnchor.ALARM_SONST_CLOCK -> stringResource(R.string.dimmer_end_alarm_sonst_clock)
}

@Composable
private fun AnkerKnopf(gewaehlt: Boolean, label: String, onClick: () -> Unit) {
    if (gewaehlt) {
        Button(onClick = onClick) { Text(label) }
    } else {
        // Schrift in Textfarbe: in der Vorgabefarbe saehe die nicht gewaehlte Wahl gesperrt aus.
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        ) { Text(label) }
    }
}

/**
 * Beschrifteter Zeit-Picker für „Feste Uhrzeit". Das Label bindet den Wert sichtbar an die Auswahl
 * (analog zum [OffsetField] der anderen Anker) — sonst wirkte der nackte Uhrzeit-Button auf schmalen
 * Displays losgelöst und schien zum darüberliegenden Anker-Knopf zu gehören.
 */
@Composable
private fun UhrzeitFeld(minutes: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Nebentext(stringResource(R.string.dimmer_clock_time))
        // Ein Wert, kein gesperrter Knopf (wie in der Karte „Bildschirm dimmen“).
        ZeitKnopf(
            minuten = minutes,
            onClick = onClick,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

/** Zahlenfeld für einen Minuten-Offset (nach Wecker bzw. nach Schichtende). */
@Composable
internal fun OffsetField(value: Int, label: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

// ── Beispielzeile zum Tageslicht (umgezogen aus dem Regel-Editor) ───────────────────────────

// Locale.GERMAN bleibt zwingend: ohne das Argument haengt der Wochentagsname an der Systemsprache.
private val BEISPIEL_TAG_ZEIT = DateTimeFormatter.ofPattern(DateTimeFormats.DAY_TIME, Locale.GERMAN)
private val BEISPIEL_ZEIT = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY, Locale.GERMAN)

private fun fmt(epoch: Long, format: DateTimeFormatter): String =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(format)

/**
 * "Nächster Wecker Do 15.10. 05:30 → gedimmt bis 06:30 (volle 60 Minuten, Sonnenaufgang ist erst
 * um 07:31)". Die Begruendung entfaellt, wenn die Rechnung keine ehrliche hergibt (siehe
 * [NachlaufBeispiel.grundArt]).
 */
@Composable
internal fun nachlaufBeispielText(b: NachlaufBeispiel?, hellAb: HellAb): String? {
    if (b == null) return null
    val basis = stringResource(
        R.string.tageslicht_dimmer_beispiel,
        fmt(b.weckzeit, BEISPIEL_TAG_ZEIT),
        fmt(b.ende, BEISPIEL_ZEIT)
    )
    val ereignis = tageslichtEreignisName(hellAb)
    val grund = when (b.grundArt) {
        BeispielGrund.NACHLAUF_VOLL -> b.hellZeitpunkt?.let {
            pluralStringResource(R.plurals.tageslicht_dimmer_grund_nachlauf, b.nachlaufMin, b.nachlaufMin, ereignis, fmt(it, BEISPIEL_ZEIT))
        }
        BeispielGrund.HELL -> b.grund?.let {
            stringResource(R.string.tageslicht_dimmer_grund_hell, ereignis, fmt(it.hellZeitpunkt, BEISPIEL_ZEIT))
        }
        BeispielGrund.WECKZEIT -> b.grund?.let {
            stringResource(R.string.tageslicht_dimmer_grund_weckzeit, ereignis, fmt(it.hellZeitpunkt, BEISPIEL_ZEIT))
        }
        null -> null
    }
    return if (grund == null) basis else "$basis ($grund)"
}

/** Breit genug fuer drei Ziffern in Schrift 1,3. */
private val MINUTENFELD_BREITE = 96.dp
