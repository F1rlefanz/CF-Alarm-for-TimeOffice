package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.DimDndEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.SpurStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TAGESLEISTE_MIN
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Tagesleiste
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteEingabe
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.dienstzeitAus
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.dimmenUndNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.licht
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.standardBlockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.tagesleiste
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.BrandRedLightMode
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/** Nutzertexte der Karte „So läuft ein Tag“ (SPEC 3.3 Punkt 3, Prototyp `zeigeTyp`), als Konstanten. */
internal object TagesleisteTexte {
    const val ZEITRAUM = "Von 18 Uhr am Vortag bis 18 Uhr am Folgetag."

    // Der Satz des Prototyps heisst „Ändere unten etwas“. In Etappe 2 ist darunter nur der Wecker
    // einstellbar (Festlegung des Betreuers), also nennt der Satz den Wecker - und steht nur dort, wo
    // die Karte „Wecker“ darunter wirklich steht.
    const val RECHNET_MIT = "Ändere unten den Wecker – die Leiste rechnet sofort mit."
    const val WIRD_GELESEN = "Die Leiste wird gelesen …"
    const val LINK = "Im Bereich „Wecker“ über die ganze Woche ansehen"
    const val BESCHREIBUNG = "Tagesleiste mit den Spuren dieses Tages. Die Ereignisse stehen darunter in der Liste."

    fun titel(name: String): String = "So läuft ein Tag „$name“"

    fun hinweis(kategorie: ShiftKategorie): String =
        if (WeckerAbschnittLogik.zeigtWeckerKarte(kategorie)) "$ZEITRAUM $RECHNET_MIT" else ZEITRAUM

    fun spur(spur: Spur): String = when (spur) {
        Spur.EINTRAG -> "Eintrag"
        Spur.DIMMEN -> "Dimmen"
        Spur.NICHT_STOEREN -> "Nicht stören"
        Spur.LICHT -> "Licht"
        Spur.RUF_ABFRAGE -> "Ruf-Abfrage"
        Spur.WECKER -> "Wecker"
    }

    /** Eine Spur, deren Quelle sich nicht lesen liess, sagt das - sie bleibt nicht still leer. */
    fun nichtLesbar(spuren: List<Spur>): String =
        "Gerade nicht lesbar: ${spuren.joinToString(", ") { spur(it) }}. " +
            "Diese Spuren fehlen hier – das heißt nicht, dass dort nichts geschieht."

    fun block(position: Blockposition): String = when (position) {
        Blockposition.ERSTER -> "Erster Tag im Block"
        Blockposition.MITTLERER -> "Mittendrin"
        Blockposition.LETZTER -> "Letzter"
        Blockposition.EINZELNER -> "Einzeln"
    }
}

/**
 * Was die Karte zeigt: [gelesen] ist `false`, solange der Bestand noch nicht gelesen ist (das ist KEIN
 * Lesefehler und keine leere Leiste), [unbekannt] die Spuren, deren Quelle sich nicht lesen liess.
 */
internal data class TagesleisteAnzeige(
    val gelesen: Boolean,
    val leiste: Tagesleiste,
    val unbekannt: List<Spur>
) {
    companion object {
        val WIRD_GELESEN = TagesleisteAnzeige(gelesen = false, leiste = Tagesleiste.LEER, unbekannt = emptyList())
    }
}

/**
 * Die Rechnung der Karte, ohne Compose (Umbau #197): aus dem Entwurf der Seite und den Quellen
 * (`ShiftViewModel.tagesleisteQuellen`, #196) wird mit den Adaptern aus #193 bis #195 die Leiste. Es
 * gibt hier keine eigene Fenster-, Licht- oder Dienstzeit-Logik.
 */
internal object TagesleisteKarteLogik {

    /** Bei Ignorieren gibt es keine Leiste: ein solcher Eintrag wird uebergangen, es gibt keinen Tag. */
    fun zeigtKarte(kategorie: ShiftKategorie): Boolean = kategorie != ShiftKategorie.IGNORIEREN

