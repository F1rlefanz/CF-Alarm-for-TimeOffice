package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.settings.kalenderVorausschauBeschreibung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #51: Die Einstellungskarte speichert nur auf "Übernehmen"/Chip - und lehnt Ungueltiges mit Text
 * ab, statt es still auf die Grenze zu setzen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class KalenderVorausschauViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ungueltige Eingabe wird mit Text abgelehnt und nicht gespeichert`() = runTest(dispatcher) {
        val prefs = FakeKalenderVorausschauPrefs()
        val vm = KalenderVorausschauViewModel(prefs)

        assertFalse(vm.uebernehmen("120"))
        advanceUntilIdle()

        assertNotNull("Die Ablehnung muss sichtbar sein", vm.meldung.value)
        assertTrue("Nichts gespeichert - auch nicht die geklemmte 90", prefs.gesetzt.isEmpty())
    }

    @Test
    fun `gueltige Eingabe wird gespeichert und eine alte Meldung verschwindet`() = runTest(dispatcher) {
        val prefs = FakeKalenderVorausschauPrefs()
        val vm = KalenderVorausschauViewModel(prefs)
        vm.uebernehmen("abc")

        assertTrue(vm.uebernehmen("28"))
        advanceUntilIdle()

        assertEquals(listOf(28), prefs.gesetzt)
        assertNull(vm.meldung.value)
    }

    @Test
    fun `Schnellwahl speichert direkt`() = runTest(dispatcher) {
        val prefs = FakeKalenderVorausschauPrefs()
        val vm = KalenderVorausschauViewModel(prefs)

        vm.waehle(56)
        advanceUntilIdle()

        assertEquals(listOf(56), prefs.gesetzt)
    }

    @Test
    fun `die Karte nennt den eingestellten Wert - und keinen, solange er nicht gelesen ist`() {
        assertTrue(kalenderVorausschauBeschreibung(28).contains("28 Tage"))
        assertFalse(
            "Vor dem Lesen darf keine Zahl behauptet werden",
            kalenderVorausschauBeschreibung(null).any { it.isDigit() }
        )
    }
}
