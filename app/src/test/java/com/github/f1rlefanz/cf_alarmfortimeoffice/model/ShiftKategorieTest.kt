package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 1 (#166): Kategorie und „Wecker an/aus“ am Schichttyp.
 *
 * Kern ist die Festlegung „Flags und Kategorie bleiben deckungsgleich“: alter Code und alle
 * heutigen Leser (DND, Ruf-Abfrage, Erkennung, Editor) kennen nur `isSickDay`/`isOnCall`. Laeuft die
 * Kategorie neben ihnen auseinander, sortiert die neue Oberflaeche einen Typ anders ein, als der
 * Wecker ihn behandelt.
 *
 * Json-Flags wie die Produktion (`ShiftConfigRepository.json`).
 */
class ShiftKategorieTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val basis = ShiftDefinition(
        id = "t1",
        name = "Typ",
        keywords = listOf("T", "TX"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Ein Typ, wie ihn eine App vor dem Umbau gespeichert hat - ohne `kategorie`, ohne `weckerAn`. */
    private fun altbestand(isOnCall: Boolean, isSickDay: Boolean): ShiftDefinition = json.decodeFromString(
        ShiftDefinition.serializer(),
        """{"id":"a","name":"Alt","keywords":["A"],"alarmTime":"06:00","isEnabled":true,""" +
            """"isSilent":false,"isOnCall":$isOnCall,"isSickDay":$isSickDay}"""
    )

    @Test
    fun `Altbestand ohne Kategorie wird beim Lesen aus den Flags abgeleitet - beide true gilt Krank`() {
        assertEquals(ShiftKategorie.DIENST, altbestand(isOnCall = false, isSickDay = false).kategorie)
        assertEquals(ShiftKategorie.RUFBEREITSCHAFT, altbestand(isOnCall = true, isSickDay = false).kategorie)
        assertEquals(ShiftKategorie.KRANK, altbestand(isOnCall = false, isSickDay = true).kategorie)
        // Heute speicherbar; die Engine zweigt Krank vor jeder Schicht ab, also ist das schon das
        // wirksame Verhalten. Die Flags werden dabei NICHT umgeschrieben (Ableitung, kein Schreibschritt).
        val beide = altbestand(isOnCall = true, isSickDay = true)
        assertEquals(ShiftKategorie.KRANK, beide.kategorie)
        assertTrue("isOnCall des Altbestands bleibt unangetastet", beide.isOnCall)
    }

    @Test
    fun `mitKategorie setzt beide Flags deckungsgleich - fuer jede Kategorie und jeden Ausgangszustand`() {
        val ausgaenge = listOf(
            basis,
            basis.copy(isOnCall = true),
            basis.copy(isSickDay = true),
            basis.copy(isOnCall = true, isSickDay = true)
        )
        for (ausgang in ausgaenge) for (k in ShiftKategorie.entries) {
            val typ = ausgang.mitKategorie(k)
            val fall = "$k aus (onCall=${ausgang.isOnCall}, sick=${ausgang.isSickDay})"
            assertEquals(fall, k, typ.kategorie)
            assertEquals(fall, k == ShiftKategorie.KRANK, typ.isSickDay)
            assertEquals(fall, k == ShiftKategorie.RUFBEREITSCHAFT, typ.isOnCall)
            assertFalse("$fall: nie beide Flags", typ.isSickDay && typ.isOnCall)
        }
    }

    /**
     * Der heutige Editor kennt die Kategorie nicht und aendert nur die Flags per `copy()`. Danach
     * muss die Kategorie den Flags folgen, sonst haengt ein veralteter gespeicherter Wert am Typ.
     */
    @Test
    fun `copy der Flags zieht die Kategorie mit - nach jedem Kopieren und Aendern deckungsgleich`() {
        val frei = basis.mitKategorie(ShiftKategorie.FREI)
        assertEquals(ShiftKategorie.KRANK, frei.copy(isSickDay = true).kategorie)
        assertEquals(ShiftKategorie.RUFBEREITSCHAFT, frei.copy(isOnCall = true).kategorie)
        // Flag zurueckgenommen: die gespeicherte Kategorie gilt wieder.
        assertEquals(ShiftKategorie.FREI, frei.copy(isOnCall = true).copy(isOnCall = false).kategorie)

        // Krank/Ruf ohne ihr Flag: der Editor hat es zurueckgenommen, und das gilt.
        assertEquals(ShiftKategorie.DIENST, basis.mitKategorie(ShiftKategorie.KRANK).copy(isSickDay = false).kategorie)
        assertEquals(
            ShiftKategorie.DIENST,
            basis.mitKategorie(ShiftKategorie.RUFBEREITSCHAFT).copy(isOnCall = false).kategorie
        )

        // Volle Matrix: jede Kategorie x jede Flag-Kombination per copy().
        for (k in ShiftKategorie.entries) for (sick in listOf(false, true)) for (onCall in listOf(false, true)) {
            val typ = basis.mitKategorie(k).copy(isSickDay = sick, isOnCall = onCall)
            val fall = "$k + copy(sick=$sick, onCall=$onCall) -> ${typ.kategorie}"
            assertEquals(fall, typ.isSickDay, typ.kategorie == ShiftKategorie.KRANK)
            if (!(sick && onCall)) assertEquals(fall, typ.isOnCall, typ.kategorie == ShiftKategorie.RUFBEREITSCHAFT)
        }
    }

    @Test
    fun `Kategorie und Wecker aus ueberstehen Speichern und Lesen`() {
        for (k in ShiftKategorie.entries) {
            val typ = basis.mitKategorie(k).copy(weckerAn = false)
            val gelesen = json.decodeFromString(ShiftDefinition.serializer(), json.encodeToString(ShiftDefinition.serializer(), typ))
            assertEquals(typ, gelesen)
            assertEquals(k, gelesen.kategorie)
            assertFalse(gelesen.weckerAn)
        }
    }

    @Test
    fun `Kategoriewechsel behaelt alle Einstellungen des Typs`() {
        val typ = basis.copy(isSilent = true, isEnabled = false, weckerAn = false)
        val gewechselt = typ.mitKategorie(ShiftKategorie.URLAUB).mitKategorie(ShiftKategorie.FORTBILDUNG)
        assertEquals(typ.id, gewechselt.id)
        assertEquals(typ.name, gewechselt.name)
        assertEquals(typ.keywords, gewechselt.keywords)
        assertEquals(typ.alarmTime, gewechselt.alarmTime)
        assertEquals(typ.isSilent, gewechselt.isSilent)
        assertEquals(typ.isEnabled, gewechselt.isEnabled)
        assertEquals(typ.weckerAn, gewechselt.weckerAn)
    }

    /**
     * Eine spaetere Version mit einer achten Kategorie, danach zurueckgenommen: der unbekannte Wert
     * darf die Konfiguration nicht unlesbar machen (ein Enum-Serializer wuerfe hier fuer die GANZE
     * Liste), sondern faellt auf die Ableitung aus den Flags zurueck.
     */
    @Test
    fun `unbekannter gespeicherter Kategoriewert faellt auf die Flags zurueck statt zu werfen`() {
        fun lies(isOnCall: Boolean) = json.decodeFromString(
            ShiftDefinition.serializer(),
            """{"id":"x","name":"X","keywords":["X"],"alarmTime":"06:00","isOnCall":$isOnCall,"kategorie":"bereitschaft2"}"""
        )
        assertEquals(ShiftKategorie.DIENST, lies(isOnCall = false).kategorie)
        assertEquals(ShiftKategorie.RUFBEREITSCHAFT, lies(isOnCall = true).kategorie)
    }

    @Test
    fun `Wecker an ist Vorgabe und ein eigenes Feld - nicht isEnabled`() {
        assertTrue(basis.weckerAn)
        assertTrue(altbestand(isOnCall = false, isSickDay = false).weckerAn)
        val aus = basis.copy(weckerAn = false)
        assertTrue("Typ wird weiter erkannt", aus.isEnabled)
        val unerkannt = basis.copy(isEnabled = false)
        assertTrue("Wecker-Schalter bleibt", unerkannt.weckerAn)
    }

    @Test
    fun `Speicherschluessel sind eindeutig und lesen sich zurueck`() {
        assertEquals(ShiftKategorie.entries.size, ShiftKategorie.entries.map { it.schluessel }.toSet().size)
        for (k in ShiftKategorie.entries) assertEquals(k, ShiftKategorie.vonSchluessel(k.schluessel))
        assertEquals(null, ShiftKategorie.vonSchluessel(null))
    }
}
