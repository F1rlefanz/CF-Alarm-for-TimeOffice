package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 *
 * [V1_59_0] ist dieselbe Art Kopie vom Stand VOR #246 (Umbau Etappe 4): das Modell nach Etappe 3
 * (#213) mit Dimmen, „Nicht stoeren“, fester Zeile und [ShiftConfig.modellStufe]. Es geht als 1.59.0
 * an die Tester (Etappen-Abschluss), auf diesem Branch heisst die App noch 1.58.0. Die Dimm-Fenster
 * liegen dort roh, deshalb hier [JsonElement]; die Feldnamen sind die gespeicherten (`@SerialName`).
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

    @Suppress("ClassName")
    private object V1_59_0 {
        @Serializable
        data class TypDimmen(
            val an: Boolean = true,
            val fenster: List<JsonElement> = emptyList(),
            val farbwaerme: Int? = null
        )

        @Serializable
        data class TypNichtStoeren(
            val solangeGedimmt: Boolean = false,
            val waehrendEintrag: Boolean = false,
            val endetSpaetestensMinuten: Int = 5 * 60
        )

        @Serializable
        data class OhneEintragEinstellungen(
            val dimmen: TypDimmen = TypDimmen(),
            val nichtStoerenSolangeGedimmt: Boolean = false
        )

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
            val isSickDay: Boolean = false,
            @SerialName("kategorie")
            val kategorie: String? = null,
            val weckerAn: Boolean = true,
            val dimmen: TypDimmen = TypDimmen(),
            val nichtStoeren: TypNichtStoeren = TypNichtStoeren()
        )

        @Serializable
        data class ShiftConfig(
            val autoAlarmEnabled: Boolean = true,
            val definitions: List<ShiftDefinition> = emptyList(),
            val ohneKalendereintragWie: String? = null,
            val ohneKalendereintrag: OhneEintragEinstellungen = OhneEintragEinstellungen(),
            val modellStufe: Int = 0
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

    private val lichtregel = HueScheduleRule(
        id = "regel_1",
        name = "Licht zum Wecker",
        shiftPattern = "Name d",
        timeRanges = listOf(
            HueTimeRange(
                listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP, targetId = "2", targetName = "Flur",
                        actionType = ActionType.TURN_ON, on = true, isGroup = true, sceneId = "s1", sceneName = "Hell"
                    )
                )
            )
        ),
        sunrise = SunriseConfig(enabled = false, durationMinutes = 20)
    )

    /** Der Stand nach #246: Licht an zwei Typen, dazu alles aus Etappe 3 abweichend vom Default. */
    private val mitLicht = neueKonfiguration.copy(
        definitions = neueKonfiguration.definitions.mapIndexed { i, d ->
            when (i) {
                0 -> d.copy(
                    licht = TypLicht().mitRegeln(listOf(lichtregel, lichtregel.copy(id = "regel_2", priority = 4))),
                    dimmen = TypDimmen(an = false, farbwaerme = 60).mitFenster(listOf(TypDimmFenster(staerke = 70))),
                    nichtStoeren = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true)
                )
                1 -> d.copy(licht = TypLicht().mitRegeln(listOf(lichtregel.copy(id = "regel_3"))))
                else -> d
            }
        },
        ohneKalendereintrag = OhneEintragEinstellungen(
            dimmen = TypDimmen(farbwaerme = 5).mitFenster(listOf(TypDimmFenster(staerke = 20))),
            nichtStoerenSolangeGedimmt = true
        ),
        modellStufe = 1,
        lichtStufe = 1
    )

    /** Das neu geschriebene JSON ohne genau die zwei Dinge, die #246 hinzufuegt. */
    private fun ohneLicht(neu: JsonObject): JsonObject {
        assertTrue("Testaufbau: lichtStufe wird geschrieben", neu.containsKey("lichtStufe"))
        val definitionen = neu.getValue("definitions").jsonArray.map { d ->
            assertTrue("Testaufbau: licht wird je Typ geschrieben", d.jsonObject.containsKey("licht"))
            JsonObject(d.jsonObject - "licht")
        }
        return JsonObject(neu - "lichtStufe" + ("definitions" to JsonArray(definitionen)))
    }

    /**
     * #246: Die Version vor dem Licht am Typ liest eine neu geschriebene Konfiguration, ueberliest
     * Regeln und [ShiftConfig.lichtStufe] - und schreibt ALLES andere unveraendert zurueck. Der
     * Vergleich laeuft ueber das ganze JSON, damit auch ein in der Kopie vergessenes Feld auffaellt.
     */
    @Test
    fun `der Decoder von v1_59_0 ueberliest Lichtregeln und lichtStufe und behaelt alles andere`() {
        val geschrieben = json.encodeToString(ShiftConfig.serializer(), mitLicht)

        val alt = json.decodeFromString(V1_59_0.ShiftConfig.serializer(), geschrieben)

        assertEquals("keine zusaetzliche Schicht", mitLicht.definitions.size, alt.definitions.size)
        assertEquals(1, alt.modellStufe)
        assertEquals(
            ohneLicht(json.parseToJsonElement(geschrieben).jsonObject),
            json.encodeToJsonElement(V1_59_0.ShiftConfig.serializer(), alt)
        )
    }

    /**
     * Die Begruendung fuer den EIGENEN Marker (Festlegung #246): schreibt die Version vor #246 zurueck
     * (Revert), fallen Regeln und [ShiftConfig.lichtStufe] ZUSAMMEN weg, die Licht-Migration laeuft
     * beim naechsten Update erneut. [ShiftConfig.modellStufe] dagegen ueberlebt - als Licht-Marker
     * haette sie die Migration fuer immer abgeschaltet, ohne dass Regeln am Typ liegen.
     */
    @Test
    fun `von v1_59_0 zurueckgeschrieben fehlen Regeln und lichtStufe zusammen, die Modell-Stufe bleibt`() {
        val alt = json.decodeFromString(V1_59_0.ShiftConfig.serializer(), json.encodeToString(ShiftConfig.serializer(), mitLicht))

        val wieder = json.decodeFromString(ShiftConfig.serializer(), json.encodeToString(V1_59_0.ShiftConfig.serializer(), alt))

        assertEquals(0, wieder.lichtStufe)
        assertTrue(wieder.definitions.all { it.licht == TypLicht() })
        assertEquals("die Modell-Stufe ueberlebt den Revert", mitLicht.modellStufe, wieder.modellStufe)
        assertEquals(mitLicht.definitions.map { it.dimmen }, wieder.definitions.map { it.dimmen })
        assertEquals(mitLicht.definitions.map { it.nichtStoeren }, wieder.definitions.map { it.nichtStoeren })
        assertEquals(mitLicht.ohneKalendereintrag, wieder.ohneKalendereintrag)
    }
}
