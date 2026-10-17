package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.LocalTime

/**
 * Umbau Etappe 1 (#166): Kategorie, „Wecker an/aus“ und der Ohne-Eintrag-Verweis reisen beim
 * Geraetewechsel mit - durch den ECHTEN [ConfigBackupUseCase], nicht nur durch das Format. Der
 * Import geht typisiert ueber [ShiftConfig] und muss die Felder dabei behalten (er kopiert die
 * Datei-Konfiguration nur mit dem lokalen `autoAlarmEnabled`). Und eine Sicherung von vor dem Umbau
 * liest sich mit abgeleiteten Kategorien.
 *
 * Fixture wie in [ImportHueNachzugTest].
 */
class ConfigBackupKategorieTest {

    /** Minimaler DataStore im Speicher - `edit {}` laeuft ueber [updateData]. */
    private class SpeicherStore : DataStore<Preferences> {
        private val stand = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = stand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(stand.value).also { stand.value = it }
    }

    private fun shiftUseCase(aktuell: ShiftConfig) = mock<IShiftUseCase> {
        onBlocking { getCurrentShiftConfig() } doReturn Result.success(aktuell)
        onBlocking { saveShiftConfig(any()) } doReturn Result.success(Unit)
    }

    private fun useCase(shiftUseCase: IShiftUseCase) = ConfigBackupUseCase(
        mainDataStore = SpeicherStore(),
        hueDataStore = SpeicherStore(),
        shiftUseCase = shiftUseCase,
        armierer = mock<ZeitkettenArmierer>(),
        dimmerModellMigration = mock<DimmerModellMigration>(),
        rufbereitschaftMigration = mock<RufbereitschaftMigration>(),
        hueLightUseCase = mock<IHueLightUseCase>(),
        hueRuleUseCase = mock<IHueRuleUseCase>(),
        hueSmartScheduler = { mock<HueSmartScheduler>() }
    )

    private fun typ(id: String, k: ShiftKategorie) =
        ShiftDefinition(id = id, name = "Name $id", keywords = listOf(id.uppercase()), alarmTime = LocalTime.of(6, 0))
            .mitKategorie(k)

    private suspend fun importiere(datei: String): ShiftConfig {
        val ziel = shiftUseCase(ShiftConfig.getDefaultConfig())
        val ergebnis = useCase(ziel).import(datei)
        assertTrue("Import muss gelingen: ${ergebnis.exceptionOrNull()}", ergebnis.isSuccess)
        val gespeichert = argumentCaptor<ShiftConfig>()
        verify(ziel).saveShiftConfig(gespeichert.capture())
        return gespeichert.firstValue
    }

    @Test
    fun `Sicherung mit Kategorien, Wecker aus und Ohne-Eintrag-Verweis uebersteht Export und Import verlustfrei`() = runTest {
        val quelle = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = ShiftKategorie.entries.map { typ(it.schluessel, it) } +
                typ("still", ShiftKategorie.DIENST).copy(weckerAn = false, isSilent = true),
            ohneKalendereintragWie = ShiftKategorie.FREI.schluessel
        )

        val datei = useCase(shiftUseCase(quelle)).export(appVersion = "test", createdAt = "2026-10-16").getOrThrow()
        val importiert = importiere(datei)

        assertEquals(quelle, importiert)
        assertEquals(quelle.definitions.map { it.kategorie }, importiert.definitions.map { it.kategorie })
        assertEquals(listOf("still"), importiert.definitions.filterNot { it.weckerAn }.map { it.id })
        assertEquals(ShiftKategorie.FREI.schluessel, importiert.ohneKalendereintragWieTyp()?.id)
    }

    @Test
    fun `alte Sicherung ohne die neuen Felder wird mit abgeleiteten Kategorien gelesen`() = runTest {
        // Aufbau einer Exportdatei von v1.56.1 - die Felder `kategorie`, `weckerAn` und
        // `ohneKalendereintragWie` gab es noch nicht.
        val alteDatei = """
            {
              "formatVersion": 1,
              "appVersion": "1.56.1",
              "createdAt": "2026-10-01",
              "shiftConfig": {
                "autoAlarmEnabled": true,
                "definitions": [
                  { "id": "a", "name": "Dienst A", "keywords": ["A", "AX"], "alarmTime": "05:30",
                    "isEnabled": true, "isSilent": false, "isOnCall": false, "isSickDay": false },
                  { "id": "r", "name": "Ruf", "keywords": ["R"], "alarmTime": "05:00",
                    "isEnabled": true, "isSilent": true, "isOnCall": true, "isSickDay": false },
                  { "id": "k", "name": "Krank", "keywords": ["K"], "alarmTime": "06:00",
                    "isEnabled": true, "isSilent": false, "isOnCall": false, "isSickDay": true },
                  { "id": "b", "name": "Beides", "keywords": ["B"], "alarmTime": "06:00",
                    "isEnabled": true, "isSilent": false, "isOnCall": true, "isSickDay": true }
                ]
              }
            }
        """.trimIndent()

        val importiert = importiere(alteDatei)

        assertEquals(
            listOf(ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.KRANK, ShiftKategorie.KRANK),
            importiert.definitions.map { it.kategorie }
        )
        assertTrue(importiert.definitions.all { it.weckerAn })
        assertEquals(null, importiert.ohneKalendereintragWie)
        assertEquals(listOf("A", "AX"), importiert.definitions.first().keywords)
    }

    @Test
    fun `verwaister Ohne-Eintrag-Verweis wird importiert und liest sich als eigene Einstellungen`() = runTest {
        val quelle = ShiftConfig(definitions = listOf(typ("a", ShiftKategorie.DIENST)), ohneKalendereintragWie = "geloescht")

        val importiert = importiere(useCase(shiftUseCase(quelle)).export("test", "2026-10-16").getOrThrow())

        assertEquals("geloescht", importiert.ohneKalendereintragWie)
        assertEquals(null, importiert.ohneKalendereintragWieTyp())
    }
}
