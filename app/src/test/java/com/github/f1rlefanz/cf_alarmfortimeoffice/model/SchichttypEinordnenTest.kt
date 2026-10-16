package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Tests fuer [SchichttypEinordnen] - den Einordnen-Dialog (Umbau SPEC 3.2, E2, E4, #169).
 * „Gehoert zu einem bestehenden Typ“ ist [ShiftConfig.withCodeAssignedTo] und steht in
 * `ShiftCodeAssignmentTest`.
 */
class SchichttypEinordnenTest {

    private fun def(
        id: String,
        name: String,
        keywords: List<String>,
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        enabled: Boolean = true
    ) = ShiftDefinition(
        id = id,
        name = name,
        keywords = keywords,
        alarmTime = LocalTime.of(5, 30),
        isEnabled = enabled
    ).mitKategorie(kategorie)

    private fun config(vararg definitions: ShiftDefinition) =
        ShiftConfig(definitions = definitions.toList())

    /** Traegt irgendein Typ ausser [ausser] den Code noch als Muster (Gross/klein egal)? */
    private fun ShiftConfig.codeAnderswo(code: String, ausser: String): Boolean =
        definitions.any { d -> d.id != ausser && d.keywords.any { it.equals(code, ignoreCase = true) } }

    // ---- Voreinstellungen ----

    @Test
    fun `Wecker an nur bei Dienst und Fortbildung`() {
        val weckerAn = ShiftKategorie.entries.associateWith { SchichttypEinordnen.voreinstellung(it).weckerAn }

        assertEquals(
            mapOf(
                ShiftKategorie.DIENST to true,
                ShiftKategorie.RUFBEREITSCHAFT to false,
                ShiftKategorie.FREI to false,
                ShiftKategorie.URLAUB to false,
                ShiftKategorie.FORTBILDUNG to true,
                ShiftKategorie.KRANK to false,
                ShiftKategorie.IGNORIEREN to false
            ),
            weckerAn
        )
    }

    // ---- Neuer Typ aus einem Code ----

    @Test
    fun `neuer Typ aus IMCF traegt je Kategorie deren Voreinstellung und passende Flags`() {
        for (kategorie in ShiftKategorie.entries) {
            val typ = SchichttypEinordnen.neuerTypAusCode("IMCF", kategorie) { "neu-1" }!!

            assertEquals("neu-1", typ.id)
            assertEquals("IMCF", typ.name)
            assertEquals(listOf("IMCF"), typ.keywords)
            assertTrue(typ.isEnabled)
            assertEquals(kategorie, typ.kategorie)
            assertEquals(kategorie == ShiftKategorie.KRANK, typ.isSickDay)
            assertEquals(kategorie == ShiftKategorie.RUFBEREITSCHAFT, typ.isOnCall)
            val vorgabe = SchichttypEinordnen.voreinstellung(kategorie)
            assertEquals("$kategorie", vorgabe.weckerAn, typ.weckerAn)
            assertEquals("$kategorie", vorgabe.weckzeit, typ.alarmTime)
            assertTrue(typ.matchesKeywords("IMCF"))
        }
    }

    /** #150: ein Zeichen-Code ist ein gueltiges Muster und trifft nur den ganzen Titel. */
    @Test
    fun `neuer Typ aus Plus erkennt Plus und nur Plus`() {
        val typ = SchichttypEinordnen.neuerTypAusCode(" + ", ShiftKategorie.FREI)!!

        assertEquals("+", typ.name)
        assertEquals(listOf("+"), typ.keywords)
        assertEquals(ShiftKategorie.FREI, typ.kategorie)
        assertFalse(typ.weckerAn)
        assertTrue(typ.matchesKeywords("+"))
        assertFalse(typ.matchesKeywords("C++"))
    }

    @Test
    fun `jeder neue Typ bekommt eine eigene id`() {
        val a = SchichttypEinordnen.neuerTypAusCode("IMCF", ShiftKategorie.DIENST)!!
        val b = SchichttypEinordnen.neuerTypAusCode("IMCF", ShiftKategorie.DIENST)!!

        assertNotEquals(a.id, b.id)
        assertTrue(a.id.isNotBlank())
    }

    @Test
    fun `leerer Code ergibt keinen Typ`() {
        assertNull(SchichttypEinordnen.neuerTypAusCode("  ", ShiftKategorie.DIENST))
    }

    /** E2: die Kategorie setzt nur Voreinstellungen, ein spaeterer Wechsel behaelt sie. */
    @Test
    fun `spaeterer Kategoriewechsel aendert die Voreinstellung nicht`() {
        val dienst = SchichttypEinordnen.neuerTypAusCode("IMCF", ShiftKategorie.DIENST)!!
        val frei = SchichttypEinordnen.neuerTypAusCode("+", ShiftKategorie.FREI)!!

        val dienstAlsUrlaub = dienst.mitKategorie(ShiftKategorie.URLAUB)
        val freiAlsDienst = frei.mitKategorie(ShiftKategorie.DIENST)

        assertTrue(dienstAlsUrlaub.weckerAn)
        assertEquals(dienst.alarmTime, dienstAlsUrlaub.alarmTime)
        assertFalse(freiAlsDienst.weckerAn)
        assertEquals(frei.alarmTime, freiAlsDienst.alarmTime)
    }

    // ---- Code ignorieren ----

    @Test
    fun `ignorieren ohne Ignorieren-Typ legt einen mit Wecker aus an und nimmt den Code ueberall sonst weg`() {
        val vorher = config(
            def("f", "Frühdienst", listOf("F", "imcf")),
            def("s", "Spätdienst", listOf("S"))
        )

        val nachher = SchichttypEinordnen.ignorieren(vorher, "IMCF") { "ign-neu" }

        assertNotNull(nachher)
        val ign = nachher!!.definitions.single { it.id == "ign-neu" }
        assertEquals(ShiftKategorie.IGNORIEREN, ign.kategorie)
        assertEquals(SchichttypEinordnen.NAME_NEUER_IGNORIEREN_TYP, ign.name)
        assertEquals(listOf("IMCF"), ign.keywords)
        assertTrue(ign.isEnabled)
        assertFalse(ign.weckerAn)
        assertFalse(nachher.codeAnderswo("IMCF", ausser = "ign-neu"))
        assertEquals(listOf("F"), nachher.definitions.single { it.id == "f" }.keywords)
        assertEquals(3, nachher.definitions.size)
    }

    /**
     * Der Normalfall des Dialogs: ein frisch gefundener Code steht an KEINEM Typ. Haette der neue
     * Ignorieren-Typ den Code schon als Muster, saehe [ShiftConfig.withCodeAssignedTo] nichts zu
     * tun und lieferte `null` - der Klick verpuffte.
     */
    @Test
    fun `ignorieren eines Codes, den noch kein Typ traegt, legt den Ignorieren-Typ an`() {
        val vorher = config(def("f", "Frühdienst", listOf("F")))

        val nachher = SchichttypEinordnen.ignorieren(vorher, "FBE") { "ign-neu" }

        assertNotNull(nachher)
        assertEquals(listOf("FBE"), nachher!!.definitions.single { it.id == "ign-neu" }.keywords)
        assertEquals(vorher.definitions.single(), nachher.definitions.single { it.id == "f" })
    }

    @Test
    fun `ignorieren mit vorhandenem Ignorieren-Typ traegt den Code dort ein und legt keinen neuen an`() {
        val vorher = config(
            def("f", "Frühdienst", listOf("F", "imcf")),
            def("tb", "Teambesprechung", listOf("TB"), ShiftKategorie.IGNORIEREN)
        )

        val nachher = SchichttypEinordnen.ignorieren(vorher, "IMCF") { error("keine neue id erwartet") }

        assertNotNull(nachher)
        assertEquals(2, nachher!!.definitions.size)
        assertEquals(listOf("TB", "IMCF"), nachher.definitions.single { it.id == "tb" }.keywords)
        assertFalse(nachher.codeAnderswo("IMCF", ausser = "tb"))
    }

    /**
     * Ein AUSGESCHALTETER Ignorieren-Typ ist kein Ziel: [ShiftConfig.withCodeAssignedTo] schaltet
     * sein Ziel ein und haette damit still auch dessen alte Codes wieder ignoriert.
     */
    @Test
    fun `ausgeschalteter Ignorieren-Typ wird nicht wieder eingeschaltet`() {
        val vorher = config(
            def("f", "Frühdienst", listOf("F", "IMCF")),
            def("tb", "Teambesprechung", listOf("TB"), ShiftKategorie.IGNORIEREN, enabled = false)
        )

        val nachher = SchichttypEinordnen.ignorieren(vorher, "IMCF") { "ign-neu" }!!

        val alt = nachher.definitions.single { it.id == "tb" }
        assertFalse(alt.isEnabled)
        assertEquals(listOf("TB"), alt.keywords)
        assertEquals(listOf("IMCF"), nachher.definitions.single { it.id == "ign-neu" }.keywords)
        assertFalse(nachher.codeAnderswo("IMCF", ausser = "ign-neu"))
    }

    /** #150: auch ein Zeichen-Code laesst sich ignorieren. */
    @Test
    fun `Plus ignorieren nimmt es dem Frei-Typ weg`() {
        val vorher = config(def("p", "Frei", listOf("+"), ShiftKategorie.FREI))

        val nachher = SchichttypEinordnen.ignorieren(vorher, "+") { "ign-neu" }!!

        val ign = nachher.definitions.single { it.id == "ign-neu" }
        assertEquals(listOf("+"), ign.keywords)
        assertTrue(ign.matchesKeywords("+"))
        assertTrue(nachher.definitions.single { it.id == "p" }.keywords.isEmpty())
        assertFalse(nachher.codeAnderswo("+", ausser = "ign-neu"))
    }

    @Test
    fun `nichts zu tun ergibt null`() {
        val schonIgnoriert = config(def("tb", "Teambesprechung", listOf("TB"), ShiftKategorie.IGNORIEREN))

        assertNull(SchichttypEinordnen.ignorieren(schonIgnoriert, "tb"))
        assertNull(SchichttypEinordnen.ignorieren(config(), "  "))
    }
}
