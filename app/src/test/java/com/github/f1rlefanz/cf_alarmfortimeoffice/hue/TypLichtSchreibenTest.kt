package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtSchreiben
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File
import java.time.LocalTime

/**
 * Die ECHTE Schicht-Konfiguration (Repository samt Transaktion) ueber einem echten Datei-DataStore
 * im Ordner [ordner] - ohne Android-Context. Der `SpeicherStore` anderer Tests taugt hier nicht:
 * sein `updateData` ist nicht serialisiert, ein Wettlauf liesse sich damit gar nicht zeigen.
 */
internal class EchteSchichtKonfiguration(ordner: File) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(ordner, "shift_prefs.preferences_pb")
    }
    val repo = ShiftConfigRepository(store, entsperrt = { true })

    fun schliessen() = scope.cancel()
}

/**
 * Umbau Etappe 4 (#250): Lichtregeln werden AM TYP angelegt, geaendert, geloescht und abgeglichen -
 * in EINER Transaktion (`IShiftConfigRepository.updateShiftConfig`), nicht mehr im Hue-Store.
 */
class TypLichtSchreibenTest {

    @get:Rule
    val ordner = TemporaryFolder()

    private val offen = mutableListOf<EchteSchichtKonfiguration>()

    @After
    fun aufraeumen() = offen.forEach { it.schliessen() }

    private fun echt() = EchteSchichtKonfiguration(ordner.newFolder()).also { offen += it }