    /** Die Chips der Blockposition gibt es nur, wo ein Block mehrerer Tage ueberhaupt vorkommt. */
    fun zeigtBlockwahl(kategorie: ShiftKategorie): Boolean =
        kategorie == ShiftKategorie.DIENST || kategorie == ShiftKategorie.RUFBEREITSCHAFT

    val BLOCKPOSITIONEN: List<Blockposition> = listOf(
        Blockposition.ERSTER, Blockposition.MITTLERER, Blockposition.LETZTER, Blockposition.EINZELNER
    )

    /** Die gewaehlte Blockposition, wo es die Wahl gibt - sonst die Vorgabe der Kategorie. */
    fun wirksameBlockposition(kategorie: ShiftKategorie, gewaehlt: Blockposition?): Blockposition =
        if (zeigtBlockwahl(kategorie) && gewaehlt != null) gewaehlt else standardBlockposition(kategorie)

    /**
     * Die Spuren, die aus gelesenen Quellen nicht zu rechnen sind: [TagesleisteQuellen.stand], dazu
     * „Nicht stören“, sobald die Dimm-Quelle fehlt. Der Adapter braucht ihre `regelwahl` fuer jeden Aufruf
     * (auch fuer „waehrend des Kalendereintrags“); ohne sie liesse sich das Ergebnis nur mit einer zweiten
     * Rechnung bilden. Das trifft nur den Fall, dass die Dimm-Quelle WIRFT - in Produktion degradiert sie
     * im Store und wirft nie (#196, #210).
     */
    fun unbekannteSpuren(quellen: TagesleisteQuellen): List<Spur> = Spur.entries.filter {
        quellen.stand(it) == SpurStand.UNBEKANNT ||
            (it == Spur.NICHT_STOEREN && quellen.dimmer !is Quelle.Bekannt)
    }

    /**
     * Rechnet die Leiste.
     *
     * @param gespeicherterName der Name des Typs, wie er in der Konfiguration steht; `null` bei der
     *   Neuanlage. Dimm-Regeln, Hue-Regeln und Schichtspannen haengen alle am GESPEICHERTEN Namen: mit
     *   dem Entwurfsnamen verschwaenden Dimmen und Licht, sobald jemand ein Zeichen im Namen aendert.
     *   Ein Typ, der noch nicht angelegt ist, hat auch keine Spanne.
     * @param quellen `null` = noch nicht gelesen ([TagesleisteAnzeige.WIRD_GELESEN]).
     */
    fun rechne(
        entwurf: TypEntwurf,
        gespeicherterName: String?,
        quellen: TagesleisteQuellen?,
        blockposition: Blockposition,
        jetztMs: Long,
        zone: ZoneId
    ): TagesleisteAnzeige {
        if (quellen == null) return TagesleisteAnzeige.WIRD_GELESEN
        val unbekannt = unbekannteSpuren(quellen)
        // Ohne Spannen gibt es keine Dienstzeit - und die legt fest, wo der Weckpunkt liegt. „Nicht lesbar“
        // ist etwas anderes als „der Typ hat keinen Termin“: dann waere die Leiste auf einem erfundenen
        // Normaltag gerechnet.
        val spannen = quellen.spannen.wertOderNull()
            ?: return TagesleisteAnzeige(gelesen = true, leiste = Tagesleiste.LEER, unbekannt = unbekannt)

        val dimmer = quellen.dimmer.wertOderNull()
        val nichtStoeren = quellen.nichtStoeren.wertOderNull()
        val lichtQuelle = quellen.licht.wertOderNull()

        var eingabe = TagesleisteEingabe(
            name = gespeicherterName ?: entwurf.name.trim(),
            kategorie = entwurf.kategorie,
            weckerAn = entwurf.weckerAn,
            weckzeit = entwurf.weckzeit,
            still = entwurf.isSilent,
            dienstzeit = gespeicherterName?.let { dienstzeitAus(spannen, it, jetztMs, zone) },
            blockposition = blockposition,
            rufCutoffMinuten = nichtStoeren?.rufCutoffMinuten ?: DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN
        )

        if (dimmer != null) {
            // Fehlt die „Nicht stören“-Quelle, laufen ihre Schalter AUS: nur das Dimm-Ergebnis wird uebernommen.
            val ergebnis = dimmenUndNichtStoeren(
                eingabe,
                DimDndEinstellungen(
                    dimEnabled = dimmer.an,
                    regeln = dimmer.regeln,
                    nichtStoeren = nichtStoeren?.toggles ?: DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = false),
                    ausgenommeneSchichten = nichtStoeren?.ausgenommeneSchichten.orEmpty()
                ),
                dimmer.regelwahl
            )
            eingabe = eingabe.copy(
                dimmen = ergebnis.dimmen,
                nichtStoeren = if (nichtStoeren != null) ergebnis.nichtStoeren else emptyList()
            )
        }
        if (lichtQuelle != null) {
            // Ein Lesefehler der Hue-Regeln kommt hier nie als leere Liste an: der Adapter wird nur mit
            // gelesenen Regeln gerufen (KDoc von `licht`).
            eingabe = eingabe.copy(
                licht = licht(eingabe, LichtEinstellungen(lichtQuelle.konfiguriert, lichtQuelle.regeln), lichtQuelle.vorlaufMinuten)
            )
        }
        return TagesleisteAnzeige(gelesen = true, leiste = tagesleiste(eingabe), unbekannt = unbekannt)
    }
}

