package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import java.util.Locale

/**
 * Bridge-side schedule — a `/api/<username>/schedules` resource that lives ON the Hue bridge.
 *
 * NICHT verwechseln mit [HueScheduleRule] (alias [HueSchedule]): das ist die APP-eigene Regel
 * im DataStore ("bei Frühschicht Gruppe 2 an, nach 30min aus"). Dieser Typ hier ist der
 * Zeitplan, den die Bridge selbst ausführt. Zwei verschiedene Dinge, deshalb zwei Namen.
 *
 * WARUM ÜBERHAUPT: Ein Auto-Off, das das Handy per WorkManager fahren muss, scheitert, sobald
 * das Handy nicht mehr im Heim-WLAN ist. Die Bridge steht dagegen per Definition im richtigen
 * Netz. Legt die App den Zeitplan zur Weckzeit auf der Bridge ab, schaltet die Bridge selbst
 * aus — egal wo das Handy dann ist.
 *
 * Verifiziert gegen die echte Bridge (BSB002, Firmware 1.78.0, apiversion 1.78.0) am
 * 15.07.2026 — siehe [BridgeTimer] für die Details, die dabei herauskamen.
 */
data class BridgeScheduleCommand(
    val address: String,
    val method: String,
    val body: Map<String, Any>
)

/** Anlege-Payload für POST /api/<username>/schedules. */
data class BridgeScheduleCreate(
    val name: String,
    val description: String,
    val command: BridgeScheduleCommand,
    val localtime: String,
    val autodelete: Boolean = true
)

/**
 * Zeitplan, wie die Bridge ihn zurückliefert. Nur die Felder, die wir auswerten —
 * die Bridge liefert zusätzlich `command`, `created`, `starttime`, `time`, `recycle`.
 */
data class BridgeSchedule(
    val name: String? = null,
    val description: String? = null,
    val localtime: String? = null,
    val status: String? = null,
    val autodelete: Boolean? = null
)

/**
 * Baut die Timer-Zeitmuster und Namen für unsere bridge-seitigen Auto-Off-Zeitpläne.
 *
 * TIMER (`PThh:mm:ss`) STATT ABSOLUTER ZEIT (`YYYY-MM-DDThh:mm:ss`) — Absicht:
 * Der Zeitplan entsteht zur Weckzeit, gewollt ist "in +30 Minuten". Genau das sagt ein Timer,
 * und er zählt auf der Uhr der BRIDGE herunter. Eine absolute Zeit müsste das Handy rechnen
 * und die Bridge in IHRER Zeitzone interpretieren: zwei Uhren, die auseinanderlaufen können
 * (falsch gestellte Bridge-Zeitzone, Sommerzeitsprung, Drift). Der Timer kennt dieses Problem
 * nicht. Nebeneffekt: die widersprüchlich dokumentierte Wochentagsmaske `W[bbb]` brauchen wir
 * gar nicht erst.
 */
object BridgeTimer {

    /**
     * Präfix, an dem wir UNSERE Zeitpläne auf der Bridge wiedererkennen.
     *
     * Die Bridge ist geteiltes Gebiet: dort liegen auch Zeitpläne der Hue-App und der
     * Dimmer-Schalter (real gesehen: zwei "Hue dimmer switch 1"-Timer). Aufgeräumt wird
     * ausschließlich, was mit diesem Präfix beginnt.
     */
    const val NAME_PREFIX = "CFAlarm"

    /** `name` der Bridge: max. 32 Zeichen (API-Spezifikation). */
    private const val MAX_NAME_LENGTH = 32

    /** `description` der Bridge: max. 64 Zeichen (API-Spezifikation). */
    private const val MAX_DESCRIPTION_LENGTH = 64

    /**
     * Ein Timer kann höchstens `PT23:59:59` laufen. Der Auto-Off-Regler geht bis 90 Minuten,
     * dazu höchstens 90 Minuten Sonnenaufgangs-Versatz — real also nie über ~3h. Die Klemme
     * ist reine Absicherung gegen eine künftig großzügigere UI.
     */
    const val MAX_TIMER_MINUTES = 23 * 60 + 59

    /**
     * Wandelt eine Verzögerung in Minuten in das Timer-Zeitmuster der Bridge.
     *
     * [Locale.ROOT] ist Pflicht: `"%02d".format(…)` würde unter z.B. arabischem Systemgebiet
     * östlich-arabische Ziffern erzeugen, die die Bridge nicht parst.
     */
    fun timerPattern(delayMinutes: Int): String {
        val clamped = delayMinutes.coerceIn(1, MAX_TIMER_MINUTES)
        return String.format(Locale.ROOT, "PT%02d:%02d:00", clamped / 60, clamped % 60)
    }

    /** Resource-Pfad OHNE Username — den setzt das Repository davor, es besitzt die Verbindung. */
    fun resourcePath(targetId: String, isGroup: Boolean): String =
        if (isGroup) "/groups/$targetId/action" else "/lights/$targetId/state"

    fun scheduleName(targetId: String, isGroup: Boolean): String =
        "$NAME_PREFIX Auto-Off ${if (isGroup) "G" else "L"}$targetId".take(MAX_NAME_LENGTH)

    fun scheduleDescription(shiftName: String, delayMinutes: Int): String =
        "Auto-Off $shiftName +${delayMinutes}min".take(MAX_DESCRIPTION_LENGTH)

    /** Gehört dieser Zeitplan uns? */
    fun isOwnSchedule(schedule: BridgeSchedule): Boolean =
        schedule.name?.startsWith(NAME_PREFIX) == true
}
