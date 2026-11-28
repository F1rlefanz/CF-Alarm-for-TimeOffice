package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/*
 * Die QUELLEN der Tagesleiste (Umbau #196, SPEC 3.3 Punkt 3): was `ShiftViewModel.tagesleisteQuellen`
 * den Adaptern aus #193 bis #195 in die Hand gibt - die Schicht-Konfiguration (seit #219 liegen
 * Dimmen und "Nicht stoeren" am Typ), die Lichtquelle (seit #253 die Regeln am Typ samt "Bridge
 * eingerichtet"), dazu die Schichtspannen und der Tageslicht-Stand.
 *
 * LESEFEHLER WERDEN SICHTBAR. Jede Quelle ist ein [Quelle]: [Quelle.Bekannt] mit dem Wert, oder
 * [Quelle.Unbekannt], wenn sie sich nicht lesen liess. Eine unlesbare Quelle wird NIE zur leeren
 * Liste oder zu Standardwerten - "kein Typ hat ein Fenster" hiesse "kein Dimmen" und waere eine
 * Behauptung ueber Einstellungen, die man nie gesehen hat (Pflichtregel "Kein Fehler darf als leeres
 * Erfolgsergebnis durchrutschen"; der Flow `shiftConfig` degradiert auf Standardwerte und taugt hier
 * deshalb nicht, gelesen wird ueber `getCurrentShiftConfig()`). `null` als
 * Zustand des ViewModels heisst dagegen nur "noch nicht gelesen" - das ist kein Fehler.
 *
 * Diese Datei kennt weder ViewModel noch Compose: sie haelt die Zustaende, die Abhaengigkeiten der
 * Spuren von den Quellen ([TagesleisteQuellen.stand]) und die zwei Fangstellen, ueber die das
 * ViewModel jede Quelle fuehrt.
 */

/** Eine Quelle der Leiste: gelesen oder nicht lesbar. Dazwischen gibt es nichts, vor allem kein "leer". */
sealed interface Quelle<out T> {
    data class Bekannt<out T>(val wert: T) : Quelle<T>

    /** Die Quelle liess sich nicht lesen. Der Grund steht im Release-Log (WARN), nicht hier. */
    data object Unbekannt : Quelle<Nothing>
}

/**
 * Die Lichtregeln am Typ und ob eine Bridge eingerichtet ist. Bekannt nur, wenn BEIDES gelesen ist.
 *
 * @param konfiguriert "eingerichtet" (IP und Benutzer gespeichert), NICHT "verbunden" - so rechnet auch
 *   `HueSmartScheduler` (`hasStoredBridge()`, dort nur der IP-Schluessel; `saveBridgeConfig` schreibt
 *   beide zusammen). Strikt aus dem `@HueDataStore` gelesen (`bridgeEingerichtetOderFehler`, #210),
 *   ohne den Hue-Graphen (OkHttp) zu bauen.
 * @param regeln die Regeln aller Typen aus der Naht der Laufzeit (`TypLichtQuelle(config).regeln`,
 *   #253), aus der strikt gelesenen Konfiguration.
 * @param vorlaufMinuten `IHueRuleUseCase.getPreAlarmSunriseLeadMinutes`, wie `licht()` es verlangt.
 *   Greift erst beim AUFRUF auf den Hue-Use-Case zu: `licht()` ruft sie nur, wenn eine Bridge
 *   eingerichtet ist und eine Regel passt.
 */
data class LichtQuelle(
    val konfiguriert: Boolean,
    val regeln: List<HueSchedule>,
    val vorlaufMinuten: VorlaufMinuten
)

/** Ob die Spur etwas Gelesenes zeigt - oder sagen muss, dass sie es nicht weiss. */
enum class SpurStand { BEKANNT, UNBEKANNT }

/**
 * Alles, was die Tagesleiste aus dem Bestand braucht - je Quelle gelesen oder unbekannt.
 *
 * [tageslicht] ist KEINE [Quelle] und haengt an keiner Spur ([stand]): ohne Ort heisst fail-safe "keine
 * Einschraenkung" (Licht an, voller Dimm-Nachlauf), wie zur Laufzeit - das gilt auch, wenn das Lesen scheitert.
 *
 * Ausserdem NICHT enthalten, weil es nicht aus dem Bestand kommt: `jetzt` und Zone fuer `dienstzeitAus` und
 * den Tag, dessen Sonne gilt - die liefert der Aufrufer.
 */
