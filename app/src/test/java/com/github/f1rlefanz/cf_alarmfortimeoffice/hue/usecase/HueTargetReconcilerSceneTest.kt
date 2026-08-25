package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Ziel-Abgleich fuer SZENEN-Ziele.
 *
 * Warum das ein eigener Namensraum sein muss, ist an der Bridge des Nutzers gemessen
 * (BSB002, 25.08.2026): „Nachtlicht" existiert dort NEUN Mal, „Energie tanken" ZEHN Mal - je
 * einmal pro Raum. Ein Anker allein ueber den Szenennamen waere in der Praxis immer mehrdeutig.
 * INNERHALB einer Gruppe kollidierte dagegen kein einziger Name. Deshalb ist der Anker das Paar
 * (Szenenname, Gruppenname), und deshalb wird die Gruppe ZUERST aufgeloest.
 */
class HueTargetReconcilerSceneTest {

    // --- Bridge-Bestand: zwei Raeume mit gleichnamigen Szenen -------------------------------

    private val gruppen = listOf(
        gruppe("1", "Wohnzimmer"),
        gruppe("82", "Schlafzimmer")
    )

    private val szenen = listOf(
        HueScene(id = "wz-nacht", name = "Nachtlicht", type = "GroupScene", group = "1"),
        HueScene(id = "sz-nacht", name = "Nachtlicht", type = "GroupScene", group = "82"),
        HueScene(id = "sz-lesen", name = "Lesen", type = "GroupScene", group = "82")
    )

    private val bestand = LightTargets(groups = gruppen, scenes = szenen)

