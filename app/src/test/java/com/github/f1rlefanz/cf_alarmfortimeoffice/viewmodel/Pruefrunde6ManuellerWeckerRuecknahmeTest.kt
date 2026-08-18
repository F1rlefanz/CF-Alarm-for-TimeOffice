package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruefrunde 6: Die kompensierende Ruecknahme eines manuellen Weckers, der gespeichert, aber nicht
 * armiert werden konnte.
 *
 * DER FEHLERFALL, GEGEN DEN SIE GEBAUT IST: Bleibt der Eintrag im Repository (und damit im
 * Direct-Boot-Spiegel) stehen, ohne dass je ein System-Alarm dazu existiert, zeigt die Karte
 * "Manueller Alarm aktiv" samt Uhrzeit - waehrend der Fehlertext dem Nutzer woertlich zusichert,
 * der Wecker sei NICHT aufgenommen worden. Ein manueller Wecker wird genau einmal armiert
 * (`syncAlarms()` SCHONT ihn per `keepManualAlarms` nur), ein Nachholer kommt also nicht.
 *
 * DIE REGRESSION: Die Ruecknahme stand als blankes `runCatching { ... }` direkt im
 * `viewModelScope`. `SafeExecutor.safeExecute` wirft `CancellationException` ausdruecklich weiter,
 * und `runCatching` faengt `Throwable` - also auch die. Verlaesst der Nutzer die App in genau dem
 * Moment, in dem das Stellen scheitert (Activity wird abgeraeumt), wurde die Ruecknahme still zum
 * No-op: der stumme Wecker MIT Anzeige blieb genau dann stehen, wenn die Ruecknahme gebraucht
 * wurde.
 */
class Pruefrunde6ManuellerWeckerRuecknahmeTest {

    @Test
    fun `die Ruecknahme laeuft zu Ende, auch wenn der Scope mittendrin abgebrochen wird`() = runTest {
        val schritte = mutableListOf<String>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        val job = scope.launch {
            nimmAlarmZurueck(
                alarmId = 4711,
                logTag = "TEST",
                // Beide Schritte suspendieren, wie ihre echten Vorbilder (DataStore/AlarmManager
                // hinter SafeExecutor). Vermerkt wird NACH dem Suspensionspunkt - nur so
                // unterscheidet der Test "ist gelaufen" von "wurde begonnen und abgebrochen".
                cancelSystemAlarm = { delay(10); schritte += "cancelSystemAlarm"; Result.success(Unit) },
                deleteAlarm = { delay(10); schritte += "deleteAlarm"; Result.success(Unit) }
            )
        }

        // Der Lauf haengt jetzt im ersten Suspensionspunkt - und genau hier verlaesst der Nutzer
        // die App.
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(
            "OHNE withContext(NonCancellable) schluckt runCatching die CancellationException und " +
                "die Ruecknahme wird zum stillen No-op - der Alarm bleibt im Bestand und im " +
                "Direct-Boot-Spiegel stehen, ohne dass ihn je ein System-Alarm traegt",
            listOf("cancelSystemAlarm", "deleteAlarm"),
            schritte
        )
    }

    @Test
    fun `die Reihenfolge ist erst cancelSystemAlarm, dann deleteAlarm`() = runTest {
        val schritte = mutableListOf<String>()

        nimmAlarmZurueck(
            alarmId = 1,
            logTag = "TEST",
            cancelSystemAlarm = { schritte += "cancelSystemAlarm"; Result.success(Unit) },
            deleteAlarm = { schritte += "deleteAlarm"; Result.success(Unit) }
        )

        // Umgekehrt entstuende ein armierter Alarm, den weder Repository noch Direct-Boot-Spiegel
        // kennen - unsichtbar UND unabbrechbar bis zum naechsten Neustart.
        assertEquals(listOf("cancelSystemAlarm", "deleteAlarm"), schritte)
    }

    @Test
    fun `ein Fehlschlag des Cancelns haelt das Loeschen nicht auf`() = runTest {
        val schritte = mutableListOf<String>()

        nimmAlarmZurueck(
            alarmId = 1,
            logTag = "TEST",
            cancelSystemAlarm = { throw IllegalStateException("AlarmManager verweigert") },
            deleteAlarm = { schritte += "deleteAlarm"; Result.success(Unit) }
        )

        assertEquals(
            "Ein halb zurueckgenommener Alarm ist schlimmer als ein ganz zurueckgenommener",
            listOf("deleteAlarm"),
            schritte
        )
    }
}
