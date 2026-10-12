package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Empfaengt einen der beiden einmaligen Abgleiche des [TimeOfficeNachfassAbgleich] und startet
 * den regulaeren Wartungslauf mit `forceSync = true`. Stellt selbst nichts - weder einen
 * Nachfolger noch die 6h-Kette (die plant das `finally` des Laufs wie immer).
 *
 * Kein `directBootAware`: der Wartungslauf braucht den CE-Store (Kalenderauswahl, Token), und
 * gestellt wird nur aus der laufenden App heraus, also nach der Entsperrung; ein Neustart loescht
 * beide Alarme ohnehin. `AlarmMaintenanceService.start()` faengt den abgelehnten
 * Vordergrund-Start selbst - eine Exception aus `onReceive()` risse den Prozess mit. Waehrend der
 * Master-Pause feuert er regulaer nicht: `pause()` raeumt beide Alarme ab; der Pausen-Backstop
 * des Wartungslaufs ist nur das zweite Netz (siehe [TimeOfficeNachfassAbgleich]).
 */
class TimeOfficeNachfassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.business(LogTags.MAINTENANCE, "🔁 TimeOffice-Nachfass: Kalender-Abgleich - Wartungslauf mit forceSync")
        AlarmMaintenanceService.start(context, forceSync = true)
    }
}
