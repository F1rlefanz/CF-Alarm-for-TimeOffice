package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.ZeitKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.pickTime
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.StatusTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.tonPaar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/** Ob die App „Nicht stoeren“ auf diesem Geraet steuern kann. Nur [ERTEILT] macht die Karte bedienbar. */
internal enum class DndZugriff(val bedienbar: Boolean) {
    /** Android < 11: die Funktion gibt es dort nicht. */
    NICHT_UNTERSTUETZT(false),

    /** Die einmalige Freigabe in den Systemeinstellungen fehlt. */
    FEHLT(false),
    ERTEILT(true)
}

/**
 * Die Rechnung des Abschnitts „Nicht stören“, ohne Compose (Umbau Etappe 3, #222). Jede Aenderung ist eine
 * Umformung des „Nicht stoeren“-Teils im Entwurf; geschrieben wird erst mit „Speichern“. Was die Schalter zur
 * Laufzeit tun, steht allein in `DndScheduleUseCase` (`DndTypSchalter`) - hier gibt es keine zweite
 * Fensterlogik. `kategorie == null` steht fuer die feste Zeile „Ohne Kalendereintrag“.
 */
internal object NichtStoerenAbschnittLogik {

    /** Der Abschnitt fehlt, wo die Karte „Wecker“ fehlt: bei Krank (gibt den Tag frei) und Ignorieren. */
    fun zeigtAbschnitt(kategorie: ShiftKategorie): Boolean = WeckerAbschnittLogik.zeigtWeckerKarte(kategorie)

    /** „Endet spätestens um“ wirkt nur bei Rufbereitschaft (`DndTypSchalter.rufCutoffMinuten`). */
    fun zeigtEndetSpaetestens(kategorie: ShiftKategorie?): Boolean = kategorie == ShiftKategorie.RUFBEREITSCHAFT

    /** „Während des Kalendereintrags“ gibt es nicht an der festen Zeile: dort steht nichts im Kalender. */
    fun gibtEintrag(kategorie: ShiftKategorie?): Boolean = kategorie != null

    /** Wie der Schalter „Während des Kalendereintrags“ steht - an der festen Zeile nie an. */
    fun eintragAn(werte: NichtStoerenEntwurf, kategorie: ShiftKategorie?): Boolean =
        gibtEintrag(kategorie) && werte.waehrendEintrag

    fun zugriff(unterstuetzt: Boolean, erteilt: Boolean): DndZugriff = when {
        !unterstuetzt -> DndZugriff.NICHT_UNTERSTUETZT
        !erteilt -> DndZugriff.FEHLT
        else -> DndZugriff.ERTEILT
    }

    /**
     * Steht „Solange gedimmt wird“ an, obwohl der Typ nie dimmt („Dimmen an diesem Tag“ aus oder kein
     * Fenster)? Dann bewirkt der Schalter nichts, und das wird gesagt. [dimmen] `null` = unbekannt.
     */
    fun gedimmtOhneWirkung(werte: NichtStoerenEntwurf, dimmen: DimmenEntwurf?): Boolean =
        werte.solangeGedimmt && dimmen != null && (!dimmen.an || dimmen.fenster.isEmpty())

    /**
     * Die Quellen der Tagesleiste, als waere der „Nicht stoeren“-Teil des Entwurfs schon gespeichert: die Karte
     * rechnet ihn aus der Konfiguration (`TagesleisteKarteLogik.einstellungenAus`, auch fuer die feste Zeile, wenn
     * sie „wie <dieser Typ>“ ist). Der Typ wird wie dort nach dem GESPEICHERTEN Namen gesucht. Unveraendert bleiben
     * die Quellen, wo es nichts zu ersetzen gibt - auch bei der Neuanlage ([gespeicherterName] `null`): der Typ
     * steht noch nicht in der Konfiguration.
     */
    fun leistenQuellen(
        quellen: TagesleisteQuellen?,
        gespeicherterName: String?,
        entwurf: NichtStoerenEntwurf?
    ): TagesleisteQuellen? {
        if (quellen == null || gespeicherterName == null || entwurf == null) return quellen
        val config = (quellen.typen as? Quelle.Bekannt)?.wert ?: return quellen
        val typ = config.definitions.firstOrNull { it.name.equals(gespeicherterName, ignoreCase = true) }
            ?: return quellen
        val definitionen = config.definitions.map {
            if (it.id == typ.id) it.copy(nichtStoeren = entwurf.anwenden(it.nichtStoeren)) else it
        }
        return quellen.copy(typen = Quelle.Bekannt(config.copy(definitions = definitionen)))
    }
}

/**
 * Die Karte „Nicht stören“ der Typ-Seite (SPEC E7 und 3.3 Punkt 7, Prototyp `zeigeTyp`): „Solange gedimmt
 * wird“, „Während des Kalendereintrags“ und bei Rufbereitschaft „Endet spätestens um“ als Zeitwahl. Einen
 * globalen Hauptschalter gibt es nicht. Fehlt der Zugriff auf „Nicht stören“ (oder ist das Android zu alt),
 * sagt die Karte das oben, fuehrt zur Systemfreigabe und sperrt ihre Bedienelemente - die gespeicherten Werte
 * bleiben dabei unberuehrt. Zustandslos bis auf den Zugriff, der dem Geraet gehoert.
 *
 * Nur fuer Typen mit Abschnitt ([NichtStoerenAbschnittLogik.zeigtAbschnitt]); `kategorie == null` ist die feste
 * Zeile: „Während des Kalendereintrags“ steht dort gesperrt da.
 *
 * @param dimmen der Dimm-Teil, dem „Solange gedimmt wird“ folgt - fuer den Hinweis, wenn er nie dimmt.
 */
