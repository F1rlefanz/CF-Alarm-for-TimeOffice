package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Review-Befund #140: Der Konfigurations-Import schreibt zwei Eingaben der Hue-
 * Sonnenaufgangsplanung - den Ort (`tageslicht_land`/`tageslicht_plz`) und die Hue-Regeln samt
 * `hellAb` -, zog die Planung aber nicht nach. Der Scheduler reagiert von sich aus nur auf
 * Alarm-Aenderungen und den Tagesplaner: eine Regel, die am alten Ort als "entfaellt" galt,
 * bekam bis zu 24 h keinen Vorlauf-Worker. Gleicher Nachzug wie im TageslichtViewModel.
 */
class ImportHueNachzugTest {

    /** Minimaler DataStore im Speicher - `edit {}` laeuft ueber [updateData]. */
    private class SpeicherStore : DataStore<Preferences> {
        private val stand = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = stand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(stand.value).also { stand.value = it }
    }

    private fun useCase(scheduler: HueSmartScheduler) = ConfigBackupUseCase(
        mainDataStore = SpeicherStore(),
        hueDataStore = SpeicherStore(),
        shiftUseCase = mock<IShiftUseCase>(),
        armierer = mock<ZeitkettenArmierer>(),
        dimmerModellMigration = mock<DimmerModellMigration>(),
        rufbereitschaftMigration = mock<RufbereitschaftMigration>(),
        hueLightUseCase = mock<IHueLightUseCase>(),
        hueRuleUseCase = mock<IHueRuleUseCase>(),
        hueSmartScheduler = { scheduler }
    )

    /** Datei, die nur den Ort traegt - genau der Fall aus dem Befund. */
    private val ortDatei = """
        {
          "formatVersion": 1,
          "settings": {
            "tageslicht_land": { "type": "string", "value": "DE" },
            "tageslicht_plz": { "type": "string", "value": "52062" }
          }
        }
    """.trimIndent()

    @Test
    fun `Import zieht die Hue-Sonnenaufgangsplanung nach`() = runTest {
        val scheduler = mock<HueSmartScheduler>()

        val ergebnis = useCase(scheduler).import(ortDatei)

        assertTrue("Import selbst muss gelingen: ${ergebnis.exceptionOrNull()}", ergebnis.isSuccess)
        assertEquals(2, ergebnis.getOrThrow().settingsKeys)
        verify(scheduler, times(1)).recalculateSchedule()
    }

    @Test
    fun `Fehlschlag des Hue-Nachzugs meldet den geschriebenen Import nicht als gescheitert`() = runTest {
        val scheduler = mock<HueSmartScheduler> {
            on { recalculateSchedule() } doThrow IllegalStateException("Scheduler kaputt")
        }

        val ergebnis = useCase(scheduler).import(ortDatei)

        assertTrue("Best-effort: ${ergebnis.exceptionOrNull()}", ergebnis.isSuccess)
        verify(scheduler, times(1)).recalculateSchedule()
    }
}
