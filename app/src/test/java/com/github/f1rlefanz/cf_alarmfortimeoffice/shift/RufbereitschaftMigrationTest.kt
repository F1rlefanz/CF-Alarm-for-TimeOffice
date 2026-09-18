package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Uebernahme der alten Rufbereitschaft-Namensliste (`dnd_oncall_shifts`) in
 * `ShiftDefinition.isOnCall`. Ohne sie verloere ein Bestandsnutzer nach dem Update lautlos den
 * DND-Cutoff - und die neue stuendliche Abfrage liefe an genau dem Tag nicht, fuer den sie
 * gebaut ist.
 */
class RufbereitschaftMigrationTest {

    private fun def(name: String, onCall: Boolean = false) = ShiftDefinition(
        id = name.lowercase(), name = name, keywords = listOf(name), alarmTime = LocalTime.of(6, 0), isOnCall = onCall
    )

    @Test
    fun `setzt das Flag fuer jeden Namen der Altliste`() {
        val neu = RufbereitschaftMigration.uebernehme(listOf(def("AD1"), def("Frueh")), setOf("AD1"))

        assertTrue(neu!!.first { it.name == "AD1" }.isOnCall)
        assertFalse(neu.first { it.name == "Frueh" }.isOnCall)
    }

    @Test
    fun `nichts zu setzen heisst nichts schreiben`() {
        assertNull(RufbereitschaftMigration.uebernehme(listOf(def("Frueh")), setOf("AD1")))
        assertNull(RufbereitschaftMigration.uebernehme(listOf(def("AD1", onCall = true)), setOf("AD1")))
        assertNull(RufbereitschaftMigration.uebernehme(emptyList(), setOf("AD1")))
    }

    @Test
    fun `vergleicht EXAKT - wie der Cutoff die Liste immer gelesen hat`() {
        // "ad1" hat im Cutoff nie getroffen; die Migration darf daraus keine scharfe
        // Rufbereitschaft machen, die der Nutzer nie eingerichtet hat.
        assertNull(RufbereitschaftMigration.uebernehme(listOf(def("AD1")), setOf("ad1")))
    }

    @Test
    fun `laesst bereits gesetzte Flags und alle uebrigen Felder in Ruhe`() {
        val vorher = listOf(def("AD1"), def("Nacht", onCall = true))

        val neu = RufbereitschaftMigration.uebernehme(vorher, setOf("AD1"))!!

        assertTrue(neu.first { it.name == "Nacht" }.isOnCall)
        assertEquals(vorher.map { it.id }, neu.map { it.id })
        assertEquals(vorher.map { it.keywords }, neu.map { it.keywords })
        assertEquals(vorher.map { it.alarmTime }, neu.map { it.alarmTime })
    }

    @Test
    fun `nach einem Import laeuft die Migration nur, wenn die Altliste in der Datei war`() {
        assertTrue(RufbereitschaftMigration.brauchtNachImportEineMigration(setOf("snooze_minutes", DndPrefs.LEGACY_ONCALL_KEY_NAME)))
        assertFalse(RufbereitschaftMigration.brauchtNachImportEineMigration(setOf("snooze_minutes")))
    }
}
