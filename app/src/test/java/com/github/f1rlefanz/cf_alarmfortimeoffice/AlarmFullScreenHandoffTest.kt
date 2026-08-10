package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vertrag der Doppelauslösungs-Sperre des Vollbild-Weckers ([OneShotAlarmHandoff]).
 *
 * HINTERGRUND (real belegt, Log 05.08.2026): "User dismissed alarm" und "User snoozed alarm"
 * liefen 24ms auseinander beide vollständig durch — der Nutzer drückte "Alarm stoppen" und bekam
 * trotzdem 5 Minuten später einen Schlummer-Wecker. Umgekehrt räumt ein nachlaufendes Dismiss den
 * gerade geplanten Snooze wieder ab, dann wird gar nicht mehr geweckt.
 *
 * Eine echte Gleichzeitigkeit ließ sich per adb nicht erzeugen, deshalb war der Fix bisher nur
 * durch seinen Kommentar abgesichert. Genau das prüfen diese Tests.
 *
 * NICHT hier prüfbar (Android-Rand): dass `AlarmFullScreenActivity.stopAndClose()` — der
 * Notausgang des Snooze-Fehlerpfads — bewusst NICHT über claim() läuft. Das hält der KDoc dort
 * fest; am Gerät prüfbar, indem man `scheduleSnooze` scheitern lässt (Exact-Alarm-Berechtigung
 * entziehen) und beobachtet, dass der Wecker trotzdem verstummt.
 */
class AlarmFullScreenHandoffTest {

    @Test
    fun `der erste Griff gewinnt, jeder weitere wird abgewiesen`() {
        val handoff = OneShotAlarmHandoff()

        assertTrue("Der erste Aufruf MUSS durchgelassen werden", handoff.claim())
        assertFalse("Der zweite Aufruf ist per Definition ein Versehen", handoff.claim())
        assertFalse("Auch jeder weitere Aufruf bleibt abgewiesen", handoff.claim())
    }

    @Test
    fun `isClaimed beansprucht die Sperre nicht selbst`() {
        val handoff = OneShotAlarmHandoff()

        assertFalse("Frische Sperre ist unbeansprucht", handoff.isClaimed)
        // Der alarmActive-Observer und die STOPPED-Diagnose lesen isClaimed - wuerde das Lesen
        // selbst beanspruchen, wuerde der erste echte Tastendruck danach ins Leere laufen.
        assertFalse(handoff.isClaimed)
        assertTrue(handoff.claim())
        assertTrue("Nach dem Griff muss die Sperre als beansprucht gelten", handoff.isClaimed)
    }

    @Test
    fun `zwei gleichzeitige Griffe - genau einer gewinnt`() {
        // Das ist der real gemeldete Fall: Dismiss und Snooze quasi zeitgleich (24ms).
        repeat(200) {
            val handoff = OneShotAlarmHandoff()
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)

            repeat(2) {
                pool.execute {
                    start.await()
                    if (handoff.claim()) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("Testlauf haengt", done.await(5, TimeUnit.SECONDS))
            pool.shutdown()

            assertEquals("Genau eine der beiden Handlungen darf laufen", 1, winners.get())
        }
    }
}
