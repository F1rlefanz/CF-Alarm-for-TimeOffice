package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reine Zeitmathematik fuer die Rufbereitschaft-DND-Schiene ("On-Call-Cutoff") - bewusst OHNE
 * Android-Abhaengigkeiten, damit sie unit-testbar ist (Vorbild:
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver], [DndShiftSpanResolver]).
 *
 * Keine eigene Fenster-Logik/Policy fuer On-Call-Naechte: dieser Resolver KAPPT lediglich die
 * bestehenden Fenster aus den zwei anderen Quellen ("Folgt dem Dimmer" / "Waehrend der Dienstzeit")
 * auf einen festen Cutoff-Zeitpunkt am Tag der On-Call-Schicht - dieselbe [android.app.AutomaticZenRule]
 * gilt bis dahin unveraendert weiter, es gibt keine separate Policy.
 *
 * Die [ZoneId] wird stets hereingereicht (statt intern `systemDefault()` zu ziehen), damit Tests
 * ohne Geraete-Zeitzonen-Abhaengigkeit reproduzierbar sind - gleiche Konvention wie bei
 * `DimWindowResolver`.
 */
object DndOnCallCutoffResolver {

    /**
     * Minimal-Info einer Schicht fuer die Cutoff-Berechnung (entkoppelt von Android). Name
     * historisch - gefuellt wird sie seit v1.25.2 aus `ShiftSpan`, nicht mehr aus `AlarmInfo`.
     */
    data class AlarmSlot(val shiftName: String, val shiftStartTime: Long)

    /**
     * Ein Cutoff-Zeitpunkt (Epoch-Millis) pro erkanntem On-Call-Tag: fuer jeden Alarm, dessen
     * [AlarmSlot.shiftName] in [onCallShifts] steht UND dessen [AlarmSlot.shiftStartTime] bekannt
     * ist (> 0 - 0 bedeutet "unbekannt", z. B. manuell angelegte Alarme ohne Schicht), liegt der
     * Cutoff zur Uhrzeit [cutoffMinutes] (Minuten seit Mitternacht) - und zwar an dem Kalendertag,
     * an dem diese Uhrzeit tatsaechlich in der on-call-Nacht liegt: Startet die Schicht bereits
     * NACH dieser Uhrzeit desselben Kalendertags (z. B. 00:30 oder 03:00 bei Cutoff 05:00), ist es
     * derselbe Tag wie [AlarmSlot.shiftStartTime]. Startet die Schicht dagegen VOR dieser Uhrzeit,
     * typischerweise abends (z. B. 21:00 bei Cutoff 05:00), liegt die Uhrzeit erst am naechsten
     * Kalendertag in der Zukunft - der Cutoff wandert entsprechend auf den Folgetag. Ohne diese
     * Fallunterscheidung landete der Cutoff einer abends beginnenden On-Call-Schicht vor deren
     * eigenem Beginn und klippte stattdessen die voellig unbeteiligte Vornacht.
     */
    fun cutoffInstants(
        alarms: List<AlarmSlot>,
        onCallShifts: Set<String>,
        cutoffMinutes: Int,
        zone: ZoneId
    ): List<Long> = alarms
        .asSequence()
        .filter { it.shiftName in onCallShifts && it.shiftStartTime > 0L }
        .map { alarm ->
            val startZoned = Instant.ofEpochMilli(alarm.shiftStartTime).atZone(zone)
            val startMinutesOfDay = startZoned.hour * 60 + startZoned.minute
            val date = if (startMinutesOfDay >= cutoffMinutes) {
                startZoned.toLocalDate().plusDays(1)
            } else {
                startZoned.toLocalDate()
            }
            // Wall-clock Uhrzeit direkt aufloesen (nicht Mitternacht + Millis-Offset addieren) -
            // sonst landet der Cutoff an einem DST-Vorspringen-Tag eine Stunde zu spaet, weil die
            // uebersprungene Stunde in der reinen Millis-Rechnung nie fehlt.
            date.atTime(LocalTime.ofSecondOfDay(cutoffMinutes * 60L))
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }
        .distinct()
        .toList()

    /**
     * Kappt jedes Fenster aus [windows], dessen Ende hinter einem der [cutoffInstants] liegt, auf
     * dessen Ende - unabhaengig pro Fenster, jedes wird hoechstens von dem Cutoff geklippt, der
     * tatsaechlich in seiner Spanne liegt (mehrere On-Call-Tage/Fenster beeinflussen sich nicht
     * gegenseitig). Ein Cutoff, der ausserhalb des Fensters liegt (vor dessen Start oder nach
     * dessen Ende), veraendert das Fenster nicht - insbesondere wird ein Fenster nie auf eine
     * leere/negative Spanne geklippt.
     */
    fun clip(windows: List<LongRange>, cutoffInstants: List<Long>): List<LongRange> {
        if (cutoffInstants.isEmpty()) return windows
        return windows.map { window ->
            val cutoff = cutoffInstants
                .filter { it > window.first && it < window.last }
                .minOrNull()
                ?: return@map window
            window.first..cutoff
        }
    }
}
