package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.delay

/** Versuchszahl und Wartezeit von [loescheDauerhaftMitNachfassen]. */
internal object DauerhaftesLoeschen {
    /** Loeschversuche je Wecker, inklusive des ersten. */
    const val VERSUCHE = 2
    const val WARTEZEIT_MS = 250L
}

/**
 * Loescht einen Wecker DAUERHAFT und fasst bei einem Fehlschlag genau einmal nach - gemeinsam fuer
 * "Naechsten Wecker ueberspringen" (`AlarmSkipUseCase`) und "Tag freigeben" (`TagFreigabeUseCase`).
 *
 * Der haeufige Fall ist ein voruebergehender DataStore-Schreibfehler; ihn sofort in die Ruecknahme
 * laufen zu lassen, wuerde dem Nutzer ein funktionierendes Ueberspringen bzw. Freigeben ohne Not
 * verweigern. Bleibt es beim Fehlschlag, entscheidet die Aufrufstelle (Ruecknahme, laut scheitern).
 *
 * Enthaelt bewusst KEIN `cancelSystemAlarm()`: die Aufrufer canceln VORHER - erst cancel, dann
 * delete, sonst entsteht ein armierter Alarm, den niemand mehr kennt.
 *
 * @param versuchsText Log-Praefix des Vorgangs, z.B. "SKIP: Loeschen des uebersprungenen Alarms 7"
 * @param nomen "Alarm" bzw. "Wecker" - fuer den Fehlertext bei nur fluechtigem Loeschen
 */
internal suspend fun IAlarmRepository.loescheDauerhaftMitNachfassen(
    alarmId: Int,
    logTag: String,
    versuchsText: String,
    nomen: String
): Result<Unit> {
    var ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId, nomen)
    var versuch = 1
    while (ergebnis.isFailure && versuch < DauerhaftesLoeschen.VERSUCHE) {
        Logger.w(
            logTag,
            "⚠️ $versuchsText fehlgeschlagen " +
                "(Versuch $versuch/${DauerhaftesLoeschen.VERSUCHE}) - wird wiederholt",
            ergebnis.exceptionOrNull()
        )
        delay(DauerhaftesLoeschen.WARTEZEIT_MS)
        ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId, nomen)
        versuch++
    }
    return ergebnis
}

/**
 * Loescht einmal und prueft, ob das Loeschen ueberhaupt dauerhaft sein KONNTE.
 *
 * `AlarmRepository.deleteAlarm()` meldet auch dann Erfolg, wenn nur der Arbeitsspeicher
 * geraeumt wurde: bei gesperrter Persistenz (gescheiterter Init-Load) kehrt
 * `persistToDataStore()` sofort zurueck, ohne zu schreiben und ohne zu werfen. Ohne diese
 * Nachfrage spraenge die Ruecknahme beim Aufrufer genau im wichtigsten Fall nicht an -
 * Preferences-Datei und Direct-Boot-Spiegel behielten den Alarm, der BootReceiver armierte ihn
 * nach einem naechtlichen Neustart ungefiltert wieder, und der "uebersprungene" bzw. "freie"
 * Wecker klingelte doch. Die Sperre kann zwischen der Vorpruefung des Aufrufers und hier auch erst
 * entstehen (ein Nachlade-Versuch scheitert nebenlaeufig), deshalb wird sie hier erneut gefragt.
 */
private suspend fun IAlarmRepository.loescheUndPruefeDauerhaftigkeit(alarmId: Int, nomen: String): Result<Unit> {
    val ergebnis = deleteAlarm(alarmId)
    if (ergebnis.isFailure) return ergebnis
    return if (isPersistenceBlocked()) {
        Result.failure(
            IllegalStateException(
                "$nomen $alarmId wurde nur aus dem Arbeitsspeicher entfernt - die Persistenz " +
                    "ist gesperrt, Alarm-Bestand und Direct-Boot-Spiegel behalten ihn"
            )
        )
    } else {
        ergebnis
    }
}
