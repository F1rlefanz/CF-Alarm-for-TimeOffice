package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftEditFormular
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.TypEntwurf
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Die Rechnung der Karte „Wecker“, ohne Compose (Umbau #189). Wie [TypAbschnittLogik]: jede Funktion
 * nimmt einen Entwurf und liefert einen Entwurf, geschrieben wird erst mit „Speichern“.
 */
internal object WeckerAbschnittLogik {

    /** Eingabefilter des Zeitfelds: „06:“ und „6“ sind gueltige Zwischenstaende, „ab:cd“ ist es nicht. */
    private val ZEIT_EINGABE = Regex("^\\d{0,2}:?\\d{0,2}$")

    /**
     * Zeigt die Seite die Karte „Wecker“? Nein bei Krank (gibt den Tag frei, #142) und Ignorieren
     * (ergibt keine Schicht, #167): dort zeigte jedes Feld einen Ablauf, den es nicht gibt. Dieselbe
     * Frage wie im Neuanlage-Dialog - sie hat EINE Antwort ([ShiftEditFormular.zeigtWeckerBlock]).
     */
    fun zeigtWeckerKarte(kategorie: ShiftKategorie): Boolean = ShiftEditFormular.zeigtWeckerBlock(kategorie)

    /** Weckzeit und „Stummer Wecker“ wirken nur bei eingeschaltetem Wecker: sonst sichtbar, aber gesperrt. */
    fun felderGesperrt(entwurf: TypEntwurf): Boolean = !entwurf.weckerAn

    /** Der Satz unter „Wecker an diesem Tag“ - er nennt nur, was der Typ TUT (stellt einen Wecker oder nicht). */
    fun weckerHinweis(weckerAn: Boolean): String =
        if (weckerAn) ShiftDefinitionTexte.WECKER_AN_HINWEIS else ShiftDefinitionTexte.WECKER_AUS_HINWEIS

    /**
     * Ist die Weckzeit ein Fehler? Nur wenn sie etwas bewirkt: bei „Wecker aus“ ist das Feld gesperrt und
     * sein Inhalt gleichgueltig - er sperrt auch das Speichern nicht ([TypEntwurf.speicherbar], #142).
     */
    fun weckzeitFehler(entwurf: TypEntwurf): Boolean = entwurf.weckerAn && entwurf.weckzeit == null

    /** Die getippte Weckzeit uebernehmen - oder den Entwurf unveraendert lassen, wenn sie kein Zwischenstand einer Uhrzeit ist. */
    fun weckzeitAendern(entwurf: TypEntwurf, eingabe: String): TypEntwurf =
        if (eingabe.matches(ZEIT_EINGABE)) entwurf.copy(weckzeitText = eingabe) else entwurf
}

/**
 * Die Karte „Wecker“ der Typ-Seite (SPEC 3.3 Punkt 5, Prototyp `zeigeTyp`): „Wecker an diesem Tag“,
 * „Weckzeit“ und „Stummer Wecker“. Zustandslos - der Entwurf gehoert der Seite.
 *
 * Bei „Wecker aus“ bleiben Weckzeit und „Stummer Wecker“ sichtbar, aber ausgegraut und gesperrt (Prototyp
 * `.row.aus`); ihre Werte bleiben im Entwurf und im gespeicherten Typ stehen (SPEC E2) - ein spaeteres
 * Einschalten bringt sie zurueck. Die Weckzeit ist ein Textfeld wie im Dialog davor, keine Zeitwahl: der
 * Entwurf haelt den eingetippten TEXT (`TypEntwurf.weckzeitText`), weil „06:“ ein gueltiger Zwischenstand
 * ist, und `Saver`, `speicherbar` und `zeitZumSpeichern` rechnen darauf.
 *
 * Nur fuer Typen, die einen Wecker stellen koennen ([WeckerAbschnittLogik.zeigtWeckerKarte]).
 */
@Composable
internal fun WeckerAbschnitt(
    entwurf: TypEntwurf,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    val gesperrt = WeckerAbschnittLogik.felderGesperrt(entwurf)
    val fehler = WeckerAbschnittLogik.weckzeitFehler(entwurf)
    val zeilenRand = Modifier.padding(
        horizontal = SpacingConstants.SPACING_LARGE,
        vertical = SpacingConstants.SPACING_MEDIUM
    )

    // Weisse Flaeche mit Rand wie die Karte „Typ“ (`TypAbschnitt`).
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                ShiftDefinitionTexte.WECKER_ABSCHNITT_TITEL,
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

            Column(modifier = zeilenRand) {
                SwitchRow(
                    title = ShiftDefinitionTexte.WECKER_AN_TITEL,
                    description = WeckerAbschnittLogik.weckerHinweis(entwurf.weckerAn),
                    checked = entwurf.weckerAn,
                    onCheckedChange = { neu -> aendere { it.copy(weckerAn = neu) } },
                    titleFontWeight = FontWeight.Normal
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = zeilenRand) {
                WeckzeitZeile(entwurf, gesperrt, fehler, aendere)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // SwitchRow statt handgebauter Zeile (#132): weight(1f) am Text und 12 dp Abstand zum Schalter.
            Column(modifier = zeilenRand.alpha(if (gesperrt) AUSGEGRAUT_ALPHA else 1f)) {
                SwitchRow(
                    title = ShiftDefinitionTexte.STUMMER_WECKER_TITEL,
                    description = ShiftDefinitionTexte.STUMMER_WECKER_HINWEIS,
                    checked = entwurf.isSilent,
                    onCheckedChange = { neu -> aendere { it.copy(isSilent = neu) } },
                    enabled = !gesperrt,
                    titleFontWeight = FontWeight.Normal
                )
            }
        }
    }
}

/** „Weckzeit“: Titel und Hinweis links, ein schmales Feld rechts (Prototyp `typ-weckzeit`). */
@Composable
private fun WeckzeitZeile(
    entwurf: TypEntwurf,
    gesperrt: Boolean,
    fehler: Boolean,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nur der Text ist ausgegraut; das Feld faerbt Material selbst (gesperrt + alpha waere doppelt blass).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (gesperrt) AUSGEGRAUT_ALPHA else 1f)
            ) {
                Text(ShiftDefinitionTexte.WECKZEIT_TITEL, style = MaterialTheme.typography.bodyLarge)
                Text(
                    ShiftDefinitionTexte.WECKZEIT_HINWEIS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = entwurf.weckzeitText,
                onValueChange = { eingabe -> aendere { WeckerAbschnittLogik.weckzeitAendern(it, eingabe) } },
                // Kurz, damit die Beschriftung bei 320 dp mit Schrift 1,3 nicht abgeschnitten wird.
                label = { Text("HH:mm") },
                modifier = Modifier.width(ZEITFELD_BREITE),
                singleLine = true,
                enabled = !gesperrt,
                isError = fehler
            )
        }
        if (fehler) {
            Text(
                ShiftDefinitionTexte.WECKZEIT_FEHLER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = SpacingConstants.SPACING_EXTRA_SMALL)
            )
        }
    }
}

/** Deckkraft einer ausgegrauten Zeile: unter Material-Standard (0,38), damit Text lesbar bleibt (wie im Dialog). */
private const val AUSGEGRAUT_ALPHA = 0.6f

/** Breit genug fuer „06:30“ in Schrift 1,3 samt Innenabstand, schmal genug, dass der Text links 320 dp uebersteht. */
private val ZEITFELD_BREITE = 120.dp
