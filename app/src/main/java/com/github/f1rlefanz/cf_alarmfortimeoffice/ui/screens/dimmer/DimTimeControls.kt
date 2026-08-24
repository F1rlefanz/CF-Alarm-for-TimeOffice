package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import android.app.TimePickerDialog
import android.content.Context

/** "HH:mm" aus Minuten seit Mitternacht - fuer die Uhrzeit-Felder des Regel-Editors. */
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
