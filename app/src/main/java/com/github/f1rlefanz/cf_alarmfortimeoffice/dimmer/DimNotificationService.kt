package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bedient die Aktions-Buttons der Dimmer-Korrektur-Notification (Feature C): Heller/Dunkler/
 * Pause/Fortsetzen. Leichtgewichtiger, NICHT-Foreground [Service] (Vorbild `AlarmSoundService`
 * fuer Action-String im `onStartCommand`-`when`-Block + `PendingIntent.getService`, aber ohne
 * dessen Foreground-/Ton-Verantwortung - hier gibt es nichts, das den Prozess am Leben halten
 * muesste, jeder Aufruf ist eine kurze DataStore-Operation plus ein Tick-Nachvollzug).
 *
 * Persistiert den Override direkt im [DimOverlayPrefs]-DataStore statt in-memory zu halten, weil
 * weder dieser Service noch [DimAccessibilityService]/[DimScheduleReceiver] eine garantierte
 * Lebensdauer haben.
 *
 * Ruft nach jeder Aktion [DimScheduleUseCase.applyCurrentState] auf statt selbst eine
 * Notification zu bauen - der zeigt/verwirft die Korrektur-Notification bereits zentral (Toggle +
 * Fensterende inklusive), eine zweite Stelle dafuer würde die Logik duplizieren.
 */
@AndroidEntryPoint
class DimNotificationService : Service() {

    companion object {
        const val ACTION_BRIGHTER = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_CORRECTION_BRIGHTER"
        const val ACTION_DARKER = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_CORRECTION_DARKER"
        const val ACTION_PAUSE = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_CORRECTION_PAUSE"
        const val ACTION_RESUME = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_CORRECTION_RESUME"
    }

    @Inject
    lateinit var dimSchedule: DimScheduleUseCase

    @Inject
    lateinit var prefs: DimOverlayPrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        scope.launch {
            try {
                handleAction(action)
            } catch (t: Throwable) {
                Logger.e(LogTags.DIMMER, "Dimmer-Korrektur-Aktion fehlgeschlagen: $action", t)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handleAction(action: String?) {
        val active = dimSchedule.activeSpanNow()
        if (active == null) {
            // Fenster ist inzwischen zu Ende (Notification veraltet/wird gerade weggeräumt) -
            // applyCurrentState() cancelt sie hier selbst, nichts mehr zu korrigieren.
            dimSchedule.applyCurrentState()
            return
        }

        val windowEnd = active.range.last
        val current = prefs.overrideNow()
        val stale = DimWindowResolver.isOverrideStale(windowEnd, current.windowEnd)
        val delta = if (stale) 0 else current.strengthDelta
        val paused = if (stale) false else current.paused

        val (newDelta, newPaused) = when (action) {
            ACTION_BRIGHTER -> (delta - DimOverlayPrefs.OVERRIDE_STEP) to paused
            ACTION_DARKER -> (delta + DimOverlayPrefs.OVERRIDE_STEP) to paused
            ACTION_PAUSE -> delta to true
            ACTION_RESUME -> delta to false
            else -> {
                Logger.w(LogTags.DIMMER, "Unbekannte Dimmer-Korrektur-Aktion: $action")
                return
            }
        }

        // Atomar zurückschreiben (siehe DimOverlayPrefs.setOverride) - sonst würde z.B. ein reines
        // Pause-Toggle einen bereits stale gewordenen Delta-Wert unter dem neuen windowEnd
        // unbeabsichtigt wiederbeleben.
        prefs.setOverride(newDelta, newPaused, windowEnd)
        dimSchedule.applyCurrentState()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
