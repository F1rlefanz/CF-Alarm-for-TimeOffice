package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartSchedulerEntryPoint
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * WorkManager Worker that starts a pre-alarm sunrise ramp.
 *
 * Scheduled by HueSmartScheduler at (alarmTime - sunriseDuration) for shifts that have an
 * enabled "start before alarm" sunrise rule, so the gradual warm→cool ramp finishes exactly
 * at the alarm time. Sunrises configured to start AT the alarm are handled by the at-alarm
 * path in AlarmReceiver instead.
 */
class SunriseStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SHIFT_NAME = "shift_name"

        /** Die Weckzeit als ISO-Ortszeit - fuer [HueSmartScheduler.sonnenaufgangNochGedeckt]. */
        const val KEY_WECKZEIT = "weckzeit"

        /**
         * PURE, TESTBAR: ganze Minuten von [jetzt] bis [weckzeit], aufgerundet und nie negativ
         * (WorkManager startet den Auftrag auch verspaetet). Echte Zeit, nicht Wanduhr - dieselbe
         * Rechnung wie beim Planen ([HueSmartScheduler.realeVerzoegerungMillis]), sonst laege das
         * Vorab-Auto-Aus in der Nacht der Zeitumstellung eine Stunde daneben.
         */
        internal fun minutenBisWecker(jetzt: LocalDateTime, weckzeit: LocalDateTime): Int {
            val millis = HueSmartScheduler.realeVerzoegerungMillis(jetzt, weckzeit).coerceAtLeast(0L)
            return ((millis + 59_999L) / 60_000L).toInt()
        }
    }

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val shiftName = inputData.getString(KEY_SHIFT_NAME)
        if (shiftName.isNullOrBlank()) {
            Logger.w(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: No shift name provided, nothing to do")
            return@withContext Result.success()
        }

        // Steht der Wecker noch? „Dienst fällt aus“/„Nicht wecken“ loeschen ihn, und den Auftrag
        // hier raeumt sonst nur ein Beobachter ab, der den Prozess-Tod nicht ueberlebt - siehe
        // HueSmartScheduler.sonnenaufgangNochGedeckt. Ein Auftrag aus einer Version OHNE Weckzeit
        // laeuft wie bisher. Im Zweifel (Bestand nicht lesbar) KEINE Rampe: der Wecker klingelt
        // davon unabhaengig, und ein Licht ohne Wecker ginge allenfalls ueber das Vorab-Auto-Aus
        // wieder aus - besser gar nicht erst an.
        val weckzeit = inputData.getString(KEY_WECKZEIT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (weckzeit != null) {
            val gedeckt = try {
                val alarme = EntryPointAccessors
                    .fromApplication(applicationContext, HueSmartSchedulerEntryPoint::class.java)
                    .alarmUseCase()
                    .getAllAlarms()
                    .getOrThrow()
                HueSmartScheduler.sonnenaufgangNochGedeckt(alarme, shiftName, weckzeit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: Wecker-Bestand nicht lesbar - keine Rampe", e)
                false
            }
            if (!gedeckt) {
                Logger.i(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: Wecker $weckzeit fuer $shiftName steht nicht mehr - keine Rampe")
                return@withContext Result.success()
            }
        }

        Logger.i(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: Starting pre-alarm sunrise for shift: $shiftName")

        return@withContext try {
            // Ensure the bridge is reachable before kicking off the ramp.
            bridgeManager.forceHealthCheck()

            val ruleUseCase = EntryPointAccessors
                .fromApplication(applicationContext, HueSmartSchedulerEntryPoint::class.java)
                .hueRuleUseCase()

            // Mit Weckzeit legt der Lauf das Auto-Aus gleich mit an - sonst bliebe das Licht an,
            // wenn der Wecker WAEHREND der Rampe geloescht wird (HueRuleUseCase.vorabAutoAusZiele).
            val result = ruleUseCase.executeSunrisePreAlarm(
                shiftName,
                weckzeit?.let { minutenBisWecker(LocalDateTime.now(), it) }
            )
            if (result.isSuccess) {
                val exec = result.getOrNull()
                Logger.i(
                    LogTags.HUE_BRIDGE,
                    "🌅✅ SUNRISE-WORKER: Sunrise started (${exec?.successfulActions ?: 0}/${exec?.actionsExecuted ?: 0} targets)"
                )
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "🌅⚠️ SUNRISE-WORKER: Sunrise execution failed", result.exceptionOrNull())
            }

            Result.success()
        } catch (e: Exception) {
            // Don't retry: the at-alarm path finalizes the lights anyway, so a missed ramp
            // never leaves the user without their wake-up light.
            Logger.e(LogTags.HUE_BRIDGE, "🌅❌ SUNRISE-WORKER: Failed to start sunrise", e)
            Result.success()
        }
    }
}
