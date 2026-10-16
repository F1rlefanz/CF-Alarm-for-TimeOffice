package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 1 (#166), SPEC Abschnitt 4 „Kopieren, nicht verschieben“: Wird ein Umbau-Stand per
 * Revert zurueckgenommen, liest die alte App die Konfiguration, die die neue geschrieben hat. Sie
 * muss dabei dieselben Typen, Muster, Zeiten und Flags sehen - und die feste Zeile „Ohne
 * Kalendereintrag“ darf bei ihr NICHT als zusaetzliche Schicht ankommen (sie haette dann Muster und
 * einen Wecker).
 *
 * [V1_56_2] ist eine testlokale Kopie der Datenklassen von v1.56.2 (nur die Felder; die Methoden
 * spielen fuer das Lesen keine Rolle). [LocalTimeSerializer] ist seither unveraendert und wird
 * deshalb mitbenutzt. Json-Flags wie `ShiftConfigRepository.json`.
 */
class AltDecoderKompatibilitaetTest {

    @Suppress("ClassName")
    private object V1_56_2 {
        @Serializable
        data class ShiftDefinition(
            val id: String,
            val name: String,
            val keywords: List<String>,
            @Serializable(with = LocalTimeSerializer::class)
            val alarmTime: LocalTime,
            val isEnabled: Boolean = true,
            val isSilent: Boolean = false,
            val isOnCall: Boolean = false,
            val isSickDay: Boolean = false
        )

        @Serializable
        data class ShiftConfig(
            val autoAlarmEnabled: Boolean = true,
            val definitions: List<ShiftDefinition> = emptyList()
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun typ(id: String, muster: List<String>, zeit: LocalTime, k: ShiftKategorie) =
        ShiftDefinition(id = id, name = "Name $id", keywords = muster, alarmTime = zeit).mitKategorie(k)

    /** Jede Kategorie einmal, dazu Wecker aus, still, deaktiviert und der Ohne-Eintrag-Verweis. */
    private val neueKonfiguration = ShiftConfig(
        autoAlarmEnabled = true,
        definitions = listOf(
            typ("d", listOf("D", "DX"), LocalTime.of(5, 30), ShiftKategorie.DIENST).copy(weckerAn = false),
            typ("r", listOf("R"), LocalTime.of(5, 0), ShiftKategorie.RUFBEREITSCHAFT).copy(isSilent = true),
            typ("f", listOf("+"), LocalTime.of(9, 0), ShiftKategorie.FREI),
            typ("u", listOf("U", "UL"), LocalTime.of(9, 0), ShiftKategorie.URLAUB),
            typ("fb", listOf("FBE", "FBI"), LocalTime.of(7, 0), ShiftKategorie.FORTBILDUNG),
            typ("k", listOf("K"), LocalTime.of(0, 0), ShiftKategorie.KRANK),
            typ("i", listOf("X"), LocalTime.of(8, 0), ShiftKategorie.IGNORIEREN).copy(isEnabled = false)
        ),
        ohneKalendereintragWie = "f"
    )

    @Test
    fun `alter Decoder liest eine neu geschriebene Konfiguration mit denselben Typen und ohne zusaetzliche Schicht`() {
        val geschrieben = json.encodeToString(ShiftConfig.serializer(), neueKonfiguration)

        val alt = json.decodeFromString(V1_56_2.ShiftConfig.serializer(), geschrieben)

        assertEquals("keine zusaetzliche Schicht", neueKonfiguration.definitions.size, alt.definitions.size)
        assertEquals(neueKonfiguration.autoAlarmEnabled, alt.autoAlarmEnabled)
        neueKonfiguration.definitions.zip(alt.definitions).forEach { (neu, gelesen) ->
            val erwartet = V1_56_2.ShiftDefinition(
                id = neu.id,
                name = neu.name,
                keywords = neu.keywords,
                alarmTime = neu.alarmTime,
                isEnabled = neu.isEnabled,
                isSilent = neu.isSilent,
                isOnCall = neu.isOnCall,
                isSickDay = neu.isSickDay
            )
            assertEquals("Typ ${neu.id} (${neu.kategorie})", erwartet, gelesen)
        }
        // Die Flags tragen fuer die alte App weiter Krank und Rufbereitschaft.
        assertEquals(listOf("k"), alt.definitions.filter { it.isSickDay }.map { it.id })
        assertEquals(listOf("r"), alt.definitions.filter { it.isOnCall }.map { it.id })
    }

    /**
     * Die Gegenrichtung des Revert: speichert die alte App, fallen die unbekannten Felder weg. Die
     * neue App liest danach die Kategorie wieder aus den Flags - Krank und Rufbereitschaft
     * ueberleben, die uebrigen werden zu Dienst, „Wecker an“ zur Vorgabe, der Ohne-Eintrag-Verweis
     * zu „eigene Einstellungen“. Bewusst so: nichts wird unlesbar, und der Wecker klingt im Zweifel.
     */
    @Test
    fun `von der alten App zurueckgeschrieben liest die neue die Kategorie aus den Flags`() {
        val alt = json.decodeFromString(
            V1_56_2.ShiftConfig.serializer(),
            json.encodeToString(ShiftConfig.serializer(), neueKonfiguration)
        )

        val wieder = json.decodeFromString(ShiftConfig.serializer(), json.encodeToString(V1_56_2.ShiftConfig.serializer(), alt))

        assertEquals(
            listOf(
                ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.DIENST, ShiftKategorie.DIENST,
                ShiftKategorie.DIENST, ShiftKategorie.KRANK, ShiftKategorie.DIENST
            ),
            wieder.definitions.map { it.kategorie }
        )
        assertEquals(true, wieder.definitions.all { it.weckerAn })
        assertEquals(null, wieder.ohneKalendereintragWie)
        assertEquals(neueKonfiguration.definitions.map { it.keywords }, wieder.definitions.map { it.keywords })
    }
}
