package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geschäftslogik über den Dimm-Regeln: CRUD plus die Auswahl der passenden Regel je Tag.
 *
 * Auswahl-Reihenfolge (mostspecific-wins, analog `ShiftConfig.findDefinitionFor()` und
 * `HueRuleUseCase.findApplicableRules`):
 *  - Schicht-Tag: exakter Definitionsname → sonst [DimRule.SHIFT_UNIVERSAL] → sonst nichts.
 *  - Freier Tag:  [DimRule.SHIFT_FREE] → sonst [DimRule.SHIFT_UNIVERSAL] → sonst nichts.
 *
 * Eine gefundene Regel mit leerer Fensterliste unterdrückt bewusst das Dimmen (überschreibt so
 * die UNIVERSAL-Regel) – das ist die Nachtdienst-Ausnahme.
 */
@Singleton
class DimRuleUseCase @Inject constructor(
    private val repository: DimRuleRepository
) {
    val rules: Flow<List<DimRule>> = repository.rules

    suspend fun getAllRules(): List<DimRule> = repository.getRules()
    suspend fun saveRule(rule: DimRule) = repository.upsert(rule)
    suspend fun deleteRule(id: String) = repository.delete(id)

    /** Passende Regel für einen erkannten Schicht-Namen, oder null. */
    fun findRuleForShift(shiftName: String, all: List<DimRule>): DimRule? {
        val enabled = all.filter { it.enabled }
        return enabled.firstOrNull { it.shiftPattern.equals(shiftName, ignoreCase = true) }
            ?: enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    /** Passende Regel für einen freien Tag (keine erkannte Schicht), oder null. */
    fun findRuleForFreeDay(all: List<DimRule>): DimRule? {
        val enabled = all.filter { it.enabled }
        return enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_FREE }
            ?: enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    /**
     * Zieht die Regeln einer UMBENANNTEN Schichtdefinition auf den neuen Namen nach.
     * Liefert die Anzahl der geänderten Regeln, oder einen Fehlschlag.
     *
     * WARUM ES DAS GEBEN MUSS (Prüfrunde 8, Befund 2): [DimRule.shiftPattern] bindet über den
     * NAMEN der Definition, nicht über deren stabile `id` — [findRuleForShift] vergleicht gegen
     * `ShiftSpan.shiftName`, und der trägt ab dem nächsten Sync den NEUEN Namen. Der
     * Schichtname ist aber frei änderbar (`ShiftEditDialog` behält die `id`). Eine reine
     * Beschriftungsänderung ("AD1" → "Abrufdienst") legte damit die Dimm-Regel dieser Schicht
     * lautlos still: [findRuleForShift] findet nichts mehr und fällt auf UNIVERSAL bzw. `null`
     * zurück, während die Regelliste sie unverändert als aktiv anzeigt. Läuft „Nicht stören" im
     * Modus „folgt dem Dimmer", fällt dessen Nachtfenster gleich mit weg.
     *
     * SONDERMUSTER BLEIBEN UNBERÜHRT: [DimRule.SHIFT_UNIVERSAL] und [DimRule.SHIFT_FREE] sind
     * keine Schichtnamen, sondern Tages-Kategorien. Sie mitzuziehen würde aus einer Regel für
     * ALLE Tage eine Regel für genau eine Schicht machen — der Nutzer verlöre still das Dimmen
     * an allen übrigen Tagen. Deshalb der Ausschluss in [betrifftSchicht].
     *
     * `ignoreCase` genau wie beim Suchen: der Vergleich in [findRuleForShift] ist
     * groß-/kleinschreibungsblind, also muss es der Nachzug auch sein — sonst bliebe eine Regel
     * mit „ad1" liegen, obwohl sie bis eben getroffen hat.
     *
     * IDEMPOTENT: Ein zweiter Lauf findet nichts mehr vor und schreibt nicht.
     */
    suspend fun renameShiftPattern(oldName: String, newName: String): Result<Int> = runCatching {
        require(oldName.isNotBlank() && newName.isNotBlank()) {
            "Leerer Schichtname - Dimm-Regelmuster wird NICHT nachgezogen ('$oldName' -> '$newName')"
        }
        // Rein groß-/kleinschreibungsbedingte Änderungen brechen das Matching nicht (siehe oben) -
        // es gibt nichts zu retten, und ein Schreibvorgang wäre reines Risiko.
        if (oldName.equals(newName, ignoreCase = true)) return@runCatching 0

        // BEKANNTE GRENZE: `getRules()` degradiert einen Lesefehler bewusst auf die leere Liste
        // (fail-safe "diese Nacht kein Dimmen", siehe DimRuleRepository). Wir sehen dann "keine
        // betroffene Regel" und melden 0 - und liegen damit genau so richtig/falsch wie der
        // Dimmer selbst, der in diesem Zustand ohnehin keine Regel findet. Ein eigener,
        // nicht-degradierender Lesepfad waere eine zweite Wahrheit ueber demselben Store.
        val vorher = repository.getRules()
        val betroffen = vorher.filter { it.betrifftSchicht(oldName) }
        if (betroffen.isEmpty()) return@runCatching 0

        // Einzeln über upsert(), weil das die einzige öffentliche Schreibtür ist: sie liest und
        // schreibt INNERHALB einer DataStore-Transaktion. Ein "Ganzliste zurückschreiben" wäre
        // genau das Muster, das DimRuleRepository ausdrücklich verbietet (verlorene Änderung bei
        // Doppel-Tap, degradierte Leer-Liste löscht alles).
        betroffen.forEach { repository.upsert(it.copy(shiftPattern = newName)) }

        // NACHPRÜFEN, statt Erfolg anzunehmen: `upsert()` meldet einen abgebrochenen Edit
        // (defektes JSON) bewusst NICHT nach oben - es loggt und lässt den Bestand liegen. Ohne
        // diese Kontrolle würde die Umbenennung "erfolgreich" melden, während die Regeln
        // weiterhin auf den Altnamen zeigen; genau die stille Lüge, gegen die dieser Fix ist.
        val nachher = repository.getRules()
        val betroffeneIds = betroffen.map { it.id }.toSet()
        val nachgezogen = nachher.count { it.id in betroffeneIds && it.shiftPattern == newName }
        val nochAlt = nachher.count { it.betrifftSchicht(oldName) }
        check(nochAlt == 0 && nachgezogen == betroffen.size) {
            "Dimm-Regelmuster NICHT vollstaendig nachgezogen ('$oldName' -> '$newName'): " +
                "$nachgezogen von ${betroffen.size} umgeschrieben, $nochAlt tragen weiter den Altnamen"
        }

        // WARN, nicht DEBUG: `findRuleForShift` nimmt den ERSTEN Treffer - liegt jetzt eine
        // zweite aktive Regel auf demselben Muster (z. B. eine verwaiste aus einem Import), ist
        // sie tot, ohne dass die Liste das zeigt.
        val aufNeuemMuster = nachher.count { it.enabled && it.betrifftSchicht(newName) }
        if (aufNeuemMuster > 1) {
            Logger.w(
                LogTags.DIMMER,
                "⚠️ Nach dem Umbenennen liegen $aufNeuemMuster aktive Dimm-Regeln auf '$newName' - " +
                    "es greift nur die erste, die uebrigen sind wirkungslos"
            )
        }

        Logger.business(
            LogTags.DIMMER,
            "🔁 ${betroffen.size} Dimm-Regel(n) von '$oldName' auf '$newName' nachgezogen"
        )
        betroffen.size
    }.onFailure { error ->
        // WARN+ landet auch im Release-Log: eine nicht nachgezogene Regel ist ein Dimm-Fenster,
        // das ab jetzt fehlt - und beim DND-Modus "folgt dem Dimmer" ein Telefon, das nachts klingelt.
        Logger.e(
            LogTags.DIMMER,
            "❌ Dimm-Regelmuster konnte nicht von '$oldName' auf '$newName' nachgezogen werden",
            error
        )
    }

    /**
     * Trägt diese Regel den Schichtnamen [shiftName]? Sondermuster (FREI/UNIVERSAL) zählen
     * bewusst NIE mit - sie meinen keinen Namen (siehe [renameShiftPattern]).
     */
    private fun DimRule.betrifftSchicht(shiftName: String): Boolean =
        shiftPattern != DimRule.SHIFT_UNIVERSAL &&
            shiftPattern != DimRule.SHIFT_FREE &&
            shiftPattern.equals(shiftName, ignoreCase = true)
}