@Composable
internal fun NichtStoerenAbschnitt(
    werte: NichtStoerenEntwurf,
    kategorie: ShiftKategorie?,
    dimmen: DimmenEntwurf?,
    aendere: ((NichtStoerenEntwurf) -> NichtStoerenEntwurf) -> Unit
) {
    val zugriff = rememberDndZugriff()
    val context = LocalContext.current
    val zeilenRand = Modifier.padding(
        horizontal = SpacingConstants.SPACING_LARGE,
        vertical = SpacingConstants.SPACING_MEDIUM
    )
    // Gesperrte Zeilen sind blass wie in der Karte „Wecker“.
    fun blass(an: Boolean): Modifier = Modifier.alpha(if (an) 1f else AUSGEGRAUT_ALPHA)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                NichtStoerenTexte.TITEL,
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

            if (!zugriff.bedienbar) {
                ZugriffHinweis(
                    zugriff = zugriff,
                    onFreigeben = { DndPermissionHelper.requestAccess(context) },
                    modifier = zeilenRand
                )
            }

            Column(modifier = zeilenRand) {
                Column(modifier = blass(zugriff.bedienbar)) {
                    SwitchRow(
                        title = NichtStoerenTexte.GEDIMMT_TITEL,
                        description = NichtStoerenTexte.GEDIMMT_HINWEIS,
                        checked = werte.solangeGedimmt,
                        onCheckedChange = { neu -> aendere { it.copy(solangeGedimmt = neu) } },
                        enabled = zugriff.bedienbar,
                        titleFontWeight = FontWeight.Normal
                    )
                }
                if (NichtStoerenAbschnittLogik.gedimmtOhneWirkung(werte, dimmen)) {
                    Text(
                        NichtStoerenTexte.GEDIMMT_OHNE_FENSTER,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = SpacingConstants.SPACING_EXTRA_SMALL)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val eintragBedienbar = zugriff.bedienbar && NichtStoerenAbschnittLogik.gibtEintrag(kategorie)
            Column(modifier = zeilenRand.then(blass(eintragBedienbar))) {
                SwitchRow(
                    title = NichtStoerenTexte.EINTRAG_TITEL,
                    description = NichtStoerenTexte.eintragText(kategorie),
                    checked = NichtStoerenAbschnittLogik.eintragAn(werte, kategorie),
                    onCheckedChange = { neu -> aendere { it.copy(waehrendEintrag = neu) } },
                    enabled = eintragBedienbar,
                    titleFontWeight = FontWeight.Normal
                )
            }

            if (NichtStoerenAbschnittLogik.zeigtEndetSpaetestens(kategorie)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EndetSpaetestensZeile(
                    minuten = werte.endetSpaetestensMinuten,
                    an = zugriff.bedienbar,
                    modifier = zeilenRand,
                    onNeu = { neu -> aendere { it.copy(endetSpaetestensMinuten = neu) } }
                )
            }
        }
    }
}

/**
 * Der Zugriff, beim Zurueckkehren in die App neu gelesen (die Freigabe passiert in den Systemeinstellungen) -
 * derselbe Weg wie die DND-Seite.
 */
@Composable
private fun rememberDndZugriff(): DndZugriff {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val unterstuetzt = remember { DndPermissionHelper.isFeatureSupported() }
    var erteilt by remember { mutableStateOf(unterstuetzt && DndPermissionHelper.isGranted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && unterstuetzt) {
                erteilt = DndPermissionHelper.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return NichtStoerenAbschnittLogik.zugriff(unterstuetzt, erteilt)
}

/** Warum die Karte gerade nichts bewirkt - und, wenn es eine Freigabe loest, der Weg dorthin. */
@Composable
private fun ZugriffHinweis(zugriff: DndZugriff, onFreigeben: () -> Unit, modifier: Modifier) {
    val dunkel = isSystemInDarkTheme()
    val paar = tonPaar(StatusTon.WARNUNG, dunkel)
    val schrift = if (dunkel) paar.text else SCHRIFT_BANNER_HELL
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = paar.flaeche,
            contentColor = schrift
        ) {
            Column(
                modifier = Modifier.padding(SpacingConstants.SPACING_MEDIUM),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                if (zugriff == DndZugriff.FEHLT) {
                    Text(
                        stringResource(R.string.dnd_permission_required),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.dnd_permission_hint), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.dnd_unsupported), style = MaterialTheme.typography.bodyMedium)
                }
                Text(NichtStoerenTexte.OHNE_ZUGRIFF, style = MaterialTheme.typography.bodyMedium)
                if (zugriff == DndZugriff.FEHLT) {
                    OutlinedButton(
                        onClick = onFreigeben,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = schrift),
                        border = BorderStroke(1.dp, schrift)
                    ) {
                        Text(stringResource(R.string.dnd_permission_grant))
                    }
                }
            }
        }
    }
}

/** „Endet spätestens um“: Titel und Hinweis links, die Zeitwahl rechts (wie „Bis“ in der Dimm-Karte). */
@Composable
private fun EndetSpaetestensZeile(minuten: Int, an: Boolean, modifier: Modifier, onNeu: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (an) 1f else AUSGEGRAUT_ALPHA)) {
            Text(NichtStoerenTexte.CUTOFF_TITEL, style = MaterialTheme.typography.bodyLarge)
            Text(
                NichtStoerenTexte.CUTOFF_HINWEIS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Mit eigener Flaeche und Textfarbe: mit den Material-Vorgaben saehe der Knopf gesperrt aus (#221).
        ZeitKnopf(
            minuten = minuten,
            onClick = { pickTime(context, minuten, onNeu) },
            enabled = an,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

/** Deckkraft einer gesperrten Zeile, wie in den Karten „Wecker“ und „Bildschirm dimmen“. */
private const val AUSGEGRAUT_ALPHA = 0.6f
