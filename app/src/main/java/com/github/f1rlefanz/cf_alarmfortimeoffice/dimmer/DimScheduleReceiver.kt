package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Empfaengt den rollenden Dimm-Tick: wertet den Soll-Zustand neu aus (Overlay an/aus) und
 * plant den naechsten Uebergang. Selbstkorrigierend – jeder Tick liest den aktuellen
 * Alarm-Bestand neu, sodass neue Schichten automatisch beruecksichtigt werden.
 */
@AndroidEntryPoint
class DimScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var dimSchedule: DimScheduleUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                dimSchedule.applyCurrentState()
                dimSchedule.scheduleNextTransition()
            } catch (t: Throwable) {
                Logger.e(LogTags.DIMMER, "Dimm-Tick fehlgeschlagen", t)
            } finally {
                pending.finish()
            }
        }
    }
}
