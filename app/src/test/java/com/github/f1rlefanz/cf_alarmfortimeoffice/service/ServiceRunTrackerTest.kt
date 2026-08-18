package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Haelt fest, WANN ein Service mit geteiltem CoroutineScope abgeraeumt werden darf.
 *
 * HINTERGRUND (echter Fehler, nicht theoretisch): `AlarmMaintenanceService` startete pro
 * `onStartCommand` eine Coroutine auf einem GETEILTEN Scope und beendete im `finally` mit
 * `stopSelf(startId)`. Das schuetzt nur die Reihenfolge "frueherer Lauf wird zuerst fertig".
 * Der wahrscheinliche Fall ist der umgekehrte: ein forceSync-Lauf (Zeitzonen-Wechsel, Boot,
 * Master-Pause-resume, Re-Login) haengt in Token-Refresh und Kalenderabruf, waehrend der
 * regulaere 6h-Lauf in Millisekunden mit "Puffer reicht" zurueckkehrt. Dessen startId IST dann
 * die zuletzt vergebene - Android zerstoert den Service, `onDestroy` cancelt den Scope und
 * schneidet den forceSync-Lauf mitten in `syncAlarms()` ab. Zwischen `deleteAlarm()` und dem
 * folgenden `saveAlarm()`/`scheduleSystemAlarm()` abgeschnitten heisst: fuer diese Schicht gibt
 * es bis zum naechsten Wartungslauf KEINEN Wecker.
 */
class ServiceRunTrackerTest {

    @Test
    fun `ein einzelner Lauf raeumt mit seiner eigenen startId ab`() {
        val tracker = ServiceRunTracker()
        tracker.onStart(1)

        assertEquals(1, tracker.onFinish())
    }

    @Test
    fun `der SPAETER gestartete Lauf raeumt NICHT ab, wenn der fruehere noch arbeitet`() {
        val tracker = ServiceRunTracker()
        tracker.onStart(1) // forceSync-Lauf, haengt im Netz
        tracker.onStart(2) // regulaerer 6h-Lauf, kehrt sofort zurueck

        // Genau der Regressionsfall: frueher lieferte hier stopSelf(2) den Abriss.
        assertNull("Der noch laufende Lauf 1 darf nicht abgeraeumt werden", tracker.onFinish())
        assertEquals(1, tracker.activeRunCount())

        // Erst der zuletzt endende Lauf raeumt ab - mit dem HOECHSTEN gesehenen startId, damit
        // Android den Stopp nicht als veraltet verwirft.
        assertEquals(2, tracker.onFinish())
        assertEquals(0, tracker.activeRunCount())
    }

    @Test
    fun `auch bei umgekehrter Endreihenfolge raeumt erst der letzte ab`() {
        val tracker = ServiceRunTracker()
        tracker.onStart(1)
        tracker.onStart(2)

        assertNull(tracker.onFinish()) // Lauf 1 zuerst fertig
        assertEquals(2, tracker.onFinish())
    }

    @Test
    fun `drei ueberlappende Laeufe raeumen genau einmal ab`() {
        val tracker = ServiceRunTracker()
        tracker.onStart(5)
        tracker.onStart(6)
        tracker.onStart(7)

        assertNull(tracker.onFinish())
        assertNull(tracker.onFinish())
        assertEquals(7, tracker.onFinish())
    }

    @Test
    fun `ein zusaetzlicher onFinish laesst den Zaehler nicht negativ werden`() {
        val tracker = ServiceRunTracker()
        tracker.onStart(3)

        assertEquals(3, tracker.onFinish())
        // Darf nicht dazu fuehren, dass ein spaeter angemeldeter Lauf nie abraeumt.
        tracker.onFinish()
        assertEquals(0, tracker.activeRunCount())

        tracker.onStart(4)
        assertEquals(4, tracker.onFinish())
    }

    @Test
    fun `nebenlaeufige Laeufe liefern genau ein Abraeum-Signal`() {
        val tracker = ServiceRunTracker()
        val runs = 50
        val start = CountDownLatch(1)
        val done = CountDownLatch(runs)
        val stopIds = java.util.Collections.synchronizedList(mutableListOf<Int>())

        repeat(runs) { i -> tracker.onStart(i + 1) }
        repeat(runs) {
            Thread {
                start.await()
                tracker.onFinish()?.let { stopIds.add(it) }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(10, TimeUnit.SECONDS)

        assertEquals("Genau ein Lauf darf stopSelf rufen", 1, stopIds.size)
        assertEquals(runs, stopIds[0])
        assertEquals(0, tracker.activeRunCount())
    }
}
