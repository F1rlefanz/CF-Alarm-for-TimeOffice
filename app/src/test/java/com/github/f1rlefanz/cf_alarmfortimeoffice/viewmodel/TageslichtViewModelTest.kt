package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.GespeicherterStandort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.IStandortAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzLand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzRegion
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzTabelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.StandortErgebnis
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Ort-Bildschirm fuer "nur solange es dunkel ist" (#140): was der Bildschirm zeigt, was
 * gespeichert wird - und dass JEDE wirksame Aenderung beide Zeitketten (Dimmer + DND) und die
 * Hue-Sonnenaufgangsplanung nachzieht. Ohne diesen Nachzug galte ein neuer Ort erst mit der
 * naechsten Wartung, bis zu 6 h spaeter.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class TageslichtViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- Fakes ----------------------------------------------------------------------------

    private class FakeRepo(start: TageslichtEinstellungen = TageslichtEinstellungen.LEER) : ITageslichtRepository {
        val stand = MutableStateFlow(start)
        val plzAufrufe = mutableListOf<Pair<PlzLand, String>>()
        val standortAufrufe = mutableListOf<Triple<Double, Double, Long>>()
        var entfernenAufrufe = 0
        var wirftBeimSchreiben = false

        override val einstellungen: Flow<TageslichtEinstellungen> = stand
        override suspend fun stand(): TageslichtStand = TageslichtStand.ohneOrt()

        override suspend fun setzePlz(land: PlzLand, plz: String) {
            if (wirftBeimSchreiben) error("Schreibfehler")
            plzAufrufe += land to plz
            stand.value = stand.value.copy(land = land, plz = plz.ifEmpty { null })
        }

        override suspend fun speichereStandort(breite: Double, laenge: Double, jetzt: Long) {
            if (wirftBeimSchreiben) error("Schreibfehler")
            standortAufrufe += Triple(breite, laenge, jetzt)
            stand.value = stand.value.copy(standort = GespeicherterStandort.gerundet(breite, laenge, jetzt))
        }

        override suspend fun entferneStandort() {
            entfernenAufrufe++
            stand.value = stand.value.copy(standort = null)
        }
    }

    private class FakeAbfrage(var ergebnis: StandortErgebnis) : IStandortAbfrage {
        var aufrufe = 0
        var sperre: CompletableDeferred<Unit>? = null
        override suspend fun einmalig(): StandortErgebnis {
            aufrufe++
            sperre?.await()
            return ergebnis
        }
    }

    private val tabellen = mapOf(
        PlzLand.DE to PlzTabelle(
            listOf(
                PlzRegion(PlzLand.DE, "80", 48.14, 11.58, "München"),
                PlzRegion(PlzLand.DE, "99", 50.98, 11.03, "Erfurt")
            )
        ),
        PlzLand.AT to PlzTabelle(listOf(PlzRegion(PlzLand.AT, "10", 48.21, 16.37, "Wien"))),
        PlzLand.CH to PlzTabelle.leer()
    )

    private val berlin = ZoneId.of("Europe/Berlin")

    /** 14.10.2026 12:00 Berlin - der Tag der Referenztabelle (Plan §3). */
    private val jetzt = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, berlin).toInstant().toEpochMilli()

    private inner class Aufbau(
        val repo: FakeRepo = FakeRepo(),
        val abfrage: FakeAbfrage = FakeAbfrage(StandortErgebnis.Zeitueberschreitung),
        region: String? = "DE",
        zone: ZoneId = berlin
    ) {
        val armierer = mock<ZeitkettenArmierer>()
        var hueNachzuege = 0
        val vm = TageslichtViewModel(
            repo = repo,
            abfrage = abfrage,
            armierer = armierer,
            hueNachzug = { hueNachzuege++ },
            plzTabelle = { land -> tabellen.getValue(land) },
            region = region,
            zone = { zone },
            jetzt = { jetzt }
        )

        /** `stateIn(WhileSubscribed)` rechnet nur mit Abnehmer - wie der Bildschirm einer ist. */
        fun TestScope.beobachte() {
            backgroundScope.launch { vm.zustand.collect {} }
        }

        val z: OrtBildschirmZustand get() = vm.zustand.value

        suspend fun erwarteNachzug(mal: Int) {
            verify(armierer, times(mal)).armiere(eq("TAGESLICHT"), any(), any())
            assertEquals("Hue-Sonnenaufgangsplanung nachgezogen", mal, hueNachzuege)
        }

        suspend fun erwarteKeinenNachzug() {
            verify(armierer, never()).armiere(any(), any(), any())
            assertEquals(0, hueNachzuege)
        }
    }

    // --- Land-Vorauswahl -----------------------------------------------------------------

    @Test
    fun `Land wird aus der Geraeteregion vorausgewaehlt, solange nichts gespeichert ist`() = runTest(dispatcher) {
        with(Aufbau(region = "AT")) {
            beobachte(); advanceUntilIdle()
            assertEquals(PlzLand.AT, z.land)
        }
    }

    @Test
    fun `fremde Region faellt auf Deutschland zurueck`() = runTest(dispatcher) {
        with(Aufbau(region = "US")) {
            beobachte(); advanceUntilIdle()
            assertEquals(PlzLand.DE, z.land)
        }
    }

    @Test
    fun `gespeichertes Land schlaegt die Geraeteregion`() = runTest(dispatcher) {
        with(Aufbau(repo = FakeRepo(TageslichtEinstellungen(null, PlzLand.AT, "10")), region = "DE")) {
            beobachte(); advanceUntilIdle()
            assertEquals(PlzLand.AT, z.land)
            assertEquals("10", z.plzEingabe)
            assertEquals("Wien", z.plzRegion)
            assertEquals(OrtQuelle.PLZ, z.genutzt)
        }
    }

    // --- Postleitzahl --------------------------------------------------------------------

    @Test
    fun `PLZ 80 zeigt die Region, speichert sie und zieht nach`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("80"); advanceUntilIdle()

            assertEquals("München", z.plzRegion)
            assertFalse(z.plzUnbekannt)
            assertEquals(OrtQuelle.PLZ, z.genutzt)
            assertEquals("München", z.genutztName)
            assertEquals(listOf(PlzLand.DE to "80"), repo.plzAufrufe)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `unbekannte PLZ wird markiert und NICHT gespeichert`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("12"); advanceUntilIdle()

            assertTrue(z.plzUnbekannt)
            assertNull(z.plzRegion)
            assertTrue(repo.plzAufrufe.isEmpty())
            erwarteKeinenNachzug()
            // Fail-safe: nichts gespeichert -> es gilt weiter die Zeitzone
            assertEquals(OrtQuelle.ZEITZONE, z.genutzt)
        }
    }

    @Test
    fun `eine einzelne Ziffer ist weder bekannt noch unbekannt`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("8"); advanceUntilIdle()

            assertFalse(z.plzUnbekannt)
            assertNull(z.plzRegion)
            assertTrue(repo.plzAufrufe.isEmpty())
            erwarteKeinenNachzug()
        }
    }

    @Test
    fun `Weitertippen innerhalb derselben Region speichert die Eingabe, armiert aber nur einmal`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("80"); advanceUntilIdle()
            vm.plzEingabe("803"); advanceUntilIdle()
            vm.plzEingabe("8033"); advanceUntilIdle()

            assertEquals("8033", repo.stand.value.plz)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `unbekannte Eingabe ueber einer gespeicherten PLZ laesst die gespeicherte gelten`() = runTest(dispatcher) {
        with(Aufbau(repo = FakeRepo(TageslichtEinstellungen(null, PlzLand.DE, "80")))) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("12"); advanceUntilIdle()

            assertTrue(z.plzUnbekannt)
            assertEquals("80", repo.stand.value.plz)
            // Die Aktuell-Karte nennt, was WIRKLICH gilt - nicht die Eingabe
            assertEquals(OrtQuelle.PLZ, z.genutzt)
            assertEquals("80", z.genutztPlz)
            assertEquals("München", z.genutztName)
            erwarteKeinenNachzug()
        }
    }

    @Test
    fun `leere Eingabe loescht die gespeicherte PLZ und zieht nach`() = runTest(dispatcher) {
        with(Aufbau(repo = FakeRepo(TageslichtEinstellungen(null, PlzLand.DE, "80")))) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe(""); advanceUntilIdle()

            assertEquals(listOf(PlzLand.DE to ""), repo.plzAufrufe)
            assertNull(repo.stand.value.plz)
            assertEquals(OrtQuelle.ZEITZONE, z.genutzt)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `Landwechsel speichert eine PLZ, die das neue Land kennt`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("10"); advanceUntilIdle()
            assertTrue("10 kennt die DE-Testtabelle nicht", z.plzUnbekannt)

            vm.landWahl(PlzLand.AT); advanceUntilIdle()
            assertEquals(PlzLand.AT, z.land)
            assertEquals("Wien", z.plzRegion)
            assertEquals(listOf(PlzLand.AT to "10"), repo.plzAufrufe)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `Schreibfehler beim Speichern der PLZ reisst nichts mit und armiert nicht`() = runTest(dispatcher) {
        with(Aufbau()) {
            repo.wirftBeimSchreiben = true
            beobachte(); advanceUntilIdle()
            vm.plzEingabe("80"); advanceUntilIdle()

            assertEquals("Eingabe bleibt sichtbar", "München", z.plzRegion)
            erwarteKeinenNachzug()
        }
    }

    // --- Standort ------------------------------------------------------------------------

    @Test
    fun `Standort uebernehmen speichert nur gerundete Werte und zieht nach`() = runTest(dispatcher) {
        with(Aufbau(abfrage = FakeAbfrage(StandortErgebnis.Gefunden(48.137, 11.575)))) {
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()

            val (b, l, t) = repo.standortAufrufe.single()
            assertEquals(48.1, b, 1e-9)
            assertEquals(11.6, l, 1e-9)
            assertEquals(jetzt, t)
            assertEquals(OrtQuelle.STANDORT, z.genutzt)
            assertEquals("14.10.2026", z.standortUebernommenAm)
            assertFalse(z.standortLaeuft)
            assertNull(z.standortFehler)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `waehrend der Abfrage laeuft die Anzeige, ein zweiter Druck fragt nicht erneut`() = runTest(dispatcher) {
        val abfrage = FakeAbfrage(StandortErgebnis.Gefunden(48.137, 11.575)).apply { sperre = CompletableDeferred() }
        with(Aufbau(abfrage = abfrage)) {
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()
            assertTrue(z.standortLaeuft)

            vm.standortUebernehmen(); advanceUntilIdle()
            assertEquals(1, abfrage.aufrufe)

            abfrage.sperre!!.complete(Unit); advanceUntilIdle()
            assertFalse(z.standortLaeuft)
        }
    }

    @Test
    fun `Zeitueberschreitung zeigt einen Fehler und speichert nichts`() = runTest(dispatcher) {
        with(Aufbau(abfrage = FakeAbfrage(StandortErgebnis.Zeitueberschreitung))) {
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()

            assertEquals(StandortFehler.FEHLGESCHLAGEN, z.standortFehler)
            assertFalse(z.standortLaeuft)
            assertTrue(repo.standortAufrufe.isEmpty())
            erwarteKeinenNachzug()
        }
    }

    @Test
    fun `abgeschaltete Standortermittlung und fehlende Berechtigung haben eigene Fehler`() = runTest(dispatcher) {
        with(Aufbau(abfrage = FakeAbfrage(StandortErgebnis.Abgeschaltet))) {
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()
            assertEquals(StandortFehler.ABGESCHALTET, z.standortFehler)

            abfrage.ergebnis = StandortErgebnis.KeineBerechtigung
            vm.standortUebernehmen(); advanceUntilIdle()
            assertEquals(StandortFehler.ABGELEHNT, z.standortFehler)
            assertTrue(repo.standortAufrufe.isEmpty())
            erwarteKeinenNachzug()
        }
    }

    @Test
    fun `abgelehnter Systemdialog zeigt den PLZ-Hinweis`() = runTest(dispatcher) {
        with(Aufbau()) {
            beobachte(); advanceUntilIdle()
            vm.standortAbgelehnt(); advanceUntilIdle()
            assertEquals(StandortFehler.ABGELEHNT, z.standortFehler)
            assertEquals(0, abfrage.aufrufe)
        }
    }

    @Test
    fun `neuer Besuch verwirft alten Fehler und ungespeicherte Eingabe`() = runTest(dispatcher) {
        with(Aufbau(repo = FakeRepo(TageslichtEinstellungen(null, PlzLand.DE, "80")))) {
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()
            vm.plzEingabe("12"); advanceUntilIdle()
            assertEquals(StandortFehler.FEHLGESCHLAGEN, z.standortFehler)
            assertTrue(z.plzUnbekannt)

            vm.beimOeffnen(); advanceUntilIdle()
            assertNull(z.standortFehler)
            assertFalse(z.plzUnbekannt)
            assertEquals("gespeicherte PLZ wieder im Feld", "80", z.plzEingabe)
        }
    }

    @Test
    fun `Schreibfehler beim Standort wird als Fehlschlag gezeigt`() = runTest(dispatcher) {
        with(Aufbau(abfrage = FakeAbfrage(StandortErgebnis.Gefunden(48.137, 11.575)))) {
            repo.wirftBeimSchreiben = true
            beobachte(); advanceUntilIdle()
            vm.standortUebernehmen(); advanceUntilIdle()

            assertEquals(StandortFehler.FEHLGESCHLAGEN, z.standortFehler)
            erwarteKeinenNachzug()
        }
    }

    @Test
    fun `Standort entfernen faellt auf die PLZ zurueck und zieht nach`() = runTest(dispatcher) {
        val start = TageslichtEinstellungen(GespeicherterStandort(481, 116, jetzt), PlzLand.DE, "80")
        with(Aufbau(repo = FakeRepo(start))) {
            beobachte(); advanceUntilIdle()
            assertEquals(OrtQuelle.STANDORT, z.genutzt)

            vm.standortEntfernen(); advanceUntilIdle()
            assertEquals(1, repo.entfernenAufrufe)
            assertEquals(OrtQuelle.PLZ, z.genutzt)
            assertNull(z.standortUebernommenAm)
            erwarteNachzug(1)
        }
    }

    @Test
    fun `Standort entfernen ohne PLZ faellt auf die Zeitzone zurueck`() = runTest(dispatcher) {
        val start = TageslichtEinstellungen(GespeicherterStandort(481, 116, jetzt), null, null)
        with(Aufbau(repo = FakeRepo(start))) {
            beobachte(); advanceUntilIdle()
            vm.standortEntfernen(); advanceUntilIdle()

            assertEquals(OrtQuelle.ZEITZONE, z.genutzt)
            assertEquals("Erfurt", z.zeitzoneOrt)
            assertEquals("Europe/Berlin", z.zeitzone)
            erwarteNachzug(1)
        }
    }

    // --- Anzeige ohne Ort / Sonnenzeiten -------------------------------------------------

    @Test
    fun `fremde Zeitzone ohne Eingaben heisst kein Ort`() = runTest(dispatcher) {
        with(Aufbau(zone = ZoneId.of("America/Sao_Paulo"))) {
            beobachte(); advanceUntilIdle()
            assertNull(z.genutzt)
            assertNull(z.zeitzoneOrt)
            assertNull(z.daemmerungHeute)
            assertNull(z.sonnenaufgangHeute)
        }
    }

    @Test
    fun `Sonnenzeiten von heute fuer Muenchen passen zur Referenztabelle`() = runTest(dispatcher) {
        with(Aufbau(repo = FakeRepo(TageslichtEinstellungen(null, PlzLand.DE, "80")))) {
            beobachte(); advanceUntilIdle()
            // Plan §3: Muenchen 14.10.2026 - Daemmerung 07:00, Sonnenaufgang 07:31 (±2 min)
            assertUngefaehr("07:00", z.daemmerungHeute)
            assertUngefaehr("07:31", z.sonnenaufgangHeute)
        }
    }

    private fun assertUngefaehr(erwartet: String, ist: String?) {
        assertNotNull(ist)
        val minuten = ChronoUnit.MINUTES.between(LocalTime.parse(erwartet), LocalTime.parse(ist))
        assertTrue("$ist liegt mehr als 2 min neben $erwartet", abs(minuten) <= 2)
    }
}
