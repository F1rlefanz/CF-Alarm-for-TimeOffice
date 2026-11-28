package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueColor
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung.Zaehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypLicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 4 (#247, SPEC E8 und Abschnitt 4): die reine Uebersetzung der heutigen Hue-Regeln
 * an die Typen ([TypLichtUebersetzung]). Je Festlegung des Betreuers ein Fall, dazu Idempotenz,
 * die wortgleiche Rundreise von Szene und Sonnenaufgang und die fehlende Obergrenze.
 *
 * Die Kopie-ids stehen als LITERALE im Test, nicht als Aufruf von [TypLichtUebersetzung.kopieId]:
 * nur so faellt auf, wenn eine Kopie eine Uhrzeit-id bekaeme (`HueScheduleRule.generateId()`) oder
 * die Bildung sich aendert - beides hiesse, dass `getRule`/`deleteRule` nach einem zweiten Lauf ins
 * Leere griffen.
 */
class TypLichtUebersetzungTest {

    private val alle = HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN

    private fun regel(id: String, muster: String, enabled: Boolean = true) = HueScheduleRule(
        id = id,
        name = "Regel $id",
        shiftPattern = muster,
        enabled = enabled,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.LIGHT,
                        targetId = "7",
                        targetName = "Nachttisch",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        duration = 30
                    )
                )
            )
        )
    )

    private fun typ(
        id: String,
        name: String,
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        still: Boolean = false
    ) = ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30), isSilent = still)
        .mitKategorie(kategorie)

    private fun ShiftDefinition.mitLicht(vararg regeln: HueScheduleRule) =
        copy(licht = TypLicht().mitRegeln(regeln.toList()))

    private fun config(vararg typen: ShiftDefinition) = ShiftConfig(definitions = typen.toList(), modellStufe = 1)

    private fun uebersetze(config: ShiftConfig, vararg regeln: HueScheduleRule) =
        TypLichtUebersetzung.uebersetze(config, regeln.toList())

    private fun ShiftConfig.typ(id: String): ShiftDefinition = definitions.single { it.id == id }
    private fun ShiftConfig.regelnVon(id: String): List<HueScheduleRule> = typ(id).licht.regeln
    private fun ShiftConfig.idsVon(id: String): List<String> = regelnVon(id).map { it.id }

    /** Ausser dem Licht der Typen und [ShiftConfig.lichtStufe] darf sich nichts aendern. */
    private fun assertNurLichtGeaendert(vorher: ShiftConfig, nachher: ShiftConfig) {
        assertEquals(TypLichtUebersetzung.LICHT_STUFE, nachher.lichtStufe)
        assertEquals(vorher.definitions.map { it.id }, nachher.definitions.map { it.id })
        nachher.definitions.zip(vorher.definitions).forEach { (n, v) -> assertEquals(v, n.copy(licht = v.licht)) }
        assertEquals(vorher, nachher.copy(definitions = vorher.definitions, lichtStufe = vorher.lichtStufe))
    }

    @Test
    fun `Name - eine Regel geht an den ersten Typ dieses Namens, ohne Gross-Klein, und behaelt ihre id`() {
        val vorher = config(
            typ("f", "Früh"),
            typ("f2", "FRÜH"),
            typ("s", "Spät"),
            typ("k", "Krank", ShiftKategorie.KRANK),
            typ("i", "Termin", ShiftKategorie.IGNORIEREN)
        ).copy(autoAlarmEnabled = false, ohneKalendereintragWie = "f")
        val frueh = regel("r1", "früh")
        val spaet = regel("r2", "Spät", enabled = false)
        val krank = regel("r3", "KRANK")
        val ignoriert = regel("r4", "termin")

        val ergebnis = uebersetze(vorher, frueh, spaet, krank, ignoriert)

        val nachher = ergebnis.config
        assertEquals(listOf(frueh), nachher.regelnVon("f"))
        assertEquals("der zweite Typ desselben Namens bekommt nichts", emptyList<HueScheduleRule>(), nachher.regelnVon("f2"))
        assertEquals("ausgeschaltet bleibt ausgeschaltet", listOf(spaet), nachher.regelnVon("s"))
        // Krank und Ignorieren schliesst die Festlegung nur fuer allgemeine Regeln aus: eine Regel
        // mit ihrem Namen geht verlustfrei mit (laeuft dort ohne Weckpunkt ohnehin nicht).
        assertEquals(listOf(krank), nachher.regelnVon("k"))
        assertEquals(listOf(ignoriert), nachher.regelnVon("i"))
        assertEquals(Zaehler(uebernommen = 4), ergebnis.zaehler)
        assertNurLichtGeaendert(vorher, nachher)
    }

    @Test
    fun `Name - kein Durchfallen auf den zweiten Typ desselben Namens, wenn der erste schon Regeln traegt`() {
        val eigene = regel("e1", "Früh")
        val vorher = config(typ("f", "Früh").mitLicht(eigene), typ("f2", "früh"))

        val ergebnis = uebersetze(vorher, regel("r1", "Früh"))

        assertEquals(listOf(eigene), ergebnis.config.regelnVon("f"))
        assertEquals(emptyList<HueScheduleRule>(), ergebnis.config.regelnVon("f2"))
        assertEquals(Zaehler(schonBelegt = 1), ergebnis.zaehler)
    }

    @Test
    fun `allgemein - an jeden Typ mit moeglichem Wecker kopiert, hinter dessen eigene Regeln, in Bestandsreihenfolge`() {
        val vorher = config(
            typ("f", "Früh"),
            typ("r", "Ruf", ShiftKategorie.RUFBEREITSCHAFT),
            typ("fr", "Frei", ShiftKategorie.FREI),
            typ("u", "Urlaub", ShiftKategorie.URLAUB),
            typ("fb", "FBI", ShiftKategorie.FORTBILDUNG),
            typ("k", "Krank", ShiftKategorie.KRANK),
            typ("i", "Termin", ShiftKategorie.IGNORIEREN)
        )
        val a1 = regel("a1", alle)
        val n1 = regel("n1", "Früh")
        val a2 = regel("a2", "all", enabled = false)
        val n2 = regel("n2", "Früh")

        val ergebnis = uebersetze(vorher, a1, n1, a2, n2)

        val nachher = ergebnis.config
        assertEquals(listOf("n1", "n2", "2:a1@f", "2:a2@f"), nachher.idsVon("f"))
        assertEquals(listOf("2:a1@r", "2:a2@r"), nachher.idsVon("r"))
        assertEquals(listOf("2:a1@fr", "2:a2@fr"), nachher.idsVon("fr"))
        assertEquals(listOf("2:a1@u", "2:a2@u"), nachher.idsVon("u"))
        assertEquals(listOf("2:a1@fb", "2:a2@fb"), nachher.idsVon("fb"))
        assertEquals("Krank bekommt keine Kopie", emptyList<String>(), nachher.idsVon("k"))
        assertEquals("Ignorieren bekommt keine Kopie", emptyList<String>(), nachher.idsVon("i"))
        // Die Kopie ist die Regel bis auf die id - auch `shiftPattern` bleibt "ALL" bzw. "all".
        assertEquals(listOf(n1, n2, a1.copy(id = "2:a1@f"), a2.copy(id = "2:a2@f")), nachher.regelnVon("f"))
        assertEquals(Zaehler(uebernommen = 2, kopiert = 10), ergebnis.zaehler)
        assertNurLichtGeaendert(vorher, nachher)
    }

    @Test
    fun `allgemein - jede id ist ueber alle Typen eindeutig, auch wenn ids das Trennzeichen enthalten`() {
        // Ohne Laengenpraefix ergaeben beide Paare "x@y@z" - zwei Typen teilten sich eine id.
        val vorher = config(typ("z", "Früh"), typ("y@z", "Spät"))

        val ergebnis = uebersetze(vorher, regel("x@y", alle), regel("x", alle), regel("n1", "Früh"))

        val nachher = ergebnis.config
        assertEquals(listOf("n1", "3:x@y@z", "1:x@z"), nachher.idsVon("z"))
        assertEquals(listOf("3:x@y@y@z", "1:x@y@z"), nachher.idsVon("y@z"))
        val ids = nachher.definitions.flatMap { it.licht.regeln.map(HueScheduleRule::id) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `allgemein - ohne Typ mit moeglichem Wecker ist die Regel verwaist`() {
        val vorher = config(typ("k", "Krank", ShiftKategorie.KRANK), typ("i", "Termin", ShiftKategorie.IGNORIEREN))

        val ergebnis = uebersetze(vorher, regel("a1", alle))

        assertEquals(Zaehler(verwaist = 1), ergebnis.zaehler)
        assertNurLichtGeaendert(vorher, ergebnis.config)
        assertEquals(vorher.definitions, ergebnis.config.definitions)
    }

    @Test
    fun `stummer Typ - alle Regeln kommen ausgeschaltet an, auch die allgemeinen, und ausgeschaltete bleiben aus`() {
        val vorher = config(typ("ad1", "AD1", ShiftKategorie.RUFBEREITSCHAFT, still = true), typ("f", "Früh"))
        val n1 = regel("n1", "AD1")
        val n2 = regel("n2", "ad1", enabled = false)
        val a1 = regel("a1", alle)
        val a2 = regel("a2", alle, enabled = false)

        val ergebnis = uebersetze(vorher, n1, n2, a1, a2)

        val nachher = ergebnis.config
        assertEquals(
            listOf(n1.copy(enabled = false), n2, a1.copy(id = "2:a1@ad1", enabled = false), a2.copy(id = "2:a2@ad1")),
            nachher.regelnVon("ad1")
        )
        assertEquals(
            "am klingelnden Typ bleibt jede Regel, wie sie war",
            listOf(a1.copy(id = "2:a1@f"), a2.copy(id = "2:a2@f")),
            nachher.regelnVon("f")
        )
        // Gezaehlt wird nur, was die Uebersetzung ausgeschaltet hat (n1 und die Kopie von a1).
        assertEquals(Zaehler(uebernommen = 2, kopiert = 4, stummAbgeschaltet = 2), ergebnis.zaehler)
        assertNurLichtGeaendert(vorher, nachher)
    }

    @Test
    fun `verwaist - eine Regel, deren Name keinen Typ trifft, wird nicht uebernommen und gezaehlt`() {
        val vorher = config(typ("f", "Früh"))
        val treffer = regel("n1", "Früh")

        // Kein Teiltreffer: "Frühdienst" trifft den Typ "Früh" nicht (wie `passtAufSchicht`).
        val ergebnis = uebersetze(vorher, regel("v1", "Nacht"), treffer, regel("v2", "Frühdienst"))

        assertEquals(listOf(treffer), ergebnis.config.regelnVon("f"))
        assertEquals(Zaehler(uebernommen = 1, verwaist = 2), ergebnis.zaehler)
    }

    @Test
    fun `Typ mit Regeln - behaelt sie und bekommt nichts dazu, auch wenn er nur undeutbare traegt`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // Eine Regel einer spaeteren Version (unbekannter Zieltyp): sie ist gespeichert, aber nicht deutbar.
        val nurUndeutbar = json.decodeFromString(
            TypLicht.serializer(),
            """{"regeln":[{"id":"spaeter","name":"Neu","shiftPattern":"Urlaub","timeRanges":""" +
                """[{"actions":[{"targetType":"ZONE","targetId":"1","actionType":"TURN_ON"}]}]}]}"""
        )
        assertEquals(1, nurUndeutbar.unlesbareRegeln)
        val eigene = regel("e1", "Früh")
        val eigeneStill = regel("e2", "AD1")
        val vorher = config(
            typ("f", "Früh").mitLicht(eigene),
            typ("u", "Urlaub", ShiftKategorie.URLAUB).copy(licht = nurUndeutbar),
            typ("ad1", "AD1", ShiftKategorie.RUFBEREITSCHAFT, still = true).mitLicht(eigeneStill),
            typ("s", "Spät")
        )
        val a1 = regel("a1", alle)

        val ergebnis = uebersetze(vorher, regel("n1", "Früh"), regel("n2", "Urlaub"), a1)

        val nachher = ergebnis.config
        assertEquals(vorher.typ("f").licht, nachher.typ("f").licht)
        assertEquals(vorher.typ("u").licht, nachher.typ("u").licht)
        assertEquals("ein stummer Typ mit eigenen Regeln bleibt unberuehrt", listOf(eigeneStill), nachher.regelnVon("ad1"))
        assertEquals(listOf(a1.copy(id = "2:a1@s")), nachher.regelnVon("s"))
        // n1, n2 und die Kopien von a1 an f, u und ad1 entfallen.
        assertEquals(Zaehler(kopiert = 1, schonBelegt = 5), ergebnis.zaehler)
        assertNurLichtGeaendert(vorher, nachher)
    }

    @Test
    fun `leerer Bestand - setzt nur lichtStufe`() {
        val vorher = config(typ("f", "Früh").mitLicht(regel("e1", "Früh")), typ("s", "Spät", still = true))

        val ergebnis = uebersetze(vorher)

        assertEquals(vorher.copy(lichtStufe = TypLichtUebersetzung.LICHT_STUFE), ergebnis.config)
        assertEquals(Zaehler(), ergebnis.zaehler)
    }

    @Test
    fun `lichtStufe schon gesetzt - die Eingabe kommt unveraendert zurueck`() {
        val alt = arrayOf(regel("n1", "Früh"), regel("a1", alle))
        for (stufe in listOf(TypLichtUebersetzung.LICHT_STUFE, TypLichtUebersetzung.LICHT_STUFE + 1)) {
            val vorher = config(typ("f", "Früh")).copy(lichtStufe = stufe)

            val ergebnis = uebersetze(vorher, *alt)

            assertSame(vorher, ergebnis.config)
            assertEquals(Zaehler(), ergebnis.zaehler)
        }
    }

    @Test
    fun `Idempotenz - zweimal angewendet ergibt dasselbe wie einmal, mit denselben ids`() {
        val vorher = config(
            typ("f", "Früh"),
            typ("ad1", "AD1", ShiftKategorie.RUFBEREITSCHAFT, still = true),
            typ("u", "Urlaub", ShiftKategorie.URLAUB).mitLicht(regel("e1", "Urlaub")),
            typ("k", "Krank", ShiftKategorie.KRANK)
        )
        val alt = arrayOf(regel("n1", "Früh"), regel("a1", alle), regel("n2", "AD1"), regel("v1", "Nacht"))

        val einmal = uebersetze(vorher, *alt)
        val zweimal = uebersetze(einmal.config, *alt)
        val unabhaengig = uebersetze(vorher, *alt)

        val erwarteteIds = mapOf(
            "f" to listOf("n1", "2:a1@f"),
            "ad1" to listOf("n2", "2:a1@ad1"),
            "u" to listOf("e1"),
            "k" to emptyList()
        )
        assertEquals(erwarteteIds, einmal.config.definitions.associate { it.id to it.licht.regeln.map(HueScheduleRule::id) })
        assertSame(einmal.config, zweimal.config)
        // Ein zweiter Lauf ueber denselben Bestand (Abbruch vor dem Speichern) ergibt dieselben ids.
        assertEquals(einmal, unabhaengig)
        // Auch ohne den Marker kommt nichts doppelt an: jeder Typ, der etwas bekommen hat, traegt
        // danach Regeln und behaelt sie (#234).
        assertEquals(einmal.config, uebersetze(einmal.config.copy(lichtStufe = 0), *alt).config)
    }

    @Test
    fun `Szene und Sonnenaufgang ueberstehen die Uebersetzung wortgleich bis auf id und enabled`() {
        // Jedes Feld abweichend vom Default, sonst bliebe ein verlorenes Feld unbemerkt.
        val szene = HueScheduleRule(
            id = "regel_szene",
            name = "Szene Wohnzimmer",
            shiftPattern = "Früh",
            enabled = true,
            timeRanges = listOf(
                HueTimeRange(
                    actions = listOf(
                        HueLightAction(
                            targetType = TargetType.GROUP,
                            targetId = "3",
                            targetName = "Wohnzimmer",
                            actionType = ActionType.TURN_ON,
                            on = true,
                            transitionTime = 40,
                            duration = 25,
                            isGroup = true,
                            sceneId = "Abc123xyz",
                            sceneName = "Entspannen"
                        ),
                        HueLightAction(
                            targetType = TargetType.GROUP,
                            targetId = "5",
                            targetName = "Schlafzimmer",
                            actionType = ActionType.TURN_ON,
                            on = true,
                            isGroup = true,
                            sceneId = "Def456uvw",
                            sceneName = "Nachtlicht"
                        )
                    )
                )
            ),
            priority = 2,
            hellAb = HellAb.DAEMMERUNG,
            ausBeiHell = HellAb.SONNENAUFGANG
        )
        val sonnenaufgang = HueScheduleRule(
            id = "regel_sonne",
            name = "Sonnenaufgang Nachttisch",
            shiftPattern = alle,
            timeRanges = listOf(
                HueTimeRange(
                    actions = listOf(
                        HueLightAction(
                            targetType = TargetType.LIGHT,
                            targetId = "7",
                            targetName = "Nachttisch",
                            actionType = ActionType.TURN_OFF,
                            on = false,
                            brightness = 200,
                            hue = 12000,
                            saturation = 140,
                            colorTemperature = 370,
                            color = HueColor(hue = 11, saturation = 22, xy = listOf(0.3f, 0.4f), rgb = "#FFAA00"),
                            transitionTime = 3,
                            duration = 30
                        )
                    )
                )
            ),
            priority = 1,
            sunrise = SunriseConfig(
                enabled = true,
                durationMinutes = 30,
                startKelvin = 2200,
                endKelvin = 4500,
                endBrightness = 200,
                startBeforeAlarm = false
            ),
            hellAb = HellAb.SONNENAUFGANG,
            ausBeiHell = HellAb.DAEMMERUNG
        )
        val vorher = config(
            typ("f", "Früh", still = true).copy(dimmen = TypDimmen(an = true, farbwaerme = 40)),
            typ("s", "Spät")
        )

        val nachher = uebersetze(vorher, szene, sonnenaufgang).config

        assertEquals(
            listOf(szene.copy(enabled = false), sonnenaufgang.copy(id = "11:regel_sonne@f", enabled = false)),
            nachher.regelnVon("f")
        )
        assertEquals(listOf(sonnenaufgang.copy(id = "11:regel_sonne@s")), nachher.regelnVon("s"))
        // Und so, wie es gespeichert wird: Rundreise durch das Format von `ShiftConfigRepository`.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val gelesen = json.decodeFromString(ShiftConfig.serializer(), json.encodeToString(ShiftConfig.serializer(), nachher))
        assertEquals(nachher, gelesen)
        assertEquals(nachher.regelnVon("f"), gelesen.regelnVon("f"))
    }

    @Test
    fun `die Obergrenze je Schicht gilt nur beim Anlegen - elf Regeln eines Typs kommen alle an`() {
        val vorher = config(typ("f", "Früh"))
        val alt = (1..11).map { regel("n$it", "Früh") } + regel("a1", alle)

        val ergebnis = TypLichtUebersetzung.uebersetze(vorher, alt)

        assertEquals((1..11).map { "n$it" } + "2:a1@f", ergebnis.config.idsVon("f"))
        assertEquals(Zaehler(uebernommen = 11, kopiert = 1), ergebnis.zaehler)
        assertTrue(ergebnis.config.regelnVon("f").size > 10)
    }
}
