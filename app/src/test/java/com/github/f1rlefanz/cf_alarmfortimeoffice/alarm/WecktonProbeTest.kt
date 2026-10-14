package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probehoeren: Dauer, Pegelverlauf und vor allem das Aufhoeren - nach Ablauf, auf Knopfdruck,
 * bei Fokusverlust, bei einem echten Wecker und bei jedem Fehler. Eine Probe, die nicht aufhoert,
 * ist ein Weckton im Hintergrund, den niemand bestellt hat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WecktonProbeTest {

    private val anstieg30s = WecktonAnstieg(aktiv = true, sekunden = 30, startProzent = 10)

    // ---- reine Dauer-Funktion ----

    @Test
    fun `Dauer ohne Anstieg ist zehn Sekunden`() {
        assertEquals(10_000L, WecktonProbe.probeDauerMs(WecktonAnstieg.AUS))
        // Auch eine eingetragene, aber abgeschaltete Anlaufzeit zaehlt nicht.
        assertEquals(10_000L, WecktonProbe.probeDauerMs(anstieg30s.copy(aktiv = false)))
    }

    @Test
    fun `Dauer mit Anstieg ist Anlaufzeit plus fuenf Sekunden`() {
        assertEquals(35_000L, WecktonProbe.probeDauerMs(anstieg30s))
        assertEquals(10_000L, WecktonProbe.probeDauerMs(anstieg30s.copy(sekunden = AlarmPrefs.MIN_ANSTIEG_SEKUNDEN)))
        assertEquals(125_000L, WecktonProbe.probeDauerMs(anstieg30s.copy(sekunden = AlarmPrefs.MAX_ANSTIEG_SEKUNDEN)))
    }

    // ---- Attrappen ----

    private class FakeKlang : ProbeKlang {
        val pegel = mutableListOf<Float>()
        var freigegeben = 0
        override fun setzePegel(pegel: Float) { this.pegel += pegel }
        override fun freigeben() { freigegeben++ }
    }

    private class FakeAudio : ProbeAudio {
        var fokusErteilt = true
        var fokusAnfragen = 0
        var fokusFreigaben = 0
        var beiVerlust: (() -> Unit)? = null
        var beiFehler: (() -> Unit)? = null
        var startPegel: Float? = null
        var klangVorgabe: () -> ProbeKlang? = { FakeKlang() }
        val klaenge = mutableListOf<ProbeKlang>()

        override fun fordereFokus(beiVerlust: () -> Unit): Boolean {
            fokusAnfragen++
            this.beiVerlust = beiVerlust
            return fokusErteilt
        }

        override fun gibFokusFrei() { fokusFreigaben++ }

        override fun spiele(startPegel: Float, beiFehler: () -> Unit): ProbeKlang? {
            this.startPegel = startPegel
            this.beiFehler = beiFehler
            return klangVorgabe()?.also { klaenge += it }
        }
    }

    private class Aufbau(scope: TestScope) {
        val audio = FakeAudio()
        val wecker = MutableStateFlow(false)
        var fabrikAufrufe = 0
        val probe = WecktonProbe(
            audioFabrik = { fabrikAufrufe++; audio },
            dispatcherFabrik = { StandardTestDispatcher(scope.testScheduler) },
            uhr = { scope.testScheduler.currentTime },
            weckerAktiv = { wecker }
        )
        val klang get() = audio.klaenge.last() as FakeKlang
    }

    // ---- Lebenszyklus ----

    @Test
    fun `Konstruktor fasst das Audio-System nicht an`() = runTest {
        val a = Aufbau(this)
        // Direct Boot: der Graph baut den Singleton, lange bevor jemand probehoert.
        assertEquals(0, a.fabrikAufrufe)
        assertEquals(ProbeZustand.Aus(), a.probe.zustand.value)
    }

    @Test
    fun `Start spielt mit Startpegel des Anstiegs unter Audio-Fokus`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()

        assertEquals(1, a.audio.fokusAnfragen)
        assertEquals(0.1f, a.audio.startPegel!!, 0.0001f)
        val z = a.probe.zustand.value as ProbeZustand.Laeuft
        assertEquals(35_000L, z.gesamtMs)
        assertEquals(35_000L, z.restMs)
        a.probe.stop()
    }

    @Test
    fun `Pegel folgt LautstaerkeAnstieg und die Probe endet von selbst`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()

        advanceTimeBy(15_000L)
        runCurrent()
        val mitte = a.probe.zustand.value as ProbeZustand.Laeuft
        assertEquals(15_000L, mitte.vergangenMs)
        assertEquals(LautstaerkeAnstieg.pegel(0.1f, 30_000L, 15_000L), mitte.pegel, 0.0001f)
        assertEquals(mitte.pegel, a.klang.pegel.last(), 0.0001f)

        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1.0f, (a.probe.zustand.value as ProbeZustand.Laeuft).pegel, 0.0001f)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(ProbeZustand.Aus(), a.probe.zustand.value)
        assertEquals(1, a.klang.freigegeben)
        assertEquals(1, a.audio.fokusFreigaben)
    }

    @Test
    fun `ohne Anstieg voller Pegel und Ende nach zehn Sekunden`() = runTest {
        val a = Aufbau(this)
        a.probe.start(WecktonAnstieg.AUS)
        runCurrent()
        assertEquals(1.0f, a.audio.startPegel!!, 0.0001f)

        advanceTimeBy(9_999L)
        runCurrent()
        assertTrue(a.probe.zustand.value is ProbeZustand.Laeuft)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(ProbeZustand.Aus(), a.probe.zustand.value)
        assertTrue(a.klang.pegel.all { it == 1.0f })
    }

    @Test
    fun `Stopp gibt Klang und Fokus frei und haelt die Pegelkette an`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        a.probe.stop()
        val schritteBeimStopp = a.klang.pegel.size
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(ProbeZustand.Aus(), a.probe.zustand.value)
        assertEquals(1, a.klang.freigegeben)
        assertEquals(1, a.audio.fokusFreigaben)
        assertEquals(schritteBeimStopp, a.klang.pegel.size)
    }

    @Test
    fun `Stopp im Ruhezustand ist gefahrlos`() = runTest {
        val a = Aufbau(this)
        a.probe.stop()
        a.probe.stop()
        assertEquals(ProbeZustand.Aus(), a.probe.zustand.value)
        assertEquals(0, a.audio.fokusFreigaben)
    }

    @Test
    fun `Fokusverlust beendet die Probe`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()

        a.audio.beiVerlust!!.invoke()
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.UNTERBROCHEN), a.probe.zustand.value)
        assertEquals(1, a.klang.freigegeben)
        assertEquals(1, a.audio.fokusFreigaben)
    }

    @Test
    fun `verweigerter Fokus spielt gar nicht erst`() = runTest {
        val a = Aufbau(this)
        a.audio.fokusErteilt = false
        a.probe.start(anstieg30s)
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.FOKUS_VERWEIGERT), a.probe.zustand.value)
        assertTrue(a.audio.klaenge.isEmpty())
    }

    // ---- Wecker-Vorrang ----

    @Test
    fun `waehrend ein Wecker klingelt startet keine Probe`() = runTest {
        val a = Aufbau(this)
        a.wecker.value = true
        a.probe.start(anstieg30s)
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.WECKER_LAEUFT), a.probe.zustand.value)
        assertEquals(0, a.audio.fokusAnfragen)
        assertTrue(a.audio.klaenge.isEmpty())
    }

    @Test
    fun `ein beginnender Wecker beendet die Probe`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()

        a.wecker.value = true
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.WECKER_LAEUFT), a.probe.zustand.value)
        assertEquals(1, a.klang.freigegeben)
        assertEquals(1, a.audio.fokusFreigaben)
    }

    // ---- Fehler: nie Absturz, immer Aus mit Grund ----

    @Test
    fun `keine nutzbare Tonquelle meldet KEIN_WECKTON und gibt den Fokus zurueck`() = runTest {
        val a = Aufbau(this)
        a.audio.klangVorgabe = { null }
        a.probe.start(anstieg30s)
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.KEIN_WECKTON), a.probe.zustand.value)
        assertEquals(1, a.audio.fokusFreigaben)
    }

    @Test
    fun `werfender Player wird zu WIEDERGABE_GESCHEITERT statt Absturz`() = runTest {
        val a = Aufbau(this)
        a.audio.klangVorgabe = { throw IllegalStateException("MediaPlayer kaputt") }
        a.probe.start(anstieg30s)
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.WIEDERGABE_GESCHEITERT), a.probe.zustand.value)
        assertEquals(1, a.audio.fokusFreigaben)
    }

    @Test
    fun `spaeter Abspielfehler beendet die Probe`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()

        a.audio.beiFehler!!.invoke()
        runCurrent()

        assertEquals(ProbeZustand.Aus(ProbeFehler.WIEDERGABE_GESCHEITERT), a.probe.zustand.value)
        assertEquals(1, a.klang.freigegeben)
    }

    @Test
    fun `werfendes Pegelsetzen beendet die Probe nicht`() = runTest {
        val a = Aufbau(this)
        a.audio.klangVorgabe = {
            object : ProbeKlang {
                override fun setzePegel(pegel: Float) = throw IllegalStateException("Fehlerzustand")
                override fun freigeben() {}
            }
        }
        a.probe.start(anstieg30s)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(a.probe.zustand.value is ProbeZustand.Laeuft)
        a.probe.stop()
    }

    // ---- Neustart ----

    @Test
    fun `Neustart ersetzt den alten Lauf und dessen spaete Rueckrufe sind wirkungslos`() = runTest {
        val a = Aufbau(this)
        a.probe.start(anstieg30s)
        runCurrent()
        val alterKlang = a.klang
        val alterVerlust = a.audio.beiVerlust!!
        val alterFehler = a.audio.beiFehler!!

        a.probe.start(WecktonAnstieg.AUS)
        runCurrent()
        assertEquals(1, alterKlang.freigegeben)

        // Rueckrufe des ersten Players/Fokus kommen verspaetet an - der neue Lauf bleibt.
        alterVerlust()
        alterFehler()
        runCurrent()

        val z = a.probe.zustand.value as ProbeZustand.Laeuft
        assertEquals(10_000L, z.gesamtMs)
        assertEquals(0, a.klang.freigegeben)
        a.probe.stop()
    }

    @Test
    fun `neuer Start nach einem Fehler loescht den Fehler`() = runTest {
        val a = Aufbau(this)
        a.audio.fokusErteilt = false
        a.probe.start(anstieg30s)
        runCurrent()
        assertFalse(a.probe.zustand.value is ProbeZustand.Laeuft)

        a.audio.fokusErteilt = true
        a.probe.start(anstieg30s)
        runCurrent()
        assertTrue(a.probe.zustand.value is ProbeZustand.Laeuft)
        a.probe.stop()
    }
}