    private fun regel(id: String, muster: String, lampe: String = "7", lampenName: String? = "Bad") = HueSchedule(
        id = id,
        name = "Licht $id",
        shiftPattern = muster,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.LIGHT,
                        targetId = lampe,
                        targetName = lampenName,
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        duration = 30
                    )
                )
            )
        )
    )

    private fun typ(id: String, name: String, vararg regeln: HueSchedule) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30)).let { t ->
            t.copy(licht = t.licht.mitRegeln(regeln.toList()))
        }

    private fun useCase(repo: IShiftConfigRepository, altbestand: AltbestandFake = AltbestandFake()) = HueRuleUseCase(
        configRepository = altbestand,
        lightUseCase = mock<IHueLightUseCaseAdvanced>(),
        typLicht = TypLichtQuelle.Leser { repo.getCurrentShiftConfig() },
        schichtKonfiguration = repo
    )

    /** Die neue Bridge: „Bad“ heisst dort Lampe 3. */
    private val neueBridge = LightTargets(lights = listOf(HueLight(id = "3", name = "Bad", state = LightState(on = false))))

    private fun ShiftConfig.regelnVon(name: String) = definitions.first { it.name == name }.licht.regeln
    private fun ShiftConfig.lampenVon(name: String) =
        regelnVon(name).flatMap { r -> r.lightActions.map { "${r.id}:${it.targetId}" } }

    /**
     * Gibt die Transaktion des ERSTEN Aufrufs erst frei, wenn der Test es sagt: so laeuft ein
     * zweiter Schreiber garantiert WAEHREND des Abgleichs - kein Hoffen auf Timing.
     */
    private class Tor(private val echt: IShiftConfigRepository) : IShiftConfigRepository by echt {
        val imAbgleich = CompletableDeferred<Unit>()
        val weiter = CompletableDeferred<Unit>()
        private var erster = true

        override suspend fun updateShiftConfig(transform: suspend (ShiftConfig) -> ShiftConfig): Result<ShiftConfig> {
            val parken = synchronized(this) { erster.also { erster = false } }
            return echt.updateShiftConfig { config ->
                if (parken) {
                    imAbgleich.complete(Unit)
                    weiter.await()
                }
                transform(config)
            }
        }
    }

    @Test
    fun `gleichzeitiger Abgleich und Speichern einer Regel verlieren nichts`() = runBlocking {
        val k = echt()
        k.repo.saveShiftConfig(ShiftConfig(definitions = listOf(typ("f", "F", regel("r1", "F")), typ("s", "S")))).getOrThrow()
        val tor = Tor(k.repo)
        val uc = useCase(tor)

        withTimeout(10_000) {
            val abgleich = async(Dispatchers.Default) { uc.reconcileTargets(neueBridge) }
            tor.imAbgleich.await()
            val speichern = async(Dispatchers.Default) { uc.createRule(regel("r2", "S", lampe = "9", lampenName = "Flur")) }
            // Waehrend der Abgleich in seiner Transaktion steht, darf das Speichern nicht durchkommen:
            // mit Lesen-dann-Speichern schriebe es hier und der Abgleich danach darueber.
            assertNull("Speichern lief am Abgleich vorbei", withTimeoutOrNull(300) { speichern.await() })
            tor.weiter.complete(Unit)

            assertEquals(1, abgleich.await().getOrThrow().remapped)
            assertTrue(speichern.await().isSuccess)
        }

        val danach = k.repo.getCurrentShiftConfig().getOrThrow()
        assertEquals("Abgleich am Typ F", listOf("r1:3"), danach.lampenVon("F"))
        assertEquals("gespeicherte Regel am Typ S", listOf("r2:9"), danach.lampenVon("S"))
        // Und auf der Platte, nicht nur im Cache:
        val roh = k.store.data.first()[stringPreferencesKey("shift_config")]!!
        assertTrue(roh, roh.contains("\"targetId\":\"3\"") && roh.contains("\"id\":\"r2\""))
    }

    @Test
    fun `der Abgleich schreibt den Typ, nicht den Altbestand`() = runBlocking {
        val k = echt()
        k.repo.saveShiftConfig(ShiftConfig(definitions = listOf(typ("f", "F", regel("r1", "F"))))).getOrThrow()
        val altbestand = AltbestandFake(listOf(regel("r1", "F")))

        val ergebnis = useCase(k.repo, altbestand).reconcileTargets(neueBridge).getOrThrow()

        assertEquals(1, ergebnis.remapped)
        assertEquals(listOf("r1:3"), k.repo.getCurrentShiftConfig().getOrThrow().lampenVon("F"))
        assertEquals("Altbestand eingefroren", "7", altbestand.store.single().lightActions.single().targetId)
    }

    @Test
    fun `Loeschen trifft genau eine Regel eines Typs, auch bei Kopien einer frueher allgemeinen Regel`() = runBlocking {
        val k = echt()
        val typen = ShiftConfig(definitions = listOf(typ("f", "F"), typ("s", "S"), typ("n", "N")))
        // Die echte Migration verteilt die allgemeine Regel als Kopie an jeden Typ (#247).
        k.repo.saveShiftConfig(TypLichtUebersetzung.uebersetze(typen, listOf(regel("a1", "ALL"))).config).getOrThrow()
        val uc = useCase(k.repo)
        val kopieAnS = uc.getAllRules().getOrThrow().single { it.shiftPattern == "S" }

        assertTrue(uc.deleteRule(kopieAnS.id).isSuccess)

        val danach = k.repo.getCurrentShiftConfig().getOrThrow()
        assertEquals(1, danach.regelnVon("F").size)
        assertEquals(0, danach.regelnVon("S").size)
        assertEquals(1, danach.regelnVon("N").size)

        // Dieselbe id an zwei Typen (Fremdbestand): es trifft nur die erste, wie beim Leser.
        k.repo.saveShiftConfig(ShiftConfig(definitions = listOf(typ("f", "F", regel("dup", "F")), typ("s", "S", regel("dup", "S"))))).getOrThrow()
        assertTrue(uc.deleteRule("dup").isSuccess)
        val doppelt = k.repo.getCurrentShiftConfig().getOrThrow()
        assertEquals(0, doppelt.regelnVon("F").size)
        assertEquals(1, doppelt.regelnVon("S").size)
    }

    @Test
    fun `Anlegen zaehlt die Hoechstzahl je Typ, lehnt Alle Schichten ab und Aendern zieht um`() = runBlocking {
        val k = echt()
        val zehn = (1..10).map { regel("f$it", "F") }.toTypedArray()
        k.repo.saveShiftConfig(ShiftConfig(definitions = listOf(typ("f", "F", *zehn), typ("s", "S")))).getOrThrow()
        val uc = useCase(k.repo)

        assertTrue("F ist voll", uc.createRule(regel("neu", "F")).isFailure)
        assertTrue("S zaehlt fuer sich", uc.createRule(regel("neu", "S")).isSuccess)
        assertEquals(
            TypLichtSchreiben.GRUND_ALLE_SCHICHTEN,
            uc.createRule(regel("alle", "ALL")).exceptionOrNull()?.message
        )
        assertTrue("doppelte id", uc.createRule(regel("neu", "S")).isFailure)

        assertTrue(uc.updateRule(regel("f1", "S", lampe = "5")).isSuccess)
        val danach = k.repo.getCurrentShiftConfig().getOrThrow()
        assertEquals(9, danach.regelnVon("F").size)
        assertEquals(listOf("neu:7", "f1:5"), danach.lampenVon("S"))
    }

    @Test
    fun `eine unlesbare Konfiguration ist ein Fehlschlag und wird nicht ueberschrieben`() = runBlocking {
        val k = echt()
        val schluessel = stringPreferencesKey("shift_config")
        k.store.edit { it[schluessel] = "{kaputt" }

        val ergebnis = useCase(k.repo).createRule(regel("r1", "F"))

        assertTrue(ergebnis.isFailure)
        val roh = k.store.data.first()
        assertEquals("{kaputt", roh[schluessel])
        assertEquals("Rohdaten gesichert", "{kaputt", roh[stringPreferencesKey("shift_config_broken")])
    }
}