    @Test
    fun `gleichnamige Szenen in zwei Raeumen - jede Regel bleibt in ihrem Raum`() {
        // Genau der reale Fall: beide Regeln heissen "Nachtlicht", beide Szenen-Ids sind
        // veraltet. Ohne den Gruppen-Anker waere hier nichts zu entscheiden.
        val wohnzimmer = regel("r1", szenenAktion("veraltet-a", "Nachtlicht", "999", "Wohnzimmer"))
        val schlafzimmer = regel("r2", szenenAktion("veraltet-b", "Nachtlicht", "998", "Schlafzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(listOf(wohnzimmer, schlafzimmer), bestand)

        assertEquals(2, ergebnis.remapped)
        assertTrue(ergebnis.unresolved.isEmpty())
        assertEquals("wz-nacht", ergebnis.rules[0].lightActions[0].sceneId)
        assertEquals("1", ergebnis.rules[0].lightActions[0].targetId)
        assertEquals("sz-nacht", ergebnis.rules[1].lightActions[0].sceneId)
        assertEquals("82", ergebnis.rules[1].lightActions[0].targetId)
    }

    @Test
    fun `Gruppe mehrdeutig - die Szenen-Id bleibt unangetastet`() {
        // Zwei Gruppen desselben Namens: der Raum ist nicht zu entscheiden. Dann darf auch die
        // Szene nicht zugeordnet werden - eine Szene in den falschen Raum zu schieben ist
        // schlimmer als gar nichts zu tun.
        val zweideutig = LightTargets(
            groups = listOf(gruppe("1", "Wohnzimmer"), gruppe("7", "Wohnzimmer")),
            scenes = szenen
        )
        val regel = regel("r1", szenenAktion("veraltet", "Nachtlicht", "999", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(listOf(regel), zweideutig)

        assertEquals(0, ergebnis.remapped)
        assertEquals("veraltet", ergebnis.rules[0].lightActions[0].sceneId)
        assertEquals(UnresolvedReason.AMBIGUOUS, ergebnis.unresolved.single().reason)
        assertEquals("Nachtlicht", ergebnis.unresolved.single().sceneName)
    }

    @Test
    fun `Szene in einen anderen Raum gewandert - gemeldet, nicht still angewendet`() {
        // Die gespeicherte Szenen-Id gibt es auf dieser Bridge noch, sie liegt aber inzwischen
        // in einem ANDEREN Raum. Der Kurzschluss ueber die bekannte Id darf hier nicht greifen.
        val regel = regel("r1", szenenAktion("sz-lesen", "Lesen", "1", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(listOf(regel), bestand)

        assertEquals(0, ergebnis.remapped)
        assertEquals("sz-lesen", ergebnis.rules[0].lightActions[0].sceneId)
        assertEquals(UnresolvedReason.NOT_FOUND, ergebnis.unresolved.single().reason)
    }

    @Test
    fun `bekannte Szene im richtigen Raum - nur die Namen werden aufgefrischt`() {
        val regel = regel("r1", szenenAktion("wz-nacht", "alter name", "1", "alter raum"))

        val ergebnis = HueTargetReconciler.reconcile(listOf(regel), bestand)

        assertEquals(0, ergebnis.remapped)
        assertEquals(1, ergebnis.namesRefreshed)
        val aktion = ergebnis.rules[0].lightActions[0]
        assertEquals("wz-nacht", aktion.sceneId)
        assertEquals("Nachtlicht", aktion.sceneName)
        assertEquals("Wohnzimmer", aktion.targetName)
    }

    @Test
    fun `alles stimmt schon - der Abgleich ist idempotent`() {
        val regel = regel("r1", szenenAktion("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(listOf(regel), bestand)

        assertEquals(0, ergebnis.remapped)
        assertEquals(0, ergebnis.namesRefreshed)
        assertTrue(ergebnis.unresolved.isEmpty())
        assertEquals(listOf(regel), ergebnis.rules)
    }

    @Test
    fun `gescheiterte Szenen-Abfrage - nichts geaendert, nichts gemeldet`() {
        // Dieselbe Haltung wie bei lightsFailed/groupsFailed: eine nicht erreichbare Bridge darf
        // die Regeln des Nutzers weder umschreiben noch als kaputt markieren.
        val regel = regel("r1", szenenAktion("veraltet", "Nachtlicht", "999", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(
            listOf(regel),
            bestand.copy(scenes = emptyList(), scenesFailed = true)
        )

        assertEquals(0, ergebnis.remapped)
        assertTrue(ergebnis.unresolved.isEmpty())
        assertEquals(listOf(regel), ergebnis.rules)
    }

    @Test
    fun `gescheiterte Gruppen-Abfrage - Szenen-Ziele bleiben ebenfalls unberuehrt`() {
        // Eine Szenen-Aktion haengt an ZWEI Abfragen. Faellt die Gruppen-Abfrage aus, fehlt der
        // erste Anker - dann ist auch der zweite wertlos.
        val regel = regel("r1", szenenAktion("veraltet", "Nachtlicht", "999", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(
            listOf(regel),
            bestand.copy(groups = emptyList(), groupsFailed = true)
        )

        assertEquals(0, ergebnis.remapped)
        assertTrue(ergebnis.unresolved.isEmpty())
        assertEquals(listOf(regel), ergebnis.rules)
    }

    @Test
    fun `Lampen-Ziele werden nicht gegen Szenennamen gematcht`() {
        // Der Namensraum-Schluessel muss dreiwertig sein: eine gleichnamige Lampe darf ein
        // Szenen-Ziel nicht anziehen, und umgekehrt.
        val lampenZiel = HueLightAction(
            targetType = TargetType.LIGHT,
            targetId = "42",
            targetName = "Nachtlicht",
            actionType = ActionType.TURN_ON,
            on = true,
            isGroup = false
        )
        val regel = regel("r1", lampenZiel)

        val ergebnis = HueTargetReconciler.reconcile(listOf(regel), bestand)

        assertEquals(0, ergebnis.remapped)
        assertNull(ergebnis.rules[0].lightActions[0].sceneId)
        assertEquals(UnresolvedReason.NOT_FOUND, ergebnis.unresolved.single().reason)
        assertNull("Ein Lampen-Ziel meldet keinen Szenennamen", ergebnis.unresolved.single().sceneName)
    }

    // --- Helfer ------------------------------------------------------------------------------

    private fun szenenAktion(
        sceneId: String,
        sceneName: String,
        groupId: String,
        groupName: String
    ) = HueLightAction(
        targetType = TargetType.GROUP,
        targetId = groupId,
        targetName = groupName,
        actionType = ActionType.TURN_ON,
        on = true,
        isGroup = true,
        sceneId = sceneId,
        sceneName = sceneName
    )

    private fun regel(id: String, vararg aktionen: HueLightAction) = HueSchedule(
        id = id,
        name = "Regel $id",
        shiftPattern = "Frühdienst",
        timeRanges = listOf(HueTimeRange(actions = aktionen.toList()))
    )

    private fun gruppe(id: String, name: String) = HueGroup(
        id = id,
        name = name,
        type = "Room",
        lights = emptyList(),
        sensors = null,
        state = GroupState(all_on = false, any_on = false),
        action = GroupAction(on = false)
    )
}
