package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Der geraetelokale Startblock (Geraetewechsel erkennen, Master-Pause aufheben, Pausen-Spiegel
 * abgleichen) hat seit dem Direct-Boot-Fix ZWEI Anlaesse: den regulaeren Prozessstart bei
 * entsperrtem Nutzer und das Nachholen nach `ACTION_USER_UNLOCKED`, wenn der Prozess vor der
 * ersten Entsperrung hochkam.
 *
 * Beide duerfen sich ueberschneiden, ohne dass der Block zweimal laeuft: ein zweiter Lauf wuerde
 * `resume()` und `reconcileDirectBootMirror()` erneut ausloesen und damit einen gerade
 * hergestellten Zustand nochmals anfassen. Die Anlaesse liegen auf verschiedenen Threads
 * (Application-Scope auf Dispatchers.IO bzw. Receiver-Thread) - deshalb `compareAndSet`.
 */
class DeviceLocalStartupGateTest {

    @Test
    fun `der Lauf wird genau einmal beansprucht`() {
        val gate = DeviceLocalStartupGate()

        assertTrue("Der erste Anlass muss laufen duerfen", gate.claimRun())
        assertFalse("Der zweite Anlass darf NICHT laufen", gate.claimRun())
        assertFalse(gate.claimRun())
        assertTrue(gate.hasRun)
    }

    @Test
    fun `vor dem ersten Anlass gilt der Block als nicht gelaufen`() {
        assertFalse(DeviceLocalStartupGate().hasRun)
    }

    /**
     * DER REGRESSIONSFALL fuer die Nebenlaeufigkeit: entsperrt der Nutzer genau waehrend des
     * Prozessstarts, treffen regulaerer Lauf und Nachholung gleichzeitig ein.
     */
    @Test
    fun `bei gleichzeitigen Anlaeufen gewinnt genau einer`() {
        val gate = DeviceLocalStartupGate()
        val threads = 32
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (gate.claimRun()) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("Testaufbau haengt", done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("Der Startblock darf hoechstens einmal laufen", 1, winners.get())
    }
}
