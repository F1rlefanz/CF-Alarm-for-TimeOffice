package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KrankmeldungNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.freieTageStoreMit
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Umbau E5 (#175): Zeigt „Ohne Kalendereintrag“ auf einen Typ mit Wecker, stellt `syncAlarms()` an
 * den leeren Tagen (`tageOhneEintrag`, #173) dessen Wecker - mit der stabilen Kennung
 * `ohne-eintrag:<Datum>`, ohne Schichtbeginn, mit Name und Stille des Verweistyps. Ein solcher
 * Wecker ist KEINE Schicht: er meldet weder „neue Schicht“ noch „Schicht entfernt“ noch „Schicht
 * geaendert“, und er geht - erst Cancel, dann Delete -, sobald der Tag einen Eintrag bekommt oder
 * der Verweis wegfaellt.
 *
 * Gegen die ECHTE [ShiftRecognitionEngine] und den ECHTEN [ShiftSpanStore]; gemockt sind nur der
 * Android-gebundene [AlarmManagerService] und die Freigaben (Hand-Freigaben aus
 * [freieTageStoreMit]). Die Engine liest ihre eigene Konfiguration, deshalb bekommt jede
 * Konfiguration einen eigenen UseCase ueber DENSELBEN Bestand.
 */
class OhneEintragSyncTest {

    // --- Fakes ---

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
    }

    /** [protokoll] haelt die Reihenfolge fest - fuer „erst cancelSystemAlarm, dann deleteAlarm“. */
    private class FakeAlarmRepository(
        initial: List<AlarmInfo>,
        private val protokoll: MutableList<String>
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            protokoll += "save:${alarmInfo.id}"
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            protokoll += "delete:$alarmId"
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            protokoll += "deleteAll"
            state.value = emptyList()
            return Result.success(Unit)
        }
    }

    private class FakeSkipUseCase : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())

        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    /** Haelt fest, WELCHER Wecker gemeldet wurde - echte Schichten duerfen sich weiter melden. */
    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        val erstellt = mutableListOf<AlarmInfo>()
        val geaendert = mutableListOf<AlarmInfo>()
        val entfernt = mutableListOf<AlarmInfo>()

        override suspend fun notifyCreated(new: AlarmInfo) {
            erstellt += new
        }

        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) {
            geaendert += new
        }

        override suspend fun notifyDeleted(old: AlarmInfo) {
            entfernt += old
        }

        fun ohneEintragGemeldet(): List<AlarmInfo> =
            (erstellt + geaendert + entfernt).filter { it.eventId.startsWith("ohne-eintrag:") }
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    // --- Fixtures ---

    private val frueh = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Der Verweistyp: ein stiller Frei-Typ mit Wecker 07:30 (ein Lichtwecker). */
    private val frei = ShiftDefinition(
        id = "free",
        name = "Frei",
        keywords = listOf("FR"),
        alarmTime = LocalTime.of(7, 30),
        isSilent = true
    ).mitKategorie(ShiftKategorie.FREI)

    private val krank = ShiftDefinition(
        id = "sick",
        name = "Krank",
        keywords = listOf("K"),
        alarmTime = LocalTime.MIDNIGHT,
        isSickDay = true
    )

    private val ignoriert = ShiftDefinition(
        id = "tb",
        name = "Teambesprechung",
        keywords = listOf("TB"),
        alarmTime = LocalTime.of(9, 0)
    ).mitKategorie(ShiftKategorie.IGNORIEREN)

    private fun konfig(verweis: String? = frei.id, freiWeckzeit: LocalTime = frei.alarmTime) = ShiftConfig(
        autoAlarmEnabled = true,
        definitions = listOf(frueh, frei.copy(alarmTime = freiWeckzeit), krank, ignoriert),
        ohneKalendereintragWie = verweis
    )

    private fun tag(d: Int): LocalDate = LocalDate.of(2035, 6, d)

    /** Uhr der Tests: 01.06.2035 06:00 - der Abruf reicht bis vor den 06.06. (Tage 1 bis 5). */
    private val jetzt = tag(1).atTime(6, 0)
    private val horizont = millis(tag(6).atStartOfDay())

    private fun termin(id: String, titel: String, d: Int, von: Int = 6, bis: Int = 14) = CalendarEvent(
        id = id,
        title = titel,
        startTime = tag(d).atTime(von, 0),
        endTime = tag(d).atTime(bis, 0),
        calendarId = "test"
    )

    private fun ganztags(id: String, titel: String, d: Int) = CalendarEvent(
        id = id,
        title = titel,
        startTime = tag(d).atStartOfDay(),
        endTime = tag(d).atTime(23, 59),
        calendarId = "test",
        isAllDay = true
    )

    private fun millis(zeit: LocalDateTime): Long = zeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun kennungsId(d: Int): Int = "ohne-eintrag:${tag(d)}".hashCode()

    private fun mockManager(protokoll: MutableList<String>): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.setAlarmFromShiftMatch(any(), any(), any())).thenAnswer { aufruf ->
            protokoll += "arm:${aufruf.arguments[2]}"
            status
        }
        whenever(m.cancelSystemAlarm(any())).thenAnswer { aufruf ->
            protokoll += "cancel:${aufruf.arguments[0]}"
            status
        }
        return m
    }

    private class Aufbau(
        val repo: FakeAlarmRepository,
        val protokoll: MutableList<String>,
        val notifier: FakeShiftChangeNotifier,
        val spanStore: ShiftSpanStore,
        val manager: AlarmManagerService
    )

    private fun aufbau(bestand: List<AlarmInfo> = emptyList()): Aufbau {
        val protokoll = mutableListOf<String>()
        return Aufbau(
            FakeAlarmRepository(bestand, protokoll),
            protokoll,
            FakeShiftChangeNotifier(),
            ShiftSpanStore(FakePreferencesDataStore()),
            mockManager(protokoll)
        )
    }

    private fun useCase(
        a: Aufbau,
        config: ShiftConfig,
        handFrei: Set<LocalDate> = emptySet(),
        uhr: LocalDateTime = jetzt
    ): AlarmUseCase = AlarmUseCase(
        a.repo,
        a.manager,
        FakeShiftConfigRepository(config),
        ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
        FakeSkipUseCase(),
        a.notifier,
        mock<MasterPausePrefs>().also {
            kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
        },
        a.spanStore,
        FakeSyncHorizonStore(),
        FakeFeedNeueinlesenStore(),
        freieTageStoreMit(handFrei),
        FakeKalenderVorausschauPrefs(FakeKalenderVorausschauPrefs.UEBER_ALLE_TESTDATEN_TAGE),
        dagger.Lazy { mock<ZeitkettenArmierer>() },
        mock<KrankmeldungNotifier>()
    ).also { it.uhr = { uhr } }

    /** Ein manueller Wecker im Bestand: er muss jeden Lauf unberuehrt ueberstehen - und macht den Lauf zum „nicht ersten“. */
    private val manuell = AlarmInfo(
        id = 777,
        shiftId = frueh.id,
        shiftName = frueh.name + MANUELLER_ALARM_SUFFIX,
        triggerTime = millis(tag(4).atTime(9, 15)),
        formattedTime = "manuell"
    )

    private fun ohneEintrag(a: Aufbau): List<AlarmInfo> =
        a.repo.current.filter { it.eventId.startsWith("ohne-eintrag:") }.sortedBy { it.triggerTime }

    /** Mo F, Di Krank, Mi freigegeben (Hand), Do und Fr leer - Tage 2 bis 5, dazu heute (Tag 1) leer. */
    private val woche = listOf(termin("f-2", "F", 2), ganztags("k-3", "K", 3))
    private val handFrei = setOf(tag(4))

    // --- Tests ---

    @Test
    fun `Verweis auf stillen Frei-Typ - Wecker nur an leeren Tagen, nicht an Eintrag, Krank oder Freigabe`() = runTest {
        val a = aufbau(listOf(manuell))

        val ergebnis = useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont)

        assertTrue("Sync muss gelingen: $ergebnis", ergebnis.isSuccess)
        val wecker = ohneEintrag(a)
        // Tag 1 (heute, 07:30 nach jetzt 06:00) und Tag 5 sind leer. Tag 2 hat F, Tag 3 Krank,
        // Tag 4 ist freigegeben; Tag 6 ist der Horizont-Tag selbst und nicht gelesen.
        assertEquals(listOf(tag(1), tag(5)).map { millis(it.atTime(7, 30)) }, wecker.map { it.triggerTime })
        assertEquals(listOf("ohne-eintrag:${tag(1)}", "ohne-eintrag:${tag(5)}"), wecker.map { it.eventId })
        assertEquals(listOf(kennungsId(1), kennungsId(5)), wecker.map { it.id })
        wecker.forEach {
            // Name und Stille des Verweistyps - die Stille-Pruefung im AlarmReceiver sucht ueber den Namen.
            assertEquals("Frei", it.shiftName)
            assertEquals(frei.id, it.shiftId)
            assertTrue("Der Verweistyp ist still", it.isSilent)
            // Kein Termin, kein Schichtbeginn.
            assertEquals(0L, it.shiftStartTime)
            assertEquals(0L, it.shiftEndTime)
        }
        assertTrue("Die Systemwecker sind gestellt: ${a.protokoll}",
            "arm:${kennungsId(1)}" in a.protokoll && "arm:${kennungsId(5)}" in a.protokoll)
        // Die echte Schicht stellt ihren Wecker wie immer, der manuelle bleibt.
        assertTrue(a.repo.current.any { it.eventId == "f-2" })
        assertTrue(manuell in a.repo.current)
        // Keine Meldung fuer die Ohne-Eintrag-Wecker - obwohl es nicht der erste Lauf ist.
        assertEquals(emptyList<AlarmInfo>(), a.notifier.ohneEintragGemeldet())
        // Keine Schichtspanne fuer leere Tage: nur F steht in den Spannen.
        assertEquals(listOf("Frueh"), a.spanStore.spansNow().getOrThrow().map { it.shiftName })
    }

    @Test
    fun `Termin desselben Typs kommt hinzu - Wecker gecancelt und geloescht, kein Entfernt, die neue Schicht meldet sich`() = runTest {
        val a = aufbau(listOf(manuell))
        useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont).getOrThrow()
        assertTrue(ohneEintrag(a).any { it.id == kennungsId(5) })

        // Am Tag 5 traegt TimeOffice jetzt ausdruecklich „FR“ ein: derselbe Typ, dieselbe Weckzeit
        // 07:30 - genau der Fall, in dem eine Paarung ueber Weckzeit + Schicht still uebernaehme.
        a.protokoll.clear()
        val mitEintrag = woche + ganztags("fr-5", "FR", 5)
        useCase(a, konfig(), handFrei).syncAlarms(mitEintrag, konfig(), horizont).getOrThrow()

        val cancel = a.protokoll.indexOf("cancel:${kennungsId(5)}")
        val delete = a.protokoll.indexOf("delete:${kennungsId(5)}")
        assertTrue("Cancel UND Delete erwartet: ${a.protokoll}", cancel >= 0 && delete >= 0)
        assertTrue("Erst cancelSystemAlarm, dann deleteAlarm: ${a.protokoll}", cancel < delete)
        assertFalse(a.repo.current.any { it.id == kennungsId(5) })
        assertEquals("Kein „Schicht entfernt“ fuer den Ohne-Eintrag-Wecker", emptyList<AlarmInfo>(), a.notifier.entfernt)
        // Der echte Termin bekommt seinen eigenen Wecker - und meldet sich als neue Schicht.
        val echt = a.repo.current.single { it.eventId == "fr-5" }
        assertEquals(millis(tag(5).atTime(7, 30)), echt.triggerTime)
        assertTrue("fr-5" in a.notifier.erstellt.map { it.eventId })
        // Tag 1 ist weiter leer, sein Wecker bleibt.
        assertEquals(listOf(kennungsId(1)), ohneEintrag(a).map { it.id })
    }

    @Test
    fun `Leere Eventliste - kein Ohne-Eintrag-Wecker, auch kein vorher gestellter`() = runTest {
        val a = aufbau(listOf(manuell))
        useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont).getOrThrow()
        assertEquals(2, ohneEintrag(a).size)

        // Die Kalender-Abwahl ruft syncAlarms() absichtlich mit leerer Liste - „jeder Tag ist leer“
        // waere die falsche Lesart.
        useCase(a, konfig(), handFrei).syncAlarms(emptyList(), konfig(), horizont).getOrThrow()

        assertEquals(emptyList<AlarmInfo>(), ohneEintrag(a))
        assertEquals(listOf(manuell), a.repo.current)
        assertEquals(emptyList<AlarmInfo>(), a.notifier.ohneEintragGemeldet())
    }

    @Test
    fun `Verweis entfernt - alle Ohne-Eintrag-Wecker abgeraeumt, erst Cancel, dann Delete, ohne Meldung`() = runTest {
        val a = aufbau(listOf(manuell))
        useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont).getOrThrow()
        assertEquals(2, ohneEintrag(a).size)

        a.protokoll.clear()
        useCase(a, konfig(verweis = null), handFrei).syncAlarms(woche, konfig(verweis = null), horizont).getOrThrow()

        assertEquals(emptyList<AlarmInfo>(), ohneEintrag(a))
        for (d in listOf(1, 5)) {
            val cancel = a.protokoll.indexOf("cancel:${kennungsId(d)}")
            val delete = a.protokoll.indexOf("delete:${kennungsId(d)}")
            assertTrue("Tag $d: erst Cancel, dann Delete: ${a.protokoll}", cancel in 0 until delete)
        }
        assertEquals(emptyList<AlarmInfo>(), a.notifier.ohneEintragGemeldet())
        // Die echte Schicht und der manuelle Wecker bleiben unberuehrt.
        assertTrue(a.repo.current.any { it.eventId == "f-2" })
        assertTrue(manuell in a.repo.current)
    }

    @Test
    fun `Nur ignorierte und unbekannte Termine - der Zweig ohne Schichttreffer uebergeht die leeren Tage nicht`() = runTest {
        // Kein einziger Schichttreffer: TB ist ignoriert, „XYZ“ noch nicht eingeordnet. Frueher
        // raeumte dieser Zweig alle Kalender-Wecker und ging - hier muss er die leeren Tage stellen.
        val a = aufbau(listOf(manuell))
        val events = listOf(termin("tb-2", "TB", 2, von = 9, bis = 10), termin("x-3", "XYZ", 3))

        useCase(a, konfig()).syncAlarms(events, konfig(), horizont).getOrThrow()

        // Tag 2 zaehlt als leer (nur Ignoriertes), Tag 3 nicht (ein unbekannter Code ist ein Eintrag).
        assertEquals(listOf(1, 2, 4, 5).map { kennungsId(it) }, ohneEintrag(a).map { it.id })
        assertEquals("Keine Spanne fuer leere Tage", emptyList<ShiftSpan>(), a.spanStore.spansNow().getOrThrow())

        // ... und faellt der Verweis weg, raeumt derselbe Zweig sie wieder ab.
        a.protokoll.clear()
        useCase(a, konfig(verweis = null)).syncAlarms(events, konfig(verweis = null), horizont).getOrThrow()
        assertEquals(listOf(manuell), a.repo.current)
        assertTrue("Erst Cancel, dann Delete: ${a.protokoll}",
            a.protokoll.indexOf("cancel:${kennungsId(2)}") in 0 until a.protokoll.indexOf("delete:${kennungsId(2)}"))
    }

    @Test
    fun `Heute nach beendetem Nachtdienst - heute kein Wecker, die uebrigen leeren Tage schon`() = runTest {
        // 03.06. 08:00, der Nachtdienst 02.06. 21:00 bis 03.06. 07:00 ist vorbei und steht deshalb
        // nicht mehr in der Liste (der Abruf beginnt bei jetzt). Der Verweistyp weckt um 11:00 -
        // ohne Gedaechtnis klingelte er nach dem Nachtdienst.
        val uhr = tag(3).atTime(8, 0)
        val events = listOf(termin("f-5", "F", 5))
        val config = konfig(freiWeckzeit = LocalTime.of(11, 0))

        val ohneSpanne = aufbau()
        useCase(ohneSpanne, config, uhr = uhr).syncAlarms(events, config, horizont).getOrThrow()
        assertEquals("Gegenprobe: ohne Spanne ist heute leer", listOf(kennungsId(3), kennungsId(4)),
            ohneEintrag(ohneSpanne).map { it.id })

        val mitSpanne = aufbau()
        mitSpanne.spanStore.replaceAll(
            listOf(
                ShiftSpan(
                    shiftName = "Nacht",
                    startTime = millis(tag(2).atTime(21, 0)),
                    endTime = millis(tag(3).atTime(7, 0)),
                    alarmTriggerTime = millis(tag(2).atTime(19, 0))
                )
            ),
            now = millis(uhr)
        )
        useCase(mitSpanne, config, uhr = uhr).syncAlarms(events, config, horizont).getOrThrow()
        assertEquals(listOf(kennungsId(4)), ohneEintrag(mitSpanne).map { it.id })
    }

    @Test
    fun `Weckzeit des Verweistyps aendert sich - id bleibt, ohne Meldung, unveraendert wird nur neu armiert`() = runTest {
        val a = aufbau(listOf(manuell))
        useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont).getOrThrow()

        // Unveraendert: kein Abbrechen, kein Neuanlegen - nur das idempotente Re-Armen.
        a.protokoll.clear()
        useCase(a, konfig(), handFrei).syncAlarms(woche, konfig(), horizont).getOrThrow()
        assertTrue("Kein Cancel/Delete/Save im unveraenderten Lauf: ${a.protokoll}",
            a.protokoll.none { it.startsWith("cancel:") || it.startsWith("delete:") || it.startsWith("save:") })
        assertTrue("arm:${kennungsId(5)}" in a.protokoll)

        // Weckzeit 07:30 -> 08:15: derselbe Wecker (dieselbe id), neu gestellt, ohne „Schicht geaendert“.
        val neu = konfig(freiWeckzeit = LocalTime.of(8, 15))
        useCase(a, neu, handFrei).syncAlarms(woche, neu, horizont).getOrThrow()
        assertEquals(listOf(kennungsId(1), kennungsId(5)), ohneEintrag(a).map { it.id })
        assertEquals(listOf(tag(1), tag(5)).map { millis(it.atTime(8, 15)) }, ohneEintrag(a).map { it.triggerTime })
        assertEquals(emptyList<AlarmInfo>(), a.notifier.ohneEintragGemeldet())
    }

    @Test
    fun `heuteDurchBeendeteSpanneBelegt - nur beendete Spannen, Ende 00 Uhr deckt den Endtag nicht`() {
        val zone = ZoneId.systemDefault()
        val heute = tag(3)
        val jetztMs = millis(heute.atTime(8, 0))
        fun spanne(von: LocalDateTime, bis: LocalDateTime) = ShiftSpan("X", millis(von), millis(bis), millis(von))

        // Nachtdienst bis 07:00 heute: belegt.
        assertTrue(AlarmUseCase.heuteDurchBeendeteSpanneBelegt(
            listOf(spanne(tag(2).atTime(21, 0), heute.atTime(7, 0))), heute, jetztMs, zone))
        // Endet genau um 00:00 heute: deckt heute nicht ab.
        assertFalse(AlarmUseCase.heuteDurchBeendeteSpanneBelegt(
            listOf(spanne(tag(2).atTime(16, 0), heute.atStartOfDay())), heute, jetztMs, zone))
        // Laeuft noch (steht in der Liste selbst): zaehlt hier nicht.
        assertFalse(AlarmUseCase.heuteDurchBeendeteSpanneBelegt(
            listOf(spanne(heute.atTime(6, 0), heute.atTime(14, 0))), heute, jetztMs, zone))
        // Beendet, aber gestern: zaehlt nicht.
        assertFalse(AlarmUseCase.heuteDurchBeendeteSpanneBelegt(
            listOf(spanne(tag(2).atTime(6, 0), tag(2).atTime(14, 0))), heute, jetztMs, zone))
    }
}
