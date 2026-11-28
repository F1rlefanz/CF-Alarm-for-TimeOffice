package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb

/*
 * Bausteine des Abschnitts „Bildschirm dimmen“ der Typ-Seite (#221) und seines Blatts „Weitere
 * Optionen“: die Uhrzeit-Auswahl, die Auswahl der Blockpositionen und der Name des Hell-Ereignisses.
 */

/** "HH:mm" aus Minuten seit Mitternacht - fuer die Uhrzeit-Felder der Dimm-Fenster. */
internal fun fmtClock(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

internal fun pickTime(context: Context, currentMinutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(h * 60 + m) },
        currentMinutes / 60,
        currentMinutes % 60,
        true
    ).show()
}

/** Der Uhrzeit-Knopf eines Fensters: zeigt die Zeit, der Tipp oeffnet die Auswahl des Aufrufers ([pickTime]). */
@Composable
internal fun ZeitKnopf(
    minuten: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.Default,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors()
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors) {
        Text(fmtClock(minuten), style = textStyle)
    }
}

/**
 * Reihenfolge der Blockpositions-Chips - alle vier, in der Reihenfolge einer Dienstfolge. Als Konstante neben den
 * Composables, damit ein Test sie gegen das Modell halten kann: eine Position, die das Modell kennt und die Auswahl
 * nicht anbietet, waere aus einer Vorlage eingeschleust nicht mehr abwaehlbar.
 */
internal val BLOCKPOSITIONEN = listOf(
    Blockposition.ERSTER, Blockposition.MITTLERER, Blockposition.LETZTER, Blockposition.EINZELNER
)

/**
 * Ein Chip-Tipp schaltet EINE Position um - und laesst das letzte Haekchen stehen. Ein Fenster
 * ohne jede Position gaelte nirgends und stuende trotzdem in der Regel; wer das Fenster nicht
 * will, entfernt es. Reine Funktion, damit die Regel testbar ist.
 */
internal fun blockpositionUmschalten(aktuell: Set<Blockposition>, position: Blockposition): Set<Blockposition> =
    if (position in aktuell) {
        if (aktuell.size == 1) aktuell else aktuell - position
    } else {
        aktuell + position
    }

/**
 * Nutzername des Ereignisses hinter einer [HellAb]-Wahl ("Dämmerung"/"Sonnenaufgang") - fuer
 * Vorschau und Editor-Beispiel. [HellAb.IMMER] kuerzt nie und taucht hier nicht auf; faellt es
 * doch hinein, ist "Sonnenaufgang" die harmlose Beschriftung.
 */
@Composable
internal fun tageslichtEreignisName(hellAb: HellAb): String = stringResource(
    when (hellAb) {
        HellAb.DAEMMERUNG -> R.string.tageslicht_ereignis_daemmerung
        HellAb.SONNENAUFGANG, HellAb.IMMER -> R.string.tageslicht_ereignis_sonnenaufgang
    }
)