private fun <T> Quelle<T>.wertOderNull(): T? = when (this) {
    is Quelle.Bekannt -> wert
    Quelle.Unbekannt -> null
}

/**
 * Die Farben der sechs Spuren. Hell sind es die Farben des Prototyps (`FARBE`, Quelle der Karte); im
 * Dunkelmodus waeren `#2D2A28` (Eintrag) und die dunklen Violett-Toene auf der dunklen Leiste kaum zu
 * sehen - dort stehen aufgehellte Toene (Kontrast >= 3:1 zur Leiste, `TagesleisteKarteTest`).
 *
 * Diese Karte folgt damit dem Prototyp und nicht der Regel „Farbe heisst Zustand, nie Funktion“ (die
 * gilt fuer Etiketten): jede Spur braucht ihre eigene Farbe, und die Ereignisliste und die Legende nennen
 * sie zusaetzlich beim Namen.
 */
internal object LeistenFarben {
    private val HELL = mapOf(
        Spur.EINTRAG to Color(0xFF2D2A28),
        Spur.DIMMEN to Color(0xFF6B3FA0),
        Spur.NICHT_STOEREN to Color(0xFF2F6FB0),
        Spur.LICHT to Color(0xFFE39B1B),
        Spur.RUF_ABFRAGE to Color(0xFF4E2A6B),
        Spur.WECKER to Color(0xFFE2001A)
    )
    private val DUNKEL = mapOf(
        Spur.EINTRAG to Color(0xFFE6E1DD),
        Spur.DIMMEN to Color(0xFFB58AE6),
        Spur.NICHT_STOEREN to Color(0xFF6EA8E8),
        Spur.LICHT to Color(0xFFE39B1B),
        Spur.RUF_ABFRAGE to Color(0xFFA27BD0),
        Spur.WECKER to BrandRedLightMode
    )

    fun farbe(spur: Spur, dunkel: Boolean): Color = (if (dunkel) DUNKEL else HELL).getValue(spur)
}

// Hoehe und Lage der Spuren auf der Leiste in dp (Prototyp `.strip`, `.l-*`); der Weckpunkt ist ein
// Strich ueber die ganze Hoehe und hat keine eigene Zeile.
private const val LEISTE_HOEHE_DP = 66f
private const val BALKEN_HOEHE_DP = 9f
private const val MIN_BREITE_ANTEIL = 0.006f

private fun zeileDp(spur: Spur): Float? = when (spur) {
    Spur.EINTRAG -> 5f
    Spur.DIMMEN -> 17f
    Spur.NICHT_STOEREN -> 29f
    Spur.LICHT -> 41f
    Spur.RUF_ABFRAGE -> 53f
    Spur.WECKER -> null
}