data class TagesleisteQuellen(
    val typen: Quelle<ShiftConfig>,
    val licht: Quelle<LichtQuelle>,
    val spannen: Quelle<List<ShiftSpan>>,
    val tageslicht: TageslichtStand = TageslichtStand.ohneOrt()
) {
    /**
     * Ob [spur] sich aus gelesenen Quellen rechnen laesst. Eine Spur ist unbekannt, sobald EINE Quelle
     * fehlt, von der sie abhaengt:
     *
     * - Eintrag, Ruf-Abfrage, Wecker: die Schichtspannen.
     * - Dimmen und Nicht stoeren: die Konfiguration ([typen], dort liegen seit #219 beide am Typ,
     *   "Solange gedimmt wird" ueber dieselben Fenster) und die Spannen.
     * - Licht: die Lichtquelle ([licht]; sie braucht Konfiguration UND Bridge-Einrichtung) und die Spannen.
     *
     * DIE SPANNEN HAENGEN AN JEDER SPUR: sie liefern die Dienstzeit des Typs, und die legt fest, WO der
     * Weckpunkt liegt (ein Nachtdienst weckt am Vortag, `weckzeitpunkt()`) - und mit ihm Dimmen, Licht
     * und "Nicht stoeren". Ohne lesbare Spannen waere die Leiste auf einem erfundenen Normaltag gerechnet.
     * Das ist etwas anderes als "der Typ hat keine Spanne": dann ist die Quelle bekannt und leer.
     */
    fun stand(spur: Spur): SpurStand {
        if (spannen !is Quelle.Bekannt) return SpurStand.UNBEKANNT
        val bekannt = when (spur) {
            Spur.EINTRAG, Spur.RUF_ABFRAGE, Spur.WECKER -> true
            Spur.DIMMEN, Spur.NICHT_STOEREN -> typen is Quelle.Bekannt
            Spur.LICHT -> licht is Quelle.Bekannt
        }
        return if (bekannt) SpurStand.BEKANNT else SpurStand.UNBEKANNT
    }
}

/**
 * Fangstelle fuer eine Quelle, die als FLOW wirft: der Fehler wird [Quelle.Unbekannt] (und WARN im
 * Release-Log), nicht leer. Gehoert an JEDE Quelle EINZELN, VOR dem `combine` - ein einziger Fang dahinter
 * liesse bei einem Fehler alle Spuren zugleich ausfallen. Der Flow endet danach; eine neue Lesung gibt es
 * mit dem naechsten Abo (`WhileSubscribed`).
 *
 * Eine `CancellationException` wird weitergeworfen: sie sagt nichts ueber den Bestand aus.
 */
internal fun <T> Flow<Quelle<T>>.fehlerAlsUnbekannt(quelle: String): Flow<Quelle<T>> = catch { e ->
    if (e is CancellationException) throw e
    Logger.w(LogTags.SHIFT, "Tagesleiste: Quelle '$quelle' nicht lesbar - ihre Spuren zeigen 'unbekannt'", e)
    emit(Quelle.Unbekannt)
}

/**
 * Fangstelle fuer eine Quelle, die ein [Result] liefert (Konfiguration, Bridge-Einrichtung,
 * Schichtspannen): ein Fehlschlag wird [Quelle.Unbekannt] (und WARN im Release-Log), ein Erfolg
 * [Quelle.Bekannt] mit `wert(it)`.
 *
 * Eine `CancellationException` im Fehlschlag wird weitergeworfen: ein Leser, der `Exception` faengt,
 * verpackt auch sie in ein `Result.failure`.
 */
internal fun <T, R> Result<T>.alsQuelle(quelle: String, wert: (T) -> R): Quelle<R> = fold(
    onSuccess = { Quelle.Bekannt(wert(it)) },
    onFailure = { e ->
        if (e is CancellationException) throw e
        Logger.w(LogTags.SHIFT, "Tagesleiste: Quelle '$quelle' nicht lesbar - ihre Spuren zeigen 'unbekannt'", e)
        Quelle.Unbekannt
    }
)
