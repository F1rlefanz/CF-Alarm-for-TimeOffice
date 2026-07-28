package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steuert die App-eigene [AutomaticZenRule] aus ZWEI unabhaengigen, per OR (Vereinigung) kombinierten
 * Fenster-Quellen, ueber EINEN rollenden exakten Alarm (Muster wie [DimScheduleUseCase]):
 *
 *  1. "Folgt dem Dimmer" ([DndPrefs.Toggles.followDimmerEnabled]): liest [DimScheduleUseCase.previewTimeline]
 *     direkt (Einbahnstrasse - der Dimmer bleibt unveraendert/unwissend von DND). Keine eigene
 *     Fenster-Definition, kein Drift-Risiko: es gibt nur eine Quelle der Wahrheit fuer "wann dimmt".
 *  2. "Waehrend der Dienstzeit" ([DndPrefs.Toggles.duringShiftEnabled]): die rohe Kalender-Event-Spanne
 *     jeder Schicht ([DndShiftSpanResolver]), abzueglich per Chip ausgeschlossener Schichten.
 *
 * DND ist binaer (an/aus) - anders als beim Dimmer gibt es kein "dunkelste gewinnt", die Vereinigung
 * beider Quellen reicht (irgendeine aktive Quelle => an).
 *
 * Nutzt bewusst eine selbst registrierte [AutomaticZenRule] statt rohem
 * `NotificationManager.setInterruptionFilter()`: die Regel ist fuer den Nutzer sichtbar unter
 * Einstellungen -> Ton -> Nicht stoeren -> Zeitplaene, mit eigener [ZenPolicy] statt der globalen,
 * manuell gesetzten Policy zu ueberschreiben - koexistiert so mit dem manuellen DND des Nutzers und
 * fremden Automatisierungen (Bixby/Tasker), statt sie zu ueberschreiben. Nur ab API 30 verfuegbar
 * (configurationActivity-Ownership ohne ConditionProviderService), siehe [isSupported].
 */
@Singleton
class DndScheduleUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmUseCase: IAlarmUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val prefs: DndPrefs
) {
    companion object {
        const val ACTION_TICK = "com.github.f1rlefanz.cf_alarmfortimeoffice.DND_SCHED_TICK"

        // NICHT 7710 (Dimmer-Tick, dimmer/DimScheduleUseCase) oder 0 (6h-Wartung,
        // AlarmMaintenanceService) wiederverwenden - eigene, unabhaengige Alarm-Kette.
        private const val REQ_DND_TICK = 7712

        private val CONDITION_ID: Uri = "condition://com.github.f1rlefanz.cf_alarmfortimeoffice/dnd".toUri()
        private const val RULE_NAME = "CFAlarm Ruhezeit"
    }

    /** Nur ab Android 11 (API 30) - configurationActivity-Ownership ohne ConditionProviderService. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun enable() {
        applyCurrentState()
        scheduleNextTransition()
    }

    suspend fun applyCurrentState() {
        if (!isSupported()) return
        val nm = notificationManager()
        if (!nm.isNotificationPolicyAccessGranted) {
            Logger.w(LogTags.DND, "Keine Freigabe fuer Benachrichtigungszugriff - DND-Tick uebersprungen")
            return
        }
        val ruleId = ensureZenRule(nm) ?: return
        val now = System.currentTimeMillis()
        val active = windows().any { now in it }
        try {
            nm.setAutomaticZenRuleState(
                ruleId,
                Condition(CONDITION_ID, "", if (active) Condition.STATE_TRUE else Condition.STATE_FALSE)
            )
        } catch (e: SecurityException) {
            Logger.e(LogTags.DND, "setAutomaticZenRuleState ohne Freigabe aufgerufen", e)
        }
    }

    suspend fun scheduleNextTransition() {
        if (!isSupported()) return
        val am = alarmManager()
        val pi = buildPendingIntent()
        val next = computeNextTransition(System.currentTimeMillis())
        if (next == null) {
            am.cancel(pi)
            return
        }
        val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        if (canBeExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        Logger.d(LogTags.DND, "Naechster DND-Wechsel geplant: ${java.util.Date(next)}")
    }

    // --- Fenster-Berechnung ---

    private suspend fun windows(): List<LongRange> {
        val toggles = prefs.togglesNow()
        if (!toggles.followDimmerEnabled && !toggles.duringShiftEnabled) return emptyList()

        val out = mutableListOf<LongRange>()

        if (toggles.followDimmerEnabled) {
            out += dimSchedule.previewTimeline().map { it.range }
        }

        if (toggles.duringShiftEnabled) {
            val alarms = alarmUseCase.getAllAlarms().getOrElse {
                Logger.w(LogTags.DND, "Alarm-Bestand nicht lesbar - keine Dienstzeit-Fenster (fail-open)")
                emptyList()
            }.filter { it.isActive }
            val excluded = prefs.shiftExcludedShiftsNow()
            val slots = alarms.map {
                DndShiftSpanResolver.AlarmSlot(it.shiftName, it.shiftStartTime, it.shiftEndTime)
            }
            out += DndShiftSpanResolver.buildShiftSpans(slots, excluded)
        }
        return out
    }

    private suspend fun computeNextTransition(now: Long): Long? =
        windows()
            .flatMap { listOf(it.first, it.last) }
            .filter { it > now }
            .minOrNull()

    // --- AutomaticZenRule-Verwaltung ---

    /**
     * Liefert die registrierte Regel-ID; registriert bei Bedarf neu (Erstregistrierung ODER falls
     * der Nutzer die Regel extern - z. B. in den System-Einstellungen - geloescht hat:
     * [NotificationManager.getAutomaticZenRule] liefert dann null fuer eine gespeicherte, aber nicht
     * mehr existierende ID).
     */
    private suspend fun ensureZenRule(nm: NotificationManager): String? {
        // Direkter SDK_INT-Check (nicht nur ueber isSupported()) noetig, damit Lint den Aufruf
        // von buildAutomaticZenRule() (@RequiresApi R) hier als abgesichert erkennt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val storedId = prefs.zenRuleIdNow()
        if (storedId.isNotBlank() && nm.getAutomaticZenRule(storedId) != null) {
            return storedId
        }
        return try {
            val newId = nm.addAutomaticZenRule(buildAutomaticZenRule())
            prefs.setZenRuleId(newId)
            newId
        } catch (e: SecurityException) {
            Logger.e(LogTags.DND, "addAutomaticZenRule ohne Freigabe aufgerufen", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildAutomaticZenRule(): AutomaticZenRule {
        val component = ComponentName(context, MainActivity::class.java)
        // Prioritaet + Anrufer-Ausnahme: wiederholte Anrufer (zweiter Anruf kurz nacheinander)
        // duerfen durch, alles andere ist stumm - Erreichbarkeit im Notfall bleibt gewahrt. Der
        // Wecker selbst ist davon unabhaengig (AlarmSoundService.setBypassDnd(true) umgeht JEDE
        // DND-Konfiguration, auch diese hier).
        val policy = ZenPolicy.Builder()
            .allowRepeatCallers(true)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowReminders(false)
            .allowEvents(false)
            .allowAlarms(false)
            .allowMedia(false)
            .allowSystem(false)
            .build()
        return AutomaticZenRule(
            RULE_NAME,
            component,
            component,
            CONDITION_ID,
            policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
    }

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, DndScheduleReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQ_DND_TICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
