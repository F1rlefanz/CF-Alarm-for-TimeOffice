package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.laeuftBeiTageslicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.passtAufSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import java.time.LocalDate

/*
 * Licht (Hue) fuer die Tagesleiste (Umbau #195, SPEC E8 und 3.3 Punkt 3) - AUS DEN HEUTIGEN
 * HUE-REGELN, ohne eine zweite Zeitrechnung. Der Adapter waehlt die Regeln mit denselben Funktionen
 * wie die Laufzeit, laesst Beginn und Ende von Hue rechnen und setzt sie um den Weckpunkt der
 * Leiste (`weckpunktMin`, #193) herum in Minuten der Leiste.
 *
 * Die Eingabe ist dieselbe wie bei der Leiste ([TagesleisteEingabe]); `licht` darin ist hier ohne
 * Bedeutung. Der Aufrufer setzt das Ergebnis danach selbst ein: `eingabe.copy(licht = licht(...))`.
 */

/** Die Vorlauf-Rechnung der Hue-Regeln: `IHueRuleUseCase.getPreAlarmSunriseLeadMinutes`. */
typealias VorlaufMinuten = (regeln: List<HueSchedule>, schichtname: String, weckzeit: Long?, stand: TageslichtStand?) -> Int?

/**
 * Der Tag, dessen Sonne die Tageslicht-Bedingung (#140) fuer den Mustertag liest. Der Mustertag der
 * Leiste ist ein erfundener Normaltag ohne Datum - die Sonne aber haengt am echten. Der Aufrufer
 * sagt, welchen er meint (etwa den heutigen).
 */
class TageslichtAmMustertag(val datum: LocalDate, val stand: TageslichtStand)

/**
 * Die heutigen Hue-Einstellungen, aus denen die Leiste rechnet.
 *
 * @param hueKonfiguriert `HueBridgeConnectionManager.hasStoredBridge()` - "eingerichtet", NICHT
 *   "verbunden" (wie `HueSmartScheduler`). Ohne Bridge geht kein Licht an, egal wie viele Regeln es gibt.
 * @param regeln alle Hue-Regeln, wie `IHueConfigRepository.getScheduleRules()` sie liefert. Ein
 *   gescheitertes Lesen DARF hier nicht als `emptyList()` ankommen: "keine Licht-Spur" waere dann eine
 *   Behauptung ueber Regeln, die man nie gesehen hat (Skill cfalarm-persistenz-und-auth). Der Aufrufer
 *   ruft den Adapter bei einem Lesefehler gar nicht auf.
 * @param tageslicht `null` = kein Standort, keine Sonne: die Tageslicht-Bedingung (`hellAb`) wird dann
 *   NICHT behauptet, jede Regel zaehlt - wie die Laufzeit ohne Ort (fail-safe: Licht).
 */
data class LichtEinstellungen(
    val hueKonfiguriert: Boolean,
    val regeln: List<HueSchedule>,
    val tageslicht: TageslichtAmMustertag? = null
)

