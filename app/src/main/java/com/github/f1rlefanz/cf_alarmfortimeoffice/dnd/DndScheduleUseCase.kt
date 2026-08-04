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
import java.time.ZoneId
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
 * Rufbereitschaft ([DndPrefs.onCallShifts]/[DndPrefs.onCallCutoffMinutes]) ist KEINE dritte
 * Fenster-Quelle mit eigener Policy, sondern klippt beide obigen Quellen als letzten Schritt auf
 * einen festen Cutoff am On-Call-Tag ([DndOnCallCutoffResolver.clip]) - dieselbe Zen-Regel gilt bis
 * dahin unveraendert weiter.
 *
 * Nutzt bewusst eine selbst registrierte [AutomaticZenRule] statt rohem
 * `NotificationManager.setInterruptionFilter()`: die Regel ist fuer den Nutzer sichtbar unter
 * Einstellungen -> Ton -> Nicht stoeren -> Zeitplaene, mit eigener [ZenPolicy] statt der globalen,
 * manuell gesetzten Policy zu ueberschreiben - koexistiert so mit dem manuellen DND des Nutzers und
 * fremden Automatisierungen (Bixby/Tasker), statt sie zu ueberschreiben. Nur ab API 30 verfuegbar
 * (configurationActivity-Ownership ohne ConditionProviderService), siehe [isSupported].
 *
 * Was konkret stummgeschaltet wird, ist NICHT hart codiert, sondern kommt aus [DndPrefs.Policy] -
 * siehe deren Klassenkommentar fuer den Vorfall (Medien/Wecker per Default blockiert), der zu
 * dieser Entscheidung fuehrte. Beobachtung vom 28.07.2026: ist gleichzeitig ein ANDERER,
 * permissiverer Systemmodus aktiv (z. B. Android/Herstellers eigene "Schlafenszeit"), gewinnt fuer
 * eine Kategorie offenbar die freizuegigere Einstellung ueber alle aktiven Regeln hinweg - unsere
 * Regel kann eine Kategorie also nicht zuverlaessig strenger machen, als es die am wenigsten
 * strenge gleichzeitig aktive Regel erlaubt. Kein Bug in diesem Code, sondern wie Android mehrere
 * gleichzeitig aktive Zen-Regeln konsolidiert - nicht durch eigenen Code umgehbar.
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

    /**
     * Setzt die Zen-Regel-Bedingung auf inaktiv und storniert den rollenden Tick-Alarm - registriert
     * die Regel bewusst NICHT ab ([NotificationManager.removeAutomaticZenRule]), sie bleibt sichtbar
     * und inaktiv liegen. Ein spaeteres [enable] findet sie ueber [ensureZenRule] wieder und muss
     * nichts neu anlegen.
     */
    suspend fun disable() {
        if (!isSupported()) return
        val nm = notificationManager()
        val ruleId = prefs.zenRuleIdNow()
        if (ruleId.isNotBlank()) {
            try {
                nm.setAutomaticZenRuleState(ruleId, Condition(CONDITION_ID, "", Condition.STATE_FALSE))
            } catch (e: SecurityException) {
                Logger.e(LogTags.DND, "disable() ohne Freigabe aufgerufen", e)
            }
        }
        alarmManager().cancel(buildPendingIntent())
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
        val onCallShifts = prefs.onCallShiftsNow()
        if (!toggles.followDimmerEnabled && !toggles.duringShiftEnabled && onCallShifts.isEmpty()) {
            return emptyList()
        }

        val out = mutableListOf<LongRange>()

        if (toggles.followDimmerEnabled) {
            out += dimSchedule.previewTimeline().map { it.range }
        }

        // Alarme werden auch NUR fuer den On-Call-Cutoff geholt (unabhaengig von
        // duringShiftEnabled) - der Cutoff klippt auch das "Folgt dem Dimmer"-Fenster.
        val alarms = if (toggles.duringShiftEnabled || onCallShifts.isNotEmpty()) {
            alarmUseCase.getAllAlarms().getOrElse {
                Logger.w(LogTags.DND, "Alarm-Bestand nicht lesbar - keine Dienstzeit-/On-Call-Fenster (fail-open)")
                emptyList()
            }.filter { it.isActive }
        } else {
            emptyList()
        }

        if (toggles.duringShiftEnabled) {
            val excluded = prefs.shiftExcludedShiftsNow()
            val slots = alarms.map {
                DndShiftSpanResolver.AlarmSlot(it.shiftName, it.shiftStartTime, it.shiftEndTime)
            }
            out += DndShiftSpanResolver.buildShiftSpans(slots, excluded)
        }

        if (onCallShifts.isNotEmpty() && out.isNotEmpty()) {
            val cutoffMinutes = prefs.onCallCutoffMinutesNow()
            val cutoffSlots = alarms.map {
                DndOnCallCutoffResolver.AlarmSlot(it.shiftName, it.shiftStartTime)
            }
            val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
                cutoffSlots, onCallShifts, cutoffMinutes, ZoneId.systemDefault()
            )
            return DndOnCallCutoffResolver.clip(out, cutoffs)
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
     * mehr existierende ID) und aktualisiert eine bereits bestehende Regel IMMER mit der aktuellen
     * [DndPrefs.Policy] - sonst wirkt eine Options-Aenderung des Nutzers erst nach einer
     * Neuinstallation, weil die einmal registrierte Regel ihre alte Policy sonst dauerhaft behaelt.
     */
    private suspend fun ensureZenRule(nm: NotificationManager): String? {
        // Direkter SDK_INT-Check (nicht nur ueber isSupported()) noetig, damit Lint den Aufruf
        // von buildAutomaticZenRule() (@RequiresApi R) hier als abgesichert erkennt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val storedId = prefs.zenRuleIdNow()
        val rule = buildAutomaticZenRule()
        return try {
            if (storedId.isNotBlank() && nm.getAutomaticZenRule(storedId) != null) {
                nm.updateAutomaticZenRule(storedId, rule)
                storedId
            } else {
                val newId = nm.addAutomaticZenRule(rule)
                prefs.setZenRuleId(newId)
                newId
            }
        } catch (e: SecurityException) {
            Logger.e(LogTags.DND, "Zen-Regel-Verwaltung ohne Freigabe aufgerufen", e)
            null
        }
    }

    /**
     * Baut die Zen-Regel aus der Nutzer-Policy ([DndPrefs.Policy]) - bewusst NICHTS hart codiert,
     * siehe Klassenkommentar von [DndPrefs.Policy]. Der Wecker selbst ist von `blockAlarms`
     * unabhaengig: `AlarmSoundService.setBypassDnd(true)` umgeht JEDE DND-Konfiguration, auch diese
     * hier - `blockAlarms` betrifft nur FREMDE Wecker/Alarm-Apps, die der Nutzer bewusst zulassen
     * oder stummschalten kann.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun buildAutomaticZenRule(): AutomaticZenRule {
        val component = ComponentName(context, MainActivity::class.java)
        val p = prefs.policyNow()
        val policy = ZenPolicy.Builder()
            .allowRepeatCallers(p.allowRepeatCallers)
            .allowCalls(if (p.blockCalls) ZenPolicy.PEOPLE_TYPE_NONE else ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowMessages(if (p.blockMessages) ZenPolicy.PEOPLE_TYPE_NONE else ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowConversations(if (p.blockConversations) ZenPolicy.CONVERSATION_SENDERS_NONE else ZenPolicy.CONVERSATION_SENDERS_ANYONE)
            .allowReminders(!p.blockReminders)
            .allowEvents(!p.blockEvents)
            .allowAlarms(!p.blockAlarms)
            .allowMedia(!p.blockMedia)
            .allowSystem(!p.blockSystem)
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
