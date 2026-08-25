package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable

/**
 * IMMUTABLE Alarm Information Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Strukturelle Gleichheit für effiziente Flow-Operations
 * 
 * 🔧 SYNC-FIX: Event-Tracking für intelligente Alarm-Synchronisation
 * ✅ eventId: Google Calendar Event-ID (erkennt gelöschte Events)
 * ✅ eventChecksum: Hash des Events (erkennt Änderungen)
 * ✅ Löst Bug: "Alter Alarm klingelt nach Event-Änderung"
 */
@Immutable
data class AlarmInfo(
    val id: Int,
    val shiftId: String,
    val shiftName: String,
    val triggerTime: Long,
    val formattedTime: String,
    val isActive: Boolean = true,
    
    // 🔧 SYNC-FIX: Event-Tracking für intelligente Synchronisation
    val eventId: String = "",  // Google Calendar Event-ID
    val eventChecksum: String = "",  // Hash: startTime+endTime+title (erkennt Änderungen)

    // Schichtende (Ende des Kalender-Events) als Epoch-Millis – Basis für SHIFT_END-Dimmfenster
    // (ND-Tagschlaf). 0 = unbekannt (z. B. manuell angelegte Alarme ohne Schicht).
    val shiftEndTime: Long = 0,

    // Schichtbeginn (Start des Kalender-Events) als Epoch-Millis – anders als triggerTime (das ist
    // die Weckzeit, i.d.R. vor Schichtbeginn wegen Anfahrtszeit). Basis fuer DND-"Dienstzeit"-Fenster
    // (dnd/DndShiftSpanResolver). 0 = unbekannt (z. B. manuell angelegte Alarme ohne Schicht).
    val shiftStartTime: Long = 0,

    // "Stille Schicht" (ShiftDefinition.isSilent), uebernommen beim Erstellen aus dem ShiftMatch.
    // true = beim Feuern kein AlarmSoundService-Start, kein Vollbild, keine Hue-Regeln - nur der
    // Zeit-Anker fuer DND/Dimmer bleibt. Siehe AlarmReceiver.isSilentAlarm() und CLAUDE.md
    // "Stille Schicht".
    val isSilent: Boolean = false
)

/**
 * Anhaengsel, das einen VON HAND angelegten Wecker in der Anzeige kenntlich macht
 * ("Fruehschicht (Manuell)"). Es steht bewusst im [AlarmInfo.shiftName], weil der Nutzer in der
 * Weckerliste sehen soll, welcher Wecker nicht aus dem Kalender stammt.
 *
 * Genau deshalb ist es aber eine Falle fuer jeden Konsumenten, der ueber den Schichtnamen
 * ZUORDNET: `ShiftConfig.findDefinitionFor("Fruehschicht (Manuell)")` findet nichts, und der
 * Aufrufer haelt das fuer "diese Schicht kenne ich nicht". Am Emulator gemessen (27.08.2026):
 * Ein manueller Fruehschicht-Wecker feuerte normal, im Log stand
 * `No shift definition found for: Fruehschicht (Manuell) (skipping Hue rules)` - die Hue-Regel
 * der Fruehschicht lief nicht, ohne dass irgendwo etwas von einem Fehler stand.
 *
 * Wer ueber den Schichtnamen zuordnet, nimmt deshalb [reinerSchichtname]; wer ihn ANZEIGT,
 * nimmt weiterhin [AlarmInfo.shiftName].
 */
const val MANUELLER_ALARM_SUFFIX = " (Manuell)"

/**
 * Der Schichtname ohne das Anzeige-Anhaengsel [MANUELLER_ALARM_SUFFIX] - die Form, mit der sich
 * eine Schichtdefinition wiederfinden laesst.
 */
fun reinerSchichtname(shiftName: String): String = shiftName.removeSuffix(MANUELLER_ALARM_SUFFIX)

