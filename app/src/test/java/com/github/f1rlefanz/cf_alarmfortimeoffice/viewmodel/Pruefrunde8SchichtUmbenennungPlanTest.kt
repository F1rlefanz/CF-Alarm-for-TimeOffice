package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Prüfrunde 8, Befund 2 - Teil 1: Wann ist eine Namensänderung eine Umbenennung, die in
 * Dimmer-/Hue-Regelmustern nachgezogen werden MUSS, und wann darf sie es ausdrücklich NICHT sein?
 *
 * DER FEHLER: `DimRule.shiftPattern` und `HueSchedule.shiftPattern` binden über den NAMEN der
 * Schichtdefinition, während der Editor den Namen bei gleichbleibender `id` frei ändern lässt.
 * Eine reine Beschriftungsänderung ("AD1" → "Abrufdienst") legte damit beide Regeln lautlos still,
 * und die Regellisten zeigten sie weiter als aktiv.
 *
 * Das Nachziehen ist aber kein „ersetze überall den String": Falsch zugeordnet schaltet eine Regel
 * Licht und Verdunkelung zur falschen Zeit - schlimmer als gar nicht. Die Blockaden unten sind
 * deshalb genauso Teil des Fixes wie der Nachzug selbst.
 */
class Pruefrunde8SchichtUmbenennungPlanTest {

    private fun def(id: String, name: String) = ShiftDefinition(
        id = id,
        name = name,
        keywords = listOf(name),
        alarmTime = LocalTime.of(5, 30)
    )

    private fun config(vararg defs: ShiftDefinition) = ShiftConfig(definitions = defs.toList())

    @Test
    fun `gleiche id mit neuem Namen ist eine Umbenennung`() {
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1"), def("2", "Frueh")),
            nachher = config(def("1", "Abrufdienst"), def("2", "Frueh"))
        )

        assertEquals(listOf(SchichtUmbenennung("AD1", "Abrufdienst")), plan.umbenennungen)
        assertTrue(plan.blockiert.isEmpty())
    }

    @Test
    fun `reine Schreibweisenaenderung ist KEINE Umbenennung - das Matching ist ignoreCase`() {
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "ad1")),
            nachher = config(def("1", "AD1"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertTrue(plan.blockiert.isEmpty())
    }

    @Test
    fun `geloescht und neu angelegt ist KEINE Umbenennung - die id entscheidet`() {
        // Gleicher Effekt auf dem Bildschirm, voellig andere Absicht: die Regeln der alten Schicht
        // duerfen NICHT auf die neue wandern.
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1")),
            nachher = config(def("99", "Abrufdienst"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertTrue(plan.blockiert.isEmpty())
    }

    @Test
    fun `ohne vorherige Konfiguration wird nichts nachgezogen`() {
        val plan = planeSchichtUmbenennungen(vorher = null, nachher = config(def("1", "AD1")))

        assertTrue(plan.umbenennungen.isEmpty())
        assertTrue(plan.blockiert.isEmpty())
    }

    @Test
    fun `Zielname ist das Hue-Universalmuster - blockiert`() {
        // "ALL" heisst in Hue-Regeln "alle Schichten". Zoege man mit, wuerde aus einer Regel fuer
        // JEDE Schicht eine fuer genau diese eine.
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1")),
            nachher = config(def("1", "ALL"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertEquals(1, plan.blockiert.size)
        assertEquals(SchichtUmbenennung("AD1", "ALL"), plan.blockiert.single().umbenennung)
    }

    @Test
    fun `Zielname ist ein Dimmer-Sondermuster - blockiert`() {
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1")),
            nachher = config(def("1", DimRule.SHIFT_UNIVERSAL))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertEquals(1, plan.blockiert.size)
    }

    @Test
    fun `zwei Schichten heissen jetzt gleich - blockiert statt mehrdeutig zuzuordnen`() {
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1"), def("2", "Abrufdienst")),
            nachher = config(def("1", "Abrufdienst"), def("2", "Abrufdienst"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertEquals(1, plan.blockiert.size)
    }

    @Test
    fun `Namenstausch zwischen zwei Schichten - beide blockiert`() {
        // Ohne diese Blockade wuerden die Regeln beider Schichten aufeinander geworfen: der
        // Fruehdienst dimmte nach dem Nachtdienst-Plan.
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "Frueh"), def("2", "Nacht")),
            nachher = config(def("1", "Nacht"), def("2", "Frueh"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertEquals(2, plan.blockiert.size)
    }

    @Test
    fun `der neue Name gehoerte bisher einer anderen Schicht - blockiert`() {
        // "Abrufdienst" war der Name von Definition 2; deren Regeln tragen dieses Muster bereits.
        // Ein Zusammenlegen waere nicht rueckgaengig zu machen - und findRuleForShift nimmt
        // ohnehin nur den ersten Treffer.
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1"), def("2", "Abrufdienst")),
            nachher = config(def("1", "Abrufdienst"))
        )

        assertTrue(plan.umbenennungen.isEmpty())
        assertEquals(1, plan.blockiert.size)
    }

    @Test
    fun `mehrere unabhaengige Umbenennungen werden alle erfasst`() {
        val plan = planeSchichtUmbenennungen(
            vorher = config(def("1", "AD1"), def("2", "F"), def("3", "Nacht")),
            nachher = config(def("1", "Abrufdienst"), def("2", "Fruehdienst"), def("3", "Nacht"))
        )

        assertEquals(
            listOf(
                SchichtUmbenennung("AD1", "Abrufdienst"),
                SchichtUmbenennung("F", "Fruehdienst")
            ),
            plan.umbenennungen
        )
    }
}
