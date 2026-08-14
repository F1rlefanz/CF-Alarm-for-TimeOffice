package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueTargetReconciler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt fest, wie Hue-Regel-Ziele nach einem Bridge-Wechsel behandelt werden.
 *
 * WARUM DAS FESTGENAGELT GEHOERT: `HueLightAction.targetId` ist eine BRIDGE-LOKALE Nummer und
 * reist im Konfigurations-Export (`hue_schedule_rules`) und im Android-Backup mit. Auf einer
 * anderen Bridge zeigt sie ins Leere - die Regel sieht vollstaendig aus und schaltet am Wecktag
 * nichts oder die FALSCHE Lampe. Beides merkt der Nutzer erst morgens.
 *
 * Die drei gefaehrlichen Rueckbauten, gegen die diese Tests stehen:
 *  - Bei mehreren Namenstreffern doch "den ersten" nehmen (dieselbe Fehlerfamilie wie
 *    `ShiftConfig.findDefinitionFor` mit einbuchstabigen Keywords).
 *  - Lampen- und Gruppen-Namensraum zusammenwerfen.
 *  - Eine gescheiterte Bridge-Abfrage als "die Bridge hat dieses Ziel nicht" deuten.
 */
class HueTargetReconcilerTest {

    private fun light(id: String, name: String) = HueLight(
        id = id,
        name = name,
        type = "Extended color light",
        modelid = null,
        manufacturername = null,
        productname = null,
        state = LightState(on = false),
        uniqueid = "uid-$id"
    )

    private fun group(id: String, name: String) = HueGroup(
        id = id,
        name = name,
        type = "Room",
        lights = emptyList(),
        state = GroupState(any_on = false, all_on = false),
        action = GroupAction(on = false)
    )

    private fun action(
        targetId: String,
        targetName: String?,
        isGroup: Boolean = false
    ) = HueLightAction(
        targetType = if (isGroup) TargetType.GROUP else TargetType.LIGHT,
        targetId = targetId,
        targetName = targetName,
        actionType = ActionType.TURN_ON,
        on = true,
        isGroup = isGroup
    )

    private fun rule(vararg actions: HueLightAction) = HueScheduleRule(
        id = "rule_1",
        name = "Fruehdienst-Licht",
        shiftPattern = "Fruehdienst",
        timeRanges = listOf(HueTimeRange(actions = actions.toList()))
    )

    private fun firstAction(outcome: HueTargetReconciler.Outcome) =
        outcome.rules.first().timeRanges.first().actions.first()

