package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueTargetReconciler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedReason
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Bridge-Wechsel-Probe fuer SZENEN-Regeln, als Test.
 *
 * Am Geraet wurde dieser Ablauf am 14.08.2026 fuer Lampen- und Gruppenziele gefahren: exportieren,
 * die Datei ausserhalb der App zur "fremden Bridge" verbiegen, importieren - und pruefen, dass die
 * gueltigen Ziele sofort zurueckgeordnet werden und die ungueltigen NAMENTLICH im Fertig-Dialog
 * stehen. Fuer Szenen fehlte dieselbe Probe; hier steht sie auf der Ebene, auf der die Entscheidung
 * faellt.
 *
 * Zwei Dinge muessen zusammen stimmen, und beide waren ungetestet:
 *
 *  1. **Die Datei muss die Szenenfelder ueberhaupt durchlassen.** Der Import prueft
 *     `hue_schedule_rules` strukturell (`ConfigBackupUseCase.structuralRejection`) - und zwar
 *     BEWUSST streng, weil ein beschaedigter Wert sonst als "keine Regeln" durchginge und der
 *     Nutzer eine leere Regelliste saehe, ohne zu wissen warum. Waere diese Pruefung an den neuen
 *     Feldern haengengeblieben, haette ein Import saemtliche Szenenregeln verworfen.
 *  2. **Der Abgleich muss ueber den NAMEN laufen**, denn Szenen- und Gruppen-Id sind
 *     bridge-lokal und zeigen auf einer anderen Bridge ins Leere.
 */
class HueSzenenImportTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun szenenRegel(
        id: String,
        sceneId: String,
        sceneName: String,
        groupId: String,
        groupName: String
    ) = HueSchedule(
        id = id,
        name = "Regel $id",
        shiftPattern = "Frühdienst",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = groupId,
                        targetName = groupName,
                        actionType = ActionType.TURN_ON,
                        on = true,
                        duration = 30,
                        isGroup = true,
                        sceneId = sceneId,
                        sceneName = sceneName
                    )
                )
            )
        )
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

    // --- Teil 1: die Datei ---------------------------------------------------------------------

    @Test
    fun `eine exportierte Szenenregel kommt durch die Struktur-Pruefung des Imports`() {
        val datei = json.encodeToString(
            listOf(szenenRegel("r1", "wz-nacht", "Nachtlicht", "1", "Wohnzimmer"))
        )

        assertNull(
            "Szenenfelder duerfen den Import nicht zu Fall bringen",
            ConfigBackupUseCase.structuralRejection("hue_schedule_rules", datei)
        )
    }

    @Test
    fun `die Szenenfelder ueberleben den Weg durch die Datei`() {
        // Der eigentliche Anker: Ohne targetName UND sceneName in der Datei waere der Abgleich auf
        // einer anderen Bridge unmoeglich.
        val datei = json.encodeToString(
            listOf(szenenRegel("r1", "wz-nacht", "Nachtlicht", "1", "Wohnzimmer"))
        )

        assertTrue("Der Szenenname MUSS mitreisen", datei.contains("Nachtlicht"))
        assertTrue("Der Gruppenname MUSS mitreisen", datei.contains("Wohnzimmer"))

        val zurueck = json.decodeFromString<List<HueSchedule>>(datei).single()
        val aktion = zurueck.lightActions.single()
        assertEquals("wz-nacht", aktion.sceneId)
        assertEquals("Nachtlicht", aktion.sceneName)
        assertEquals("Wohnzimmer", aktion.targetName)
    }

    @Test
    fun `eine beschaedigte Regeldatei wird weiterhin abgelehnt, nicht stillschweigend geleert`() {
        // Die Gegenprobe zur Lockerung oben: Der Import darf einen kaputten Wert NICHT als
        // "keine Regeln" durchwinken - der Ort, an dem das noch sagbar ist, ist der Import.
        val kaputt = """[{"id":"r1","name":"Regel","shiftPattern":"Frühdienst","timeRanges":["""

        assertNotNull(
            ConfigBackupUseCase.structuralRejection("hue_schedule_rules", kaputt)
        )
    }

    // --- Teil 2: der Abgleich gegen die fremde Bridge -------------------------------------------

    @Test
    fun `Import auf fremder Bridge - gueltige Szene wird zurueckgeordnet, erfundene gemeldet`() {
        // Die Datei, wie sie von der ALTEN Bridge kommt: beide Ids gelten hier nicht mehr.
        val importiert = json.decodeFromString<List<HueSchedule>>(
            json.encodeToString(
                listOf(
                    szenenRegel("r1", "alt-nacht", "Nachtlicht", "77", "Wohnzimmer"),
                    szenenRegel("r2", "alt-erfunden", "Gibtsnicht", "78", "Wohnzimmer")
                )
            )
        )

        // Was DIESE Bridge wirklich hat.
        val dieseBridge = LightTargets(
            groups = listOf(gruppe("1", "Wohnzimmer")),
            scenes = listOf(
                HueScene(id = "neu-nacht", name = "Nachtlicht", group = "1")
            )
        )

        val ergebnis = HueTargetReconciler.reconcile(importiert, dieseBridge)

        // Die gueltige wird SOFORT zurueckgeordnet - ohne Zutun des Nutzers.
        val ersteAktion = ergebnis.rules[0].lightActions.single()
        assertEquals("neu-nacht", ersteAktion.sceneId)
        assertEquals("1", ersteAktion.targetId)
        assertEquals(1, ergebnis.remapped)

        // Die erfundene bleibt unangetastet und steht NAMENTLICH in der Rueckmeldung - genau das
        // zeigt der Fertig-Dialog des Imports.
        val gemeldet = ergebnis.unresolved.single()
        assertEquals(UnresolvedReason.NOT_FOUND, gemeldet.reason)
        assertEquals("Gibtsnicht", gemeldet.sceneName)
        assertTrue(
            "Die Beschriftung muss Szene UND Raum nennen, sonst sucht der Nutzer im Nichts",
            gemeldet.label.contains("Gibtsnicht") && gemeldet.label.contains("Wohnzimmer")
        )
        assertEquals(
            "Nichts wird geloescht - die Regel bleibt, sie zeigt nur ins Leere",
            "alt-erfunden",
            ergebnis.rules[1].lightActions.single().sceneId
        )
    }

    @Test
    fun `eine nicht erreichbare Bridge beim Import aendert und meldet NICHTS`() {
        // Der wichtigste Fall der ganzen Probe: Wer die Datei im falschen WLAN importiert, darf
        // nicht erleben, dass seine Regeln als kaputt markiert werden.
        val importiert = listOf(szenenRegel("r1", "alt-nacht", "Nachtlicht", "77", "Wohnzimmer"))

        val ergebnis = HueTargetReconciler.reconcile(
            importiert,
            LightTargets(groupsFailed = true, scenesFailed = true)
        )

        assertEquals(0, ergebnis.remapped)
        assertTrue(ergebnis.unresolved.isEmpty())
        assertEquals(importiert, ergebnis.rules)
    }
}
