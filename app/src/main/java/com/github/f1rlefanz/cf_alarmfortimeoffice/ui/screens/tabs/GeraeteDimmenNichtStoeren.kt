package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAccessibilityService
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DndViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.GeraeteDimmenViewModel
import kotlin.math.roundToInt

/**
 * Die Geraete-Einstellungen von „Bildschirm dimmen“ und „Nicht stören“ unter „Einstellungen“
 * (#225, SPEC 3.6): was fuer das ganze Geraet gilt. WANN gedimmt und stummgeschaltet wird, steht am
 * Schichttyp.
 */

/** Die Texte dieser beiden Karten - reine Funktionen, damit sie pruefbar sind. */
internal object GeraeteTexte {

    const val GILT_FUER_ALLE = "gilt für alle Typen"

    /**
     * Die Kurzzeile unter „Wer trotzdem durchkommt“. ACHTUNG Polung: `block*` an heisst STUMM, die
     * Gruppe kommt also NICHT durch. [DndPrefs.Policy.allowRepeatCallers] an heisst, sie kommt
     * durch - das zaehlt aber nur, solange Anrufe blockiert sind; sonst kommen ohnehin alle durch.
     * Der eigene Wecker klingelt immer, deshalb steht er auch bei blockierten fremden Weckern da.
     */
    fun werDurchkommt(policy: DndPrefs.Policy): String {
        val gruppen = buildList {
            if (!policy.blockCalls) add("Anrufe")
            else if (policy.allowRepeatCallers) add("Wiederholte Anrufer")
            if (!policy.blockMessages) add("Nachrichten")
            if (!policy.blockConversations) add("Gespräche")
            if (!policy.blockReminders) add("Erinnerungen")
            if (!policy.blockEvents) add("Termine")
            if (!policy.blockSystem) add("System-Töne")
            add(if (policy.blockAlarms) "dein Wecker" else "Wecker")
            if (!policy.blockMedia) add("Medien")
        }
        return "${gruppen.joinToString(", ")} – $GILT_FUER_ALLE"
    }

    fun bedienungshilfe(lage: DimDiagnostik.DienstLage): String = when (lage) {
        DimDiagnostik.DienstLage.VERBUNDEN -> "Erlaubt – ohne sie kann die App nicht dimmen"
        DimDiagnostik.DienstLage.AUSGESCHALTET -> "Nicht erlaubt – ohne sie kann die App nicht dimmen"
        // Mit Handlungsrichtung (Hergang bei DimDiagnostik.DienstLage): der Schalter steht schon an.
        DimDiagnostik.DienstLage.EINGESCHALTET_NICHT_VERBUNDEN ->
            "Eingeschaltet, aber nicht verbunden – in den Bedienungshilfen aus- und wieder einschalten"
    }

    /** Die Vorschau verspricht nur, was geschieht: ohne verbundenen Dienst erscheint nichts. */
    fun vorschau(lage: DimDiagnostik.DienstLage): String {
        val dauer = "${GeraeteDimmenViewModel.VORSCHAU_SEKUNDEN} Sekunden testen"
        return if (lage == DimDiagnostik.DienstLage.VERBUNDEN) dauer
        else "$dauer – geht erst, wenn die Bedienungshilfe läuft"
    }

    const val FARBWAERME_HINWEIS = "Vorgabe für Typen ohne eigene Farbwärme"

    fun prozent(wert: Int): String = "$wert %"

    fun zugriff(unterstuetzt: Boolean, erteilt: Boolean, nichtVerfuegbar: String): String = when {
        !unterstuetzt -> nichtVerfuegbar
        erteilt -> "Erlaubt"
        else -> "Nicht erlaubt – ohne ihn bleibt „Nicht stören“ aus"
    }
}

/** Karte „Bildschirm dimmen“: Bedienungshilfe, Farbwärme, Vorschau. */
@Composable
internal fun DimmenGeraeteKarte(viewModel: GeraeteDimmenViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val farbwaerme by viewModel.farbwaerme.collectAsStateWithLifecycle()

    var lage by remember { mutableStateOf(DimAccessibilityService.lage(context)) }
    BeiRueckkehr { lage = DimAccessibilityService.lage(context) }
    val offenlegung = rememberSaveable { mutableStateOf(false) }
    val verbunden = lage == DimDiagnostik.DienstLage.VERBUNDEN

    BedienungshilfeOffenlegung(
        offen = offenlegung,
        eingeschaltetNichtVerbunden = lage == DimDiagnostik.DienstLage.EINGESCHALTET_NICHT_VERBUNDEN
    )

    GeraeteKarte("Bildschirm dimmen") {
        GeraeteZeile(
            titel = "Bedienungshilfe",
            text = GeraeteTexte.bedienungshilfe(lage),
            ok = verbunden,
            // Kein Direktsprung: der Weg fuehrt ueber die Offenlegung (Play-Pflicht).
            onClick = if (verbunden) null else ({ offenlegung.value = true })
        )
        HorizontalDivider()
        FarbwaermeZeile(farbwaerme, viewModel::setzeFarbwaerme)
        HorizontalDivider()
        GeraeteZeile(
            titel = "Vorschau",
            text = GeraeteTexte.vorschau(lage),
            onClick = if (verbunden) ({ viewModel.zeigeVorschau(); Unit }) else null
        )
    }
}