/** Die Achse unter der Leiste: Anteil der Breite und Beschriftung (Prototyp `leiste.achse`). */
private val ACHSE = listOf(
    0.125f to "Tag 0:00", 0.25f to "6", 0.375f to "12", 0.5f to "18", 0.625f to "Folgetag", 0.75f to "6", 0.875f to "12"
)

/**
 * Die Karte „So läuft ein Tag <Name>“ (SPEC 3.3 Punkt 3, Prototyp `zeigeTyp`): Hinweis, bei Dienst und
 * Rufbereitschaft die Chips der Blockposition, die Leiste mit Achse und Legende, die Ereignisse und der Link
 * in den Bereich „Wecker“. Zustandslos - der Entwurf und die Blockposition gehoeren der Seite.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagesleisteKarte(
    name: String,
    kategorie: ShiftKategorie,
    anzeige: TagesleisteAnzeige,
    blockposition: Blockposition,
    onBlockposition: (Blockposition) -> Unit,
    onZurWoche: () -> Unit
) {
    val dunkel = isSystemInDarkTheme()
    val rand = Modifier.padding(horizontal = SpacingConstants.SPACING_LARGE)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                TagesleisteTexte.titel(name),
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
            Text(
                TagesleisteTexte.hinweis(kategorie),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = rand.padding(bottom = SpacingConstants.SPACING_MEDIUM)
            )

            if (TagesleisteKarteLogik.zeigtBlockwahl(kategorie)) {
                // FlowRow: vier Chips brechen bei 320 dp um, statt abgeschnitten zu werden.
                FlowRow(
                    modifier = rand.padding(bottom = SpacingConstants.SPACING_MEDIUM),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TagesleisteKarteLogik.BLOCKPOSITIONEN.forEach { position ->
                        FilterChip(
                            selected = position == blockposition,
                            onClick = { onBlockposition(position) },
                            label = { Text(TagesleisteTexte.block(position)) }
                        )
                    }
                }
            }

            if (!anzeige.gelesen) {
                Text(
                    TagesleisteTexte.WIRD_GELESEN,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rand.padding(bottom = SpacingConstants.SPACING_MEDIUM)
                )
            } else {
                Column(modifier = rand.padding(bottom = 4.dp)) {
                    Leiste(anzeige.leiste, dunkel)
                    Achse()
                }
            }

            Legende(dunkel)

            if (anzeige.unbekannt.isNotEmpty()) {
                Text(
                    TagesleisteTexte.nichtLesbar(anzeige.unbekannt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rand.padding(bottom = SpacingConstants.SPACING_MEDIUM)
                )
            }
            anzeige.leiste.hinweis?.let { hinweis ->
                Text(
                    hinweis.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rand.padding(bottom = SpacingConstants.SPACING_MEDIUM)
                )
            }

            anzeige.leiste.ereignisse.forEach { ereignis ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = rand.padding(vertical = SpacingConstants.SPACING_SMALL),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        ereignis.zeit,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.width(92.dp)
                    )
                    Punkt(LeistenFarben.farbe(ereignis.spur, dunkel), Modifier.padding(top = 4.dp))
                    Text(
                        ereignis.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                TagesleisteTexte.LINK,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onZurWoche)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM)
            )
        }
    }
}

@Composable
private fun Punkt(farbe: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(farbe, CircleShape))
}

/** Die Legende: alle sechs Spuren, immer - auch die, die dieser Typ nicht hat (wie im Prototyp). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legende(dunkel: Boolean) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingConstants.SPACING_LARGE)
            .padding(top = 4.dp, bottom = SpacingConstants.SPACING_MEDIUM),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Spur.entries.forEach { spur ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Punkt(LeistenFarben.farbe(spur, dunkel))
                Text(TagesleisteTexte.spur(spur), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Die Leiste selbst: ein Streifen mit einer Zeile je Spur und dem Weckpunkt als Strich (Prototyp `.strip`). */