/**
 * Die Licht-Bereiche eines Mustertags dieses Typs, so wie Hue sie heute ausfuehrt. Rein: gleiche
 * Eingabe, gleiches Ergebnis.
 *
 * WANN ES KEINE SPUR GIBT (jeweils `emptyList()`):
 * - `still`: der `AlarmReceiver` ueberspringt bei einem stillen Wecker saemtliche Hue-Regeln
 *   (`AlarmReceiver`, `isSilentAlarm`), und `HueSmartScheduler` plant keinen Vorab-Sonnenaufgang
 *   (`sonnenaufgangsKandidaten`). Erst Etappe 4 aendert das ("Lichtwecker").
 * - Wecker aus, Krank, Ignorieren, ungueltige Weckzeit: `weckpunktMin()` liefert keinen Punkt. Das
 *   unterscheidet sich von #194: Dimmer und DND behalten die Weckzeit als Anker, Hue-Regeln laufen nur
 *   zu einem ALARM (SPEC E8: "Ohne Weckzeit gibt es kein Licht").
 * - keine Bridge eingerichtet.
 *
 * WELCHE REGEL GILT: `enabled` und `passtAufSchicht` - der eigene Typname ODER "ALL", und beide
 * feuern GEMEINSAM (`findApplicableRules`). Mit [LichtEinstellungen.tageslicht] zusaetzlich
 * `laeuftBeiTageslicht` am Wecktag des Weckpunkts (der Weckpunkt kann am Vortag liegen). Eine Regel
 * ohne schaltbares Ziel (keine Lampe/Gruppe, kein "an", keine Szene) zaehlt nicht.
 *
 * BEGINN UND ENDE:
 * - `SONNENAUFGANG` von Weckzeit minus Vorlauf bis zum Wecker: der Vorlauf kommt aus
 *   `getPreAlarmSunriseLeadMinutes` ([vorlaufMinuten]; die laengste "vor dem Wecker"-Rampe). Eine
 *   Rampe, die ERST zum Wecker startet (`startBeforeAlarm = false`), hat keinen Vorlauf.
 * - `AN` ab dem Wecker (auch bei einer Rampe, die erst dann beginnt - das Licht geht dort an). Das
 *   Ende ist der SPAETESTE Auto-Aus (`HueRuleUseCase.autoOffTargetsOf`, zaehlt die Rampe nach dem
 *   Wecker mit). Hat eine Regel KEIN Auto-Aus, bleibt ihr Licht an: der Bereich laeuft dann bis
 *   [TAGESLEISTE_MIN], und die Leiste sagt dort kein "Licht aus" (ihr Ereignis liegt ausserhalb des
 *   Fensters) - ein stilles Verschwinden einer gueltigen Regel waere falscher.
 *
 * NICHT ABGEBILDET: "Auto-Aus spaetestens bei Tageslicht" (`ausBeiHell`, #143) kuerzt das Ende nicht.
 * `autoOffTargetsOf` schreibt beim Vorziehen eine WARN-Zeile ins Release-Log, und die Leiste rechnet
 * bei jeder Eingabe neu - das Log bekaeme laufend ein "Auto-Aus vorgezogen", das nie passiert ist.
 * Die Leiste zeigt deshalb die Dauer als Obergrenze (offen: eigenes Issue).
 *
 * @param vorlaufMinuten MUSS `IHueRuleUseCase.getPreAlarmSunriseLeadMinutes` sein - dieselbe Auswahl
 *   und derselbe Massstab wie die Vorab-Rampe; eine Nachbildung waere eine zweite Wahrheit.
 */
fun licht(
    eingabe: TagesleisteEingabe,
    einstellungen: LichtEinstellungen,
    vorlaufMinuten: VorlaufMinuten
): List<LichtBereich> {
    if (eingabe.still || !einstellungen.hueKonfiguriert) return emptyList()
    val wecker = weckpunktMin(eingabe.kategorie, eingabe.weckerAn, eingabe.weckzeit, eingabe.dienstzeit)
        ?: return emptyList()
    val wecktag = einstellungen.tageslicht?.let { Wecktag(weckMillis(it, wecker), it.stand) }

    val gelten = einstellungen.regeln.filter {
        it.enabled &&
            it.passtAufSchicht(eingabe.name) &&
            (wecktag == null || it.laeuftBeiTageslicht(wecktag.weckMs, wecktag.stand)) &&
            it.schaltetLicht()
    }
    if (gelten.isEmpty()) return emptyList()

    // Die Tageslicht-Bedingung ist oben schon angewandt; die Liste kommt gefiltert, also ohne Weckzeit.
    val vorlauf = vorlaufMinuten(gelten, eingabe.name, null, null)?.takeIf { it > 0 }
    val offen = gelten.any { HueRuleUseCase.autoOffTargetsOf(listOf(it)).isEmpty() }
    val ende = if (offen) TAGESLEISTE_MIN else wecker + HueRuleUseCase.autoOffTargetsOf(gelten).maxOf { it.delayMinutes }

    return buildList {
        if (vorlauf != null) add(LichtBereich(wecker - vorlauf, wecker, LichtArt.SONNENAUFGANG))
        add(LichtBereich(wecker, ende, LichtArt.AN))
    }
}

private class Wecktag(val weckMs: Long, val stand: TageslichtStand)

/** Der Wecker als Zeitpunkt auf dem echten Tag: Minute 360 ist dessen 00:00, kleinere Minuten liegen davor. */
private fun weckMillis(tageslicht: TageslichtAmMustertag, weckerMin: Int): Long =
    tageslicht.datum.atStartOfDay()
        .plusMinutes((weckerMin - MUSTERTAG_BEGINN_MIN).toLong())
        .atZone(tageslicht.stand.zone)
        .toInstant()
        .toEpochMilli()

/**
 * Hat die Regel ein Ziel, das Licht schaltet? Sonnenaufgang: eine Lampe oder Gruppe
 * (`HueSunriseExecutor.sunriseTargets` ueberspringt Szenen). Sonst "an" oder eine Szene - dieselbe
 * Unterscheidung wie `executeActionsWithAutoRevert` (`on == true || sceneId != null`).
 */
private fun HueSchedule.schaltetLicht(): Boolean =
    if (sunrise?.enabled == true) {
        lightActions.any { !it.isScene && it.targetId.isNotBlank() }
    } else {
        lightActions.any { (it.on == true || it.isScene) && it.targetId.isNotBlank() }
    }
