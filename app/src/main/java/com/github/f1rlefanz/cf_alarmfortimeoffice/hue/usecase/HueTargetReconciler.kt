package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedReason
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget

/**
 * Ordnet die Ziele gespeicherter Hue-Regeln den Lampen/Gruppen der HEUTE verbundenen Bridge zu.
 *
 * WARUM ES DAS BRAUCHT: `HueLightAction.targetId` ist eine BRIDGE-LOKALE Nummer. Sie steht so in
 * der Exportdatei (`hue_schedule_rules`) und im Android-Backup. Auf einem Geraet mit einer
 * ANDEREN Bridge zeigt sie ins Leere - die Regel sieht vollstaendig aus, schaltet am Wecktag aber
 * nichts oder die falsche Lampe. Genau die Sorte stiller Erfolg, gegen die der Import sonst
 * ueberall verteidigt (`ConfigBackupUseCase.structuralRejection`): dort wird ein unlesbares
 * Regelwerk BENANNT abgelehnt, hier ginge es als "Regeln vorhanden" durch.
 *
 * DER ANKER IST DER NAME. [HueLightAction.targetName] wird beim Speichern einer Regel mitgefuehrt
 * (siehe `HueRuleConfigScreen.buildActions`) und hier bei jedem erfolgreichen Abgleich auf den
 * aktuellen Bridge-Namen nachgezogen - damit ist er auch fuer Bestandsregeln vorhanden, sobald die
 * App einmal mit der Bridge gesprochen hat, auf der die IDs noch stimmen. Ohne Namen gibt es
 * nichts wiederzufinden; das wird gemeldet, nicht geraten.
 *
 * DREI HALTUNGEN, DIE NICHT VERHANDELBAR SIND:
 *  1. **Bei Mehrdeutigkeit lieber NICHT zuordnen als falsch.** Zwei Lampen duerfen gleich heissen.
 *     Dieselbe Haltung wie bei den Kuerzel-Vorschlaegen (`ShiftCodeSuggester`): die App ordnet
 *     nichts still zu, sie benennt, was sie nicht kann.
 *  2. **Lampe und Gruppe sind getrennte Namensraeume.** Gesucht wird ausschliesslich unter Zielen
 *     derselben Art - massgeblich ist [HueLightAction.isGroup], denn genau das entscheidet in
 *     `HueRuleUseCase.convertRuleToLightActions`, ob die Bridge unter /lights/ oder /groups/
 *     angesprochen wird. Wandert ein Name von einer Lampe zu einer Gruppe, gilt er als NICHT
 *     zuordenbar.
 *  3. **Eine gescheiterte Abfrage aendert und meldet NICHTS.** Der Fehlschlag der Gesamtabfrage
 *     erreicht diese Funktion gar nicht erst (der Aufrufer ruft sie dann nicht). Der TEILausfall
 *     steht in [LightTargets.lightsFailed]/[LightTargets.groupsFailed] und laesst die Ziele
 *     dieser Art unangetastet - sonst wuerde ein fremdes WLAN oder eine abgelehnte Teilabfrage
 *     die Regeln des Nutzers entwerten.
 *
 * Rein und ohne Android - Vorbild `ShiftCodeSuggester`, `DimWindowResolver`.
 */
internal object HueTargetReconciler {

    /** Ein Ziel der Bridge, auf die Angaben reduziert, die der Abgleich braucht. */
    private data class Candidate(val id: String, val name: String)

    data class Outcome(
        /** Die (ggf. angepassten) Regeln - bei `remapped == 0 && !namesRefreshed` identisch zur Eingabe. */
        val rules: List<HueSchedule>,
        /** Wie viele Aktionen eine NEUE targetId bekommen haben. */
        val remapped: Int,
        /** Wie viele Aktionen (nur) ihren gespeicherten Namen aktualisiert bekommen haben. */
        val namesRefreshed: Int,
        /** Ziele, die auf dieser Bridge nicht zuzuordnen sind - sichtbar zu machen, nicht zu loeschen. */
        val unresolved: List<UnresolvedRuleTarget>
    ) {
        val changed: Boolean get() = remapped > 0 || namesRefreshed > 0
    }

    fun reconcile(rules: List<HueSchedule>, targets: LightTargets): Outcome {
        val lights = targets.lights.map { Candidate(it.id, it.name) }
        val groups = targets.groups.map { Candidate(it.id, it.name) }

        val byId = mapOf(false to lights.associateBy { it.id }, true to groups.associateBy { it.id })
        val byName = mapOf(
            false to lights.groupBy { it.name.normalized() },
            true to groups.groupBy { it.name.normalized() }
        )

        var remapped = 0
        var namesRefreshed = 0
        val unresolved = mutableListOf<UnresolvedRuleTarget>()

        val newRules = rules.map { rule ->
            var ruleChanged = false
            val newTimeRanges = rule.timeRanges.map { range ->
                val newActions = range.actions.map { action ->
                    // Ziele einer Art, deren Abfrage gescheitert ist: nicht anfassen, nicht melden.
                    val queryFailed = if (action.isGroup) targets.groupsFailed else targets.lightsFailed
                    if (queryFailed) return@map action

                    val known = byId.getValue(action.isGroup)[action.targetId]
                    if (known != null) {
                        // ID stimmt auf dieser Bridge. Der Bridge-Name ist die Wahrheit - so
                        // wandert er auch in Bestandsregeln, die noch gar keinen Anker haben.
                        if (action.targetName == known.name) return@map action
                        namesRefreshed++
                        ruleChanged = true
                        return@map action.copy(targetName = known.name)
                    }

                    val anchor = action.targetName?.trim()
                    if (anchor.isNullOrEmpty()) {
                        unresolved += action.unresolved(rule, UnresolvedReason.NO_NAME)
                        return@map action
                    }

                    val candidates = byName.getValue(action.isGroup)[anchor.normalized()].orEmpty()
                    when (candidates.size) {
                        1 -> {
                            remapped++
                            ruleChanged = true
                            action.copy(targetId = candidates[0].id, targetName = candidates[0].name)
                        }
                        0 -> {
                            unresolved += action.unresolved(rule, UnresolvedReason.NOT_FOUND)
                            action
                        }
                        else -> {
                            unresolved += action.unresolved(rule, UnresolvedReason.AMBIGUOUS)
                            action
                        }
                    }
                }
                if (newActions == range.actions) range else range.copy(actions = newActions)
            }
            if (ruleChanged) rule.copy(timeRanges = newTimeRanges) else rule
        }

        return Outcome(
            rules = newRules,
            remapped = remapped,
            namesRefreshed = namesRefreshed,
            unresolved = unresolved
        )
    }

    private fun HueLightAction.unresolved(rule: HueSchedule, reason: UnresolvedReason) =
        UnresolvedRuleTarget(
            ruleId = rule.id,
            ruleName = rule.name,
            targetId = targetId,
            targetName = targetName,
            isGroup = isGroup,
            reason = reason
        )

    /**
     * Vergleichsform fuer Bridge-Namen: getrimmt und ohne Gross-/Kleinschreibung. Hue-Namen tippt
     * der Nutzer selbst; ein fuehrendes Leerzeichen oder ein grosses Anfangs-W darf nicht daran
     * hindern, dieselbe Lampe wiederzufinden. Bewusst KEINE weitergehende Normalisierung (keine
     * Umlaut-Faltung, keine Teiltreffer) - das waere Raten, und Raten ist hier verboten.
     */
    private fun String.normalized(): String = trim().lowercase()
}
