package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

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
 * Empfaengt den rollenden DND-Tick: wertet den Soll-Zustand neu aus (Zen-Regel an/aus) und plant
 * den naechsten Uebergang. Selbstkorrigierend, wie [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleReceiver] -
 * jeder Tick liest Alarm-Bestand und Dimmer-Zeitleiste neu.
 */
@AndroidEntryPoint
class DndScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var dndSchedule: DndScheduleUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                dndSchedule.applyCurrentState()
                dndSchedule.scheduleNextTransition()
            } catch (t: Throwable) {
                Logger.e(LogTags.DND, "DND-Tick fehlgeschlagen", t)
            } finally {
                pending.finish()
            }
        }
    }
}
