package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Empfaengt den stuendlichen Tick der [RufbereitschaftAbfrage] und startet den regulaeren
 * Wartungslauf mit `forceSync = true`. Die naechste Abfrage plant dessen `finally` ueber
 * [RufbereitschaftAbfrage.reschedule] - dieser Empfaenger stellt selbst nichts.
 *
 * Kein `directBootAware`: der Wartungslauf braucht den CE-Store (Kalenderauswahl, Token), und
 * im Direct Boot gaebe es ohnehin keinen Nutzer, der abgerufen werden koennte, ohne das Geraet
 * zu entsperren. `AlarmMaintenanceService.start()` faengt den abgelehnten Vordergrund-Start
 * selbst - eine Exception aus `onReceive()` risse den Prozess mit.
 */
class RufbereitschaftAbfrageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.business(LogTags.MAINTENANCE, "📞 Rufbereitschaft: stuendliche Kalender-Abfrage - Wartungslauf mit forceSync")
        AlarmMaintenanceService.start(context, forceSync = true)
    }
}
