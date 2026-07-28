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
    val shiftStartTime: Long = 0
)