    @Test
    fun `eindeutiger Namenstreffer ordnet die neue Bridge-ID zu`() {
        val rules = listOf(rule(action(targetId = "3", targetName = "Schlafzimmer")))
        val targets = LightTargets(lights = listOf(light("7", "Schlafzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertEquals(1, outcome.remapped)
        assertTrue(outcome.unresolved.isEmpty())
        assertEquals("7", firstAction(outcome).targetId)
    }

    @Test
    fun `mehrere gleichnamige Ziele werden NICHT zugeordnet`() {
        // Zwei Lampen duerfen gleich heissen. Raten hiesse hier: die falsche Lampe am Wecktag.
        val rules = listOf(rule(action(targetId = "3", targetName = "Stehlampe")))
        val targets = LightTargets(lights = listOf(light("7", "Stehlampe"), light("9", "stehlampe")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertEquals(0, outcome.remapped)
        assertFalse(outcome.changed)
        assertEquals("3", firstAction(outcome).targetId)
        assertEquals(UnresolvedReason.AMBIGUOUS, outcome.unresolved.single().reason)
    }

    @Test
    fun `ein Name der jetzt einer Gruppe gehoert trifft eine Lampen-Aktion nicht`() {
        val rules = listOf(rule(action(targetId = "3", targetName = "Wohnzimmer", isGroup = false)))
        val targets = LightTargets(groups = listOf(group("2", "Wohnzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertEquals(0, outcome.remapped)
        assertEquals("3", firstAction(outcome).targetId)
        assertEquals(UnresolvedReason.NOT_FOUND, outcome.unresolved.single().reason)
    }

    @Test
    fun `ohne gespeicherten Namen gibt es keinen Anker und wird nichts geraten`() {
        val rules = listOf(rule(action(targetId = "3", targetName = null)))
        val targets = LightTargets(lights = listOf(light("7", "Schlafzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertFalse(outcome.changed)
        assertEquals(UnresolvedReason.NO_NAME, outcome.unresolved.single().reason)
    }

    @Test
    fun `eine erreichbare Bridge ohne jedes Ziel macht alles unbekannt aber loescht nichts`() {
        val rules = listOf(rule(action(targetId = "3", targetName = "Schlafzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, LightTargets())

        assertEquals(1, outcome.unresolved.size)
        assertFalse(outcome.changed)
        assertEquals("3", firstAction(outcome).targetId)
        assertEquals("Schlafzimmer", firstAction(outcome).targetName)
    }

    @Test
    fun `eine gescheiterte Teilabfrage laesst Ziele dieser Art unangetastet UND unbenannt`() {
        // DAS IST DER KERN: "Gruppen nicht abrufbar" ist etwas anderes als "Bridge hat keine
        // Gruppen". Wer das gleichsetzt, entwertet die Regeln des Nutzers wegen eines fremden
        // WLANs oder einer abgelehnten Teilabfrage.
        val rules = listOf(
            rule(
                action(targetId = "2", targetName = "Wohnzimmer", isGroup = true),
                action(targetId = "3", targetName = "Schlafzimmer", isGroup = false)
            )
        )
        val targets = LightTargets(
            lights = listOf(light("7", "Schlafzimmer")),
            groupsFailed = true
        )

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        // Die Lampe wird normal zugeordnet ...
        assertEquals(1, outcome.remapped)
        // ... die Gruppe bleibt unveraendert und wird NICHT als unbekannt gemeldet.
        val actions = outcome.rules.first().timeRanges.first().actions
        assertEquals("2", actions.first { it.isGroup }.targetId)
        assertTrue(outcome.unresolved.isEmpty())
    }

    @Test
    fun `eine gueltige ID bleibt und zieht den Bridge-Namen als Anker nach`() {
        // Bestandsregeln haben noch gar keinen targetName. Solange die eigene Bridge da ist,
        // entsteht der Anker hier - sonst waere die Regel beim naechsten Bridge-Wechsel verloren.
        val rules = listOf(rule(action(targetId = "7", targetName = null)))
        val targets = LightTargets(lights = listOf(light("7", "Schlafzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertEquals(0, outcome.remapped)
        assertEquals(1, outcome.namesRefreshed)
        assertEquals("7", firstAction(outcome).targetId)
        assertEquals("Schlafzimmer", firstAction(outcome).targetName)
    }

    @Test
    fun `ein zweiter Lauf aendert nichts mehr`() {
        val rules = listOf(rule(action(targetId = "3", targetName = "Schlafzimmer")))
        val targets = LightTargets(lights = listOf(light("7", "Schlafzimmer")))

        val first = HueTargetReconciler.reconcile(rules, targets)
        val second = HueTargetReconciler.reconcile(first.rules, targets)

        assertFalse(second.changed)
        assertEquals(first.rules, second.rules)
    }

    @Test
    fun `Namensvergleich ignoriert Gross-Kleinschreibung und Randleerzeichen`() {
        val rules = listOf(rule(action(targetId = "3", targetName = "  schlafZIMMER ")))
        val targets = LightTargets(lights = listOf(light("7", "Schlafzimmer")))

        val outcome = HueTargetReconciler.reconcile(rules, targets)

        assertEquals(1, outcome.remapped)
        // Der Bridge-Name gewinnt als gespeicherte Schreibweise.
        assertEquals("Schlafzimmer", firstAction(outcome).targetName)
    }
}