@Composable
private fun Leiste(leiste: Tagesleiste, dunkel: Boolean) {
    val flaeche = MaterialTheme.colorScheme.surfaceContainer
    val gitter = MaterialTheme.colorScheme.outline
    val farben = remember(dunkel) { Spur.entries.associateWith { LeistenFarben.farbe(it, dunkel) } }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(LEISTE_HOEHE_DP.dp)
            .semantics { contentDescription = TagesleisteTexte.BESCHREIBUNG }
    ) {
        val breite = size.width
        val dp = density
        drawRoundRect(flaeche, cornerRadius = CornerRadius(10 * dp))
        for (i in 1..7) {
            drawRect(gitter, Offset(breite * i / 8f - 0.5f * dp, 0f), Size(1f * dp, size.height))
        }
        leiste.balken.forEach { balken ->
            val zeile = zeileDp(balken.spur) ?: return@forEach
            val links = breite * balken.vonMin / TAGESLEISTE_MIN
            val laenge = max(breite * MIN_BREITE_ANTEIL, breite * (balken.bisMin - balken.vonMin) / TAGESLEISTE_MIN)
            val farbe = farben.getValue(balken.spur)
            if (balken.spur == Spur.RUF_ABFRAGE) {
                // Gestrichelt (Prototyp `.l-ruf`): 6 dp Strich, 4 dp Luecke - die Abfrage ist ein Raster, kein Zustand.
                var x = links
                while (x < links + laenge) {
                    drawRect(farbe, Offset(x, zeile * dp), Size(minOf(6f * dp, links + laenge - x), BALKEN_HOEHE_DP * dp))
                    x += 10f * dp
                }
            } else {
                drawRoundRect(farbe, Offset(links, zeile * dp), Size(laenge, BALKEN_HOEHE_DP * dp), CornerRadius(5 * dp))
            }
        }
        leiste.weckpunkte.forEach { wecker ->
            drawRect(
                farben.getValue(Spur.WECKER),
                Offset(breite * wecker.minute / TAGESLEISTE_MIN - 1f * dp, 0f),
                Size(3f * dp, size.height)
            )
        }
    }
}

/**
 * Die Achse unter der Leiste. Die Beschriftungen stehen unter ihrer Stelle der Leiste; passen sie bei
 * 320 dp oder grosser Schrift nicht nebeneinander, brechen sie um (in Reihenfolge, Zeile fuer Zeile), statt
 * sich zu ueberdecken oder abgeschnitten zu werden.
 */
@Composable
private fun Achse() {
    val farbe = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            ACHSE.forEach { (_, text) -> Text(text, fontSize = 11.sp, color = farbe, maxLines = 1, softWrap = false) }
        },
        modifier = Modifier.fillMaxWidth()
    ) { messbar, constraints ->
        val breite = constraints.maxWidth
        val platten = messbar.map { it.measure(Constraints()) }
        val luecke = 4.dp.roundToPx()
        val links = ACHSE.mapIndexed { i, (anteil, _) -> (breite * anteil).roundToInt() - platten[i].width / 2 }
        val passt = platten.indices.all { i ->
            links[i] >= 0 && links[i] + platten[i].width <= breite &&
                (i == 0 || links[i] - (links[i - 1] + platten[i - 1].width) >= luecke)
        }
        if (passt) {
            val hoehe = platten.maxOf { it.height }
            layout(breite, hoehe) { platten.forEachIndexed { i, p -> p.placeRelative(links[i], 0) } }
        } else {
            val abstand = 12.dp.roundToPx()
            var x = 0
            var y = 0
            var zeilenHoehe = 0
            val orte = platten.map { p ->
                if (x > 0 && x + p.width > breite) {
                    x = 0
                    y += zeilenHoehe
                    zeilenHoehe = 0
                }
                val ort = x to y
                x += p.width + abstand
                zeilenHoehe = max(zeilenHoehe, p.height)
                ort
            }
            layout(breite, y + zeilenHoehe) { platten.forEachIndexed { i, p -> p.placeRelative(orte[i].first, orte[i].second) } }
        }
    }
}
