package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimCorrectionNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * „Nicht stoeren“ liest die Schalter AM TYP (#218, SPEC E7): „Solange gedimmt wird“, „Waehrend des
 * Kalendereintrags“ und bei Rufbereitschaft „Endet spaetestens um“.
 *
 * In jedem Fall stehen die ALTEN globalen Schalter (`dnd_follow_dimmer_enabled`,
 * `dnd_during_shift_enabled`, leere Ausnahmenliste, `dnd_oncall_cutoff_min` = 05:00) auf AN. Sie
 * duerfen nichts mehr bewirken - genau daran waere der Stand vor #218 rot: er schaltete jede Nacht
 * und jede Schicht, kappte jede Rufbereitschaft an derselben Uhrzeit und plante bei einem
 * unlesbaren Typ-Bestand den Keep-alive statt des Retry.
 *
 * Der erste Fall rechnet mit dem ECHTEN Dimmer (keine Attrappe): der Filter nach Tagen sitzt im
 * Dimmer vor dem Vereinen, die Entscheidung je Tag in DND - nur zusammen zeigt sich, ob sie passen.
 * Der Dimmer rechnet ab heute in der Systemzeitzone; geprueft wird deshalb auf Kalendertage.
 */
class DndTypSchalterTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val heute: LocalDate = LocalDate.now(zone)

    private fun um(tag: LocalDate, stunde: Int): Long =
        tag.atTime(LocalTime.of(stunde, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun tagVon(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Die Nacht des Kalendertags: 22:00 bis 06:00 am Folgetag. */
    private val nacht = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = 6 * 60
    )

    private fun mitNacht(): TypDimmen = TypDimmen().mitFenster(listOf(TypDimmFenster(nacht, staerke = 50)))

    private fun typ(
        name: String,
        nichtStoeren: TypNichtStoeren,
        dimmen: TypDimmen = TypDimmen(),
        ruf: Boolean = false
    ) = ShiftDefinition(
        id = name, name = name, keywords = listOf(name), alarmTime = LocalTime.of(6, 0),
        isOnCall = ruf, dimmen = dimmen, nichtStoeren = nichtStoeren
    )

    /** Eine Schicht am Kalendertag [tag]: Wecker 06:00, Termin 08:00-16:00. */
    private fun dienst(name: String, tag: LocalDate) =
        ShiftSpan(shiftName = name, startTime = um(tag, 8), endTime = um(tag, 16), alarmTriggerTime = um(tag, 6))

    /** Die alten globalen Schalter - alle AN. Seit #218 liest sie der Scheduler nicht mehr. */
    private fun alteGlobaleSchalterAn(): DndPrefs = mock<DndPrefs>().also { prefs ->
        runBlocking {
            whenever(prefs.togglesNow()).thenReturn(DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true))
            whenever(prefs.shiftExcludedShiftsNow()).thenReturn(emptySet())
            whenever(prefs.onCallCutoffMinutesNow()).thenReturn(DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN)
        }
    }

    private fun konfigRepo(konfig: Result<ShiftConfig>): IShiftConfigRepository = mock<IShiftConfigRepository>().also {
        runBlocking { whenever(it.getCurrentShiftConfig()).thenReturn(konfig) }
    }

    private fun spanStore(spans: List<ShiftSpan>): ShiftSpanStore = mock<ShiftSpanStore>().also {
        runBlocking { whenever(it.spansNow()).thenReturn(Result.success(spans)) }
    }

    /** Der echte Dimmer auf demselben Typ-Bestand und denselben Spannen. */
    private fun echterDimmer(repo: IShiftConfigRepository, store: ShiftSpanStore): DimScheduleUseCase {
        val prefs = mock<DimOverlayPrefs>()
        val alarme = mock<IAlarmUseCase>()
        val pause = mock<MasterPausePrefs>()
        runBlocking {
            whenever(prefs.warmthNow()).thenReturn(40)
            whenever(alarme.getAllAlarms()).thenReturn(Result.success(emptyList<AlarmInfo>()))
            whenever(pause.pausedNow()).thenReturn(false)
        }
        return DimScheduleUseCase(
            mock<Context>(), alarme, store, keineFreienTage(), repo, prefs, mock<DimCorrectionNotifier>(), pause,
            TageslichtQuelle.OHNE
        )
    }

    private fun dnd(repo: IShiftConfigRepository, store: ShiftSpanStore, dimmer: DimScheduleUseCase) =
        DndScheduleUseCase(
            mock<Context>(), store, keineFreienTage(), dimmer, alteGlobaleSchalterAn(), mock<MasterPausePrefs>(), repo
        )

    @Test
    fun `Typ A mit Solange gedimmt wird, Typ B ohne - Nicht stoeren nur in den A-Naechten`() = runTest {
        val tagA = heute.plusDays(1)
        val tagB = heute.plusDays(2)
        val repo = konfigRepo(
            Result.success(
                ShiftConfig(
                    definitions = listOf(
                        typ("A", TypNichtStoeren(solangeGedimmt = true), dimmen = mitNacht()),
                        typ("B", TypNichtStoeren(solangeGedimmt = false), dimmen = mitNacht())
                    )
                )
            )
        )
        val store = spanStore(listOf(dienst("A", tagA), dienst("B", tagB)))
        val dimmer = echterDimmer(repo, store)

        // Gegenprobe: gedimmt wird in BEIDEN Naechten - B ist also nicht etwa ohne Fenster.
        val gedimmt = dimmer.previewTimeline().map { tagVon(it.range.first) }.toSet()
        assertEquals(setOf(tagA, tagB), gedimmt)

        val fenster = dnd(repo, store, dimmer).fensterJetzt()

        assertEquals(
            "Nur die Nacht des Typs mit „Solange gedimmt wird“ - die B-Nacht ist gedimmt, aber ohne Ruhezeit",
            listOf(um(tagA, 22)..um(tagA.plusDays(1), 6)),
            fenster.map { it.range }
        )
        assertTrue(fenster.all { it.quelle == DndQuelle.FOLGT_DIMMER })
    }

    @Test
    fun `Die feste Zeile folgt ihrem eigenen Schalter, bei wie Typ dem Schalter des Typs`() = runTest {
        // Kein Dienst: jeder Tag ist ein Tag ohne Kalendereintrag, die feste Zeile dimmt jede Nacht.
        // B hat den Schalter an, kommt aber nie vor - so wird die Dimm-Zeitleiste in beiden Faellen
        // gerechnet, und nur der Filter je Tag entscheidet.
        val eigeneAn = ShiftConfig(
            definitions = listOf(
                typ("A", TypNichtStoeren(solangeGedimmt = false), dimmen = mitNacht()),
                typ("B", TypNichtStoeren(solangeGedimmt = true), dimmen = mitNacht())
            ),
            ohneKalendereintrag = OhneEintragEinstellungen(dimmen = mitNacht(), nichtStoerenSolangeGedimmt = true)
        )
        val store = spanStore(emptyList())
        val repoEigene = konfigRepo(Result.success(eigeneAn))
        assertTrue(
            "Eigene Einstellungen mit dem Schalter: Ruhezeit in den Naechten ohne Eintrag",
            dnd(repoEigene, store, echterDimmer(repoEigene, store)).fensterJetzt().isNotEmpty()
        )

        // „wie A“: die Fenster UND der Schalter kommen von A, der eigene Schalter wirkt nicht.
        val wieA = eigeneAn.copy(ohneKalendereintragWie = "A")
        val repoWie = konfigRepo(Result.success(wieA))
        val dimmerWie = echterDimmer(repoWie, store)
        assertTrue("A hat Dimm-Fenster, die Naechte sind gedimmt", dimmerWie.previewTimeline().isNotEmpty())
        assertEquals(emptyList<DndFenster>(), dnd(repoWie, store, dimmerWie).fensterJetzt())
    }

    @Test
    fun `Waehrend des Kalendereintrags nur fuer Typen mit dem Schalter`() = runTest {
        val tag = heute.plusDays(1)
        val repo = konfigRepo(
            Result.success(
                ShiftConfig(
                    definitions = listOf(
                        typ("A", TypNichtStoeren(waehrendEintrag = true)),
                        typ("B", TypNichtStoeren(waehrendEintrag = false))
                    )
                )
            )
        )
        val a = dienst("A", tag)
        val b = dienst("B", tag.plusDays(1))
        val dimmer = mock<DimScheduleUseCase>()

        val fenster = dnd(repo, spanStore(listOf(a, b)), dimmer).fensterJetzt()

        assertEquals(listOf(a.startTime..a.endTime), fenster.map { it.range })
        assertTrue(fenster.all { it.quelle == DndQuelle.DIENSTZEIT })
        // Kein Typ hat „Solange gedimmt wird“: die Dimm-Zeitleiste wird gar nicht erst gerechnet.
        verifyNoInteractions(dimmer)
    }

    @Test
    fun `Ein Ruf-Typ endet an seinem eigenen Endet spaetestens um`() = runTest {
        // Zwei Rufbereitschaften mit verschiedenen Uhrzeiten; der alte globale Cutoff stuende fuer
        // beide auf 05:00.
        val r1Tag = heute.plusDays(1)
        val r2Tag = heute.plusDays(3)
        val repo = konfigRepo(
            Result.success(
                ShiftConfig(
                    definitions = listOf(
                        typ("R1", TypNichtStoeren(waehrendEintrag = true, endetSpaetestensGespeichert = 5 * 60), ruf = true),
                        typ("R2", TypNichtStoeren(waehrendEintrag = true, endetSpaetestensGespeichert = 3 * 60), ruf = true)
                    )
                )
            )
        )
        // Abends beginnend bis zum Mittag des Folgetags - der Cutoff rollt auf den Folgetag.
        val r1 = ShiftSpan("R1", um(r1Tag, 18), um(r1Tag.plusDays(1), 12), um(r1Tag, 17))
        val r2 = ShiftSpan("R2", um(r2Tag, 18), um(r2Tag.plusDays(1), 12), um(r2Tag, 17))

        val fenster = dnd(repo, spanStore(listOf(r1, r2)), mock()).fensterJetzt()

        assertEquals(
            listOf(r1.startTime..um(r1Tag.plusDays(1), 5), r2.startTime..um(r2Tag.plusDays(1), 3)),
            fenster.map { it.range }
        )
        assertTrue("Die Diagnose nennt den Cutoff", fenster.all { it.geklippt })
    }

    @Test
    fun `Alle Schalter aus - die Kette bestellt sich ab, ohne Spannen oder Dimmer zu lesen`() = runTest {
        // Dimm-Fenster und eine Rufbereitschaft gibt es - aber keinen einzigen DND-Schalter. Der
        // Ruf-Cutoff allein loest nichts aus.
        val repo = konfigRepo(
            Result.success(
                ShiftConfig(
                    definitions = listOf(
                        typ("A", TypNichtStoeren(), dimmen = mitNacht()),
                        typ("R", TypNichtStoeren(), ruf = true)
                    ),
                    ohneKalendereintrag = OhneEintragEinstellungen(dimmen = mitNacht(), nichtStoerenSolangeGedimmt = false)
                )
            )
        )
        val store = mock<ShiftSpanStore>()
        val dimmer = mock<DimScheduleUseCase>()
        val now = System.currentTimeMillis()

        assertNull(dnd(repo, store, dimmer).computeNextTransition(now))
        verifyNoInteractions(store, dimmer)
    }

    @Test
    fun `Lesefehler des Typ-Bestands - Retry-Tick statt Keep-alive`() = runTest {
        val repo = konfigRepo(Result.failure(IllegalStateException("vor der ersten Entsperrung")))
        val store = mock<ShiftSpanStore>()
        val dimmer = mock<DimScheduleUseCase>()
        val now = System.currentTimeMillis()

        val sut = dnd(repo, store, dimmer)

        assertEquals(now + 15 * 60_000L, sut.computeNextTransition(now))
        assertEquals("Ohne lesbare Schalter keine Ruhezeit", emptyList<DndFenster>(), sut.fensterJetzt())
        // Vor dem Lesen der Schalter wird kein weiterer Speicher angefasst.
        verifyNoInteractions(store, dimmer)
    }
}