/**
 * Wert rechts, Regler darunter. Der Regler schreibt erst beim Loslassen - einmal, ohne
 * Entprellung ([GeraeteDimmenViewModel.setzeFarbwaerme]). Solange noch nicht gelesen ([gespeichert]
 * `null`), steht weder ein Wert noch ein Regler da.
 */
@Composable
private fun FarbwaermeZeile(gespeichert: Int?, onFertig: (Int) -> Unit) {
    // Der gespeicherte Wert loest den gezogenen ab, sobald er ankommt.
    var gezogen by remember(gespeichert) { mutableStateOf<Int?>(null) }
    val wert = gezogen ?: gespeichert
    val aktuellFertig by rememberUpdatedState(onFertig)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM)
    ) {
        ZeilenInhalt(
            titel = "Farbwärme",
            text = GeraeteTexte.FARBWAERME_HINWEIS,
            wert = wert?.let(GeraeteTexte::prozent)
        )
        if (wert != null) {
            Slider(
                value = wert.toFloat(),
                onValueChange = { gezogen = it.roundToInt() },
                onValueChangeFinished = { gezogen?.let(aktuellFertig) },
                valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat(),
                modifier = Modifier.semantics { contentDescription = "Farbwärme" }
            )
        }
    }
}

/** Karte „Nicht stören“: Zugriff und „Wer trotzdem durchkommt“. */
@Composable
internal fun NichtStoerenGeraeteKarte(
    onWerDurchkommt: () -> Unit,
    viewModel: DndViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val unterstuetzt = remember { DndPermissionHelper.isFeatureSupported() }
    var erteilt by remember { mutableStateOf(unterstuetzt && DndPermissionHelper.isGranted(context)) }
    BeiRueckkehr { if (unterstuetzt) erteilt = DndPermissionHelper.isGranted(context) }

    GeraeteKarte("Nicht stören") {
        GeraeteZeile(
            titel = "Zugriff auf „Nicht stören“",
            text = GeraeteTexte.zugriff(unterstuetzt, erteilt, stringResource(R.string.dnd_unsupported)),
            ok = erteilt,
            onClick = if (unterstuetzt && !erteilt) ({ DndPermissionHelper.requestAccess(context) }) else null
        )
        HorizontalDivider()
        GeraeteZeile(
            titel = "Wer trotzdem durchkommt",
            // Vor dem ersten Lesen keine Aufzaehlung: die Vorgabe waere eine erfundene Auskunft.
            text = if (state.gelesen) GeraeteTexte.werDurchkommt(state.policy)
            else GeraeteTexte.GILT_FUER_ALLE.replaceFirstChar { it.uppercase() },
            onClick = onWerDurchkommt
        )
    }
}

/** Eine Karte mit Abschnitts-Titel; die Zeilen darin trennt der Aufrufer. */
@Composable
internal fun GeraeteKarte(titel: String, zeilen: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                titel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(
                        start = SpacingConstants.SPACING_LARGE,
                        end = SpacingConstants.SPACING_LARGE,
                        top = SpacingConstants.SPACING_LARGE,
                        bottom = SpacingConstants.SPACING_SMALL
                    )
                    .semantics { heading() }
            )
            zeilen()
        }
    }
}

/**
 * Eine Zeile: Titel und Unterzeile links, rechts „OK“, wenn [ok]. Mit [onClick] ist die ganze
 * Zeile das Ziel (mindestens 48 dp); ohne ist sie reine Auskunft.
 */
@Composable
internal fun GeraeteZeile(titel: String, text: String, ok: Boolean = false, onClick: (() -> Unit)? = null) {
    val ziel = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(ziel)
            .heightIn(min = 60.dp)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM),
        verticalArrangement = Arrangement.Center
    ) {
        ZeilenInhalt(titel = titel, text = text, wert = if (ok) "OK" else null, wertGruen = ok)
    }
}

@Composable
private fun ZeilenInhalt(titel: String, text: String, wert: String?, wertGruen: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (wert != null) {
            Text(
                wert,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (wertGruen) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Liest Berechtigungen neu, wenn der Nutzer aus den Systemeinstellungen zurueckkommt. */
@Composable
private fun BeiRueckkehr(aktion: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val aktuell by rememberUpdatedState(aktion)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) aktuell()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
