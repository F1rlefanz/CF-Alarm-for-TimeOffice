package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FeedNeueinlesenStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Der Dienstplan kommt aus einem ABONNIERTEN Kalender (TimeOffice-ICS-Feed). Google liest den alle
 * paar Tage neu ein und vergibt dabei ALLEN Terminen neue Event-IDs - bei voellig unveraenderten
 * Schichten, Namen und Weckzeiten. Am Fairphone aus neun Tagen Datei-Log belegt: am 17.08.
 * "Created 9 / Deleted 8", am 20.08. "Created 11 / Deleted 11", Schnittmenge der Event-IDs
 * vorher/nachher jeweils LEER.
 *
 * Weil `AlarmInfo.id` aus `calendarEvent.id.hashCode()` abgeleitet wird und der Delta-Sync
 * ausschliesslich ueber `eventId` paarte, hiess das zweierlei:
 *  1. 11x "Schicht entfernt" + 11x "Neue Schicht erkannt" fuer Aenderungen, die es nie gab.
 *  2. SAEMTLICHE Systemwecker wurden abgebrochen und neu gestellt. Stirbt der kurzlebige
 *     Wartungs-Service mitten in der Sequenz (Low-Memory-Kill, Akku leer), fehlen Wecker - und ein
 *     uebersprungener Wecker verliert seinen Bezug (der Skip-Merker haengt an `AlarmInfo.id`).
 *
 * Diese Tests fixieren: ein Wecker ist DERSELBE, wenn er dieselbe Schicht zur selben Weckzeit
 * meint - auch wenn der Kalender ihm eine neue Kennung gegeben hat. Und ebenso wichtig die
 * Gegenprobe: eine echte Aenderung (andere Weckzeit, andere Schicht, gestrichener Termin) wird
 * weiterhin gemeldet.
 *
 * Getestet gegen die ECHTE [ShiftRecognitionEngine]; nur der Android-gebundene
 * [AlarmManagerService] ist gemockt.
 */
class AlarmUseCaseKennungswechselTest {

    // --- Fakes (bewusst eigenstaendig, damit dieser Test fuer sich lesbar bleibt) ---

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    /**
     * [protokoll] haelt die Reihenfolge fest - gebraucht fuer die Zusicherung "erst
     * cancelSystemAlarm, dann deleteAlarm".
     */
    private class FakeAlarmRepository(
        initial: List<AlarmInfo> = emptyList(),
        private val protokoll: MutableList<String> = mutableListOf(),
        /** Gesetzt = `getAllAlarms()` scheitert (Befund B: der Bestand ist nicht lesbar). */
        private val lesefehler: Throwable? = null,
        /**
         * Der REALISTISCHE Fall des unlesbaren Bestands: `getAllAlarms()` meldet keinen Fehler,
         * es liefert `Result.success` mit der Notlage-Leere. Nur `isPersistenceBlocked()` weiss
         * davon - genau deshalb reicht ein `getOrThrow()` an dieser Stelle nicht.
         */
        private val persistenzGesperrt: Boolean = false
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = persistenzGesperrt
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            protokoll += "save:${alarmInfo.id}"
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> =
            lesefehler?.let { Result.failure(it) } ?: Result.success(state.value)

        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })

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

        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(state.value.any { it.id == alarmId })
    }

    private class FakeSkipUseCase(private val zustand: AlarmSkipState = AlarmSkipState()) : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(zustand)

        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(zustand)
    }

    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        var createdCount = 0
        var updatedCount = 0
        var deletedCount = 0

        val meldungen: Int get() = createdCount + updatedCount + deletedCount

        override suspend fun notifyCreated(new: AlarmInfo) {
            createdCount++
        }

        /** Fuer die Namensaenderung: alt -> neu, nicht nur die Zahl. */
        val umbenennungen = mutableListOf<Pair<String, String>>()

        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) {
            updatedCount++
            umbenennungen += old.shiftName to new.shiftName
        }

        override suspend fun notifyDeleted(old: AlarmInfo) {
            deletedCount++
        }
    }

    // --- Fixtures ---

    private val fruehSchicht = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Gleiche Weckzeit wie [fruehSchicht], andere Schicht - fuer die Gegenprobe. */
    private val spaetSchicht = ShiftDefinition(
        id = "late",
        name = "Spaet",
        keywords = listOf("S"),
        alarmTime = LocalTime.of(5, 30)
    )

    private val config = ShiftConfig(
        autoAlarmEnabled = true,
        definitions = listOf(fruehSchicht, spaetSchicht)
    )

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert > now ist. */
    private fun event(id: String, title: String, day: Int) = CalendarEvent(
        id = id,
        title = title,
        startTime = LocalDateTime.of(2035, 6, day, 6, 0),
        endTime = LocalDateTime.of(2035, 6, day, 14, 0),
        calendarId = "test"
    )

    /** Die Weckzeit, die die Engine fuer [event] mit 05:30-Definition ausrechnet. */
    private fun weckzeit(day: Int): Long =
        LocalDateTime.of(2035, 6, day, 5, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun millis(zeit: LocalDateTime): Long =
        zeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Dieselbe Formatierung, die [AlarmUseCase.formatAlarmTime] erzeugt. */
    private fun formatiert(millis: Long): String =
        DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
            .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))

    /**
     * Ein bestehender Alarm, wie ihn ein FRUEHERER Sync desselben Termins wirklich hinterlassen
     * haette - inklusive Schichtbeginn, Schichtende und formatierter Weckzeit.
     *
     * WARUM DIE FELDER VOLLSTAENDIG SEIN MUESSEN: Der Delta-Sync vergleicht das ganze
     * [AlarmInfo]. Solange der Zweig fuer die stille Uebernahme VOR diesem Vergleich stand, war
     * das egal - er verschluckte jede Abweichung, auch eine unbeabsichtigte aus der Testvorlage.
     * Genau das war der Befund (eine Umbenennung waere lautlos verschwunden). Jetzt entscheidet
     * wieder der Vergleich, und eine halb gefuellte Vorlage wuerde eine Aenderung vortaeuschen,
     * die es im echten Bestand nie gibt.
     *
     * Einzige bewusste Abweichung bleibt [eventChecksum]: sie gehoert - wie die Kennung selbst -
     * zu dem, was bei einem Feed-Neueinlesen still uebernommen wird.
     */
    private fun bestehenderAlarm(
        id: Int,
        eventId: String,
        day: Int,
        shiftId: String = "early",
        shiftName: String = "Frueh"
    ) = AlarmInfo(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = weckzeit(day),
        formattedTime = formatiert(weckzeit(day)),
        eventId = eventId,
        eventChecksum = "alter-checksum",
        shiftStartTime = millis(LocalDateTime.of(2035, 6, day, 6, 0)),
        shiftEndTime = millis(LocalDateTime.of(2035, 6, day, 14, 0))
    )

    private fun mockManager(protokoll: MutableList<String>? = null): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(status)
        if (protokoll == null) {
            whenever(m.cancelSystemAlarm(any())).thenReturn(status)
        } else {
            whenever(m.cancelSystemAlarm(any())).thenAnswer { aufruf ->
                protokoll += "cancel:${aufruf.arguments[0]}"
                status
            }
        }
        return m
    }

    private fun useCase(
        repo: FakeAlarmRepository,
        manager: AlarmManagerService,
        notifier: FakeShiftChangeNotifier,
        skipZustand: AlarmSkipState = AlarmSkipState(),
        feedStore: FakeFeedNeueinlesenStore = FakeFeedNeueinlesenStore()
    ): AlarmUseCase = AlarmUseCase(
        repo,
        manager,
        FakeShiftConfigRepository(config),
        ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
        FakeSkipUseCase(skipZustand),
        notifier,
        mock<MasterPausePrefs>().also {
            kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
        },
        mock<ShiftSpanStore>(),
        FakeSyncHorizonStore(),
        feedStore,
        keineFreienTage()
    )

    // --- Tests ---

    @Test
    fun `Feed neu eingelesen - 11 Schichten mit neuen Event-IDs erzeugen keine einzige Aenderung`() = runTest {
        // DER REALE FALL vom 20.08.: Created 11 / Deleted 11, Schnittmenge der Event-IDs = null,
        // Schichten und Weckzeiten unveraendert.
        val tage = (1..11).toList()
        val bestand = tage.map { tag ->
            bestehenderAlarm(id = 1000 + tag, eventId = "alt-$tag", day = tag)
        }
        val neueEvents = tage.map { tag -> event("neu-$tag", "F", tag) }

        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(bestand, protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier).syncAlarms(neueEvents, config)

        assertTrue(result.isSuccess)
        assertEquals("Es darf KEINE Meldung geben - fuer den Nutzer hat sich nichts geaendert",
            0, notifier.meldungen)
        assertEquals("Kein Wecker darf abgebrochen werden - die Weckzeiten stimmen ja noch",
            emptyList<String>(), protokoll.filter { it.startsWith("cancel:") })
        assertEquals("Kein Wecker darf geloescht werden",
            emptyList<String>(), protokoll.filter { it.startsWith("delete:") })
        assertEquals("Die Alarm-IDs muessen dieselben bleiben - an ihnen haengen Request-Code, " +
            "Direct-Boot-Spiegel, Notification-ID und Skip-Merker",
            bestand.map { it.id }.toSet(), repo.current.map { it.id }.toSet())
        assertEquals("Die neue Kalender-Kennung wird trotzdem uebernommen",
            tage.map { "neu-$it" }.toSet(), repo.current.map { it.eventId }.toSet())
        assertEquals(11, repo.current.size)
    }

    /**
     * Die Grundlage der stillen Statuszeile "Dienstplan-Kalender zuletzt neu eingelesen".
     *
     * Der Vorgang ist fuer den Nutzer voellig unsichtbar - genau richtig, aber auf seine Frage
     * "woran erkenne ich das?" gab es bis dahin keine Antwort in der App. Gespeichert werden darf
     * das nur, wenn es wirklich vorkam (siehe die Gegenprobe darunter).
     */
    @Test
    fun `Kennungswechsel wird mit Zeitpunkt und Anzahl gemerkt`() = runTest {
        val tage = (1..11).toList()
        val bestand = tage.map { tag -> bestehenderAlarm(id = 1000 + tag, eventId = "alt-$tag", day = tag) }
        val neueEvents = tage.map { tag -> event("neu-$tag", "F", tag) }

        val repo = FakeAlarmRepository(bestand)
        val feedStore = FakeFeedNeueinlesenStore()
        val vorher = System.currentTimeMillis()

        val result = useCase(repo, mockManager(), FakeShiftChangeNotifier(), feedStore = feedStore)
            .syncAlarms(neueEvents, config)

        assertTrue(result.isSuccess)
        val stand = feedStore.stand
        assertNotNull("Der Kennungswechsel muss fuer die Statuszeile festgehalten werden", stand)
        assertEquals("Die Anzahl muss die tatsaechlich wiedererkannten Wecker nennen",
            11, stand!!.anzahl)
        assertTrue("Der Zeitpunkt muss aus diesem Lauf stammen",
            stand.zeitpunkt >= vorher && stand.zeitpunkt <= System.currentTimeMillis())
        assertEquals("Genau EIN Schreibvorgang pro Lauf", 1, feedStore.merkeAufrufe)
    }

    /**
     * DIE EIGENTLICHE ZUSICHERUNG: ein Lauf OHNE Kennungswechsel - also der Normalfall, der
     * zwischen zwei Feed-Neueinlesungen viele Male laeuft - darf den letzten Stand nicht
     * anruehren. Sonst stuende in der Statuszeile binnen Stunden "heute, 0 Wecker", der letzte
     * echte Vorgang waere ueberschrieben, und die Zeile beantwortete genau die Frage nicht mehr,
     * fuer die sie gebaut wurde.
     */
    @Test
    fun `ein Lauf ohne Kennungswechsel laesst den letzten Stand stehen`() = runTest {
        val frueher = FeedNeueinlesenStand(zeitpunkt = 1_700_000_000_000L, anzahl = 7)
        val bestand = listOf(bestehenderAlarm(id = 601, eventId = "evA", day = 1))
        val repo = FakeAlarmRepository(bestand)
        val feedStore = FakeFeedNeueinlesenStore(stand = frueher)

        // Dieselbe Kennung wie im Bestand - es hat also kein Neueinlesen stattgefunden.
        val result = useCase(repo, mockManager(), FakeShiftChangeNotifier(), feedStore = feedStore)
            .syncAlarms(listOf(event("evA", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals("Ohne Kennungswechsel darf gar nicht geschrieben werden",
            0, feedStore.merkeAufrufe)
        assertEquals("Der letzte echte Stand muss unveraendert stehen bleiben",
            frueher, feedStore.stand)
    }

    @Test
    fun `gleiche Weckzeit aber ANDERE Schicht - echte Aenderung, wird gemeldet`() = runTest {
        // "Der Chef stellt F auf S um": selbe Weckzeit, andere Schicht. Das MUSS ankommen.
        val bestand = listOf(bestehenderAlarm(id = 500, eventId = "alt-1", day = 1))
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(bestand, protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier)
            .syncAlarms(listOf(event("neu-1", "S", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals("Die weggefallene Fruehschicht muss gemeldet werden", 1, notifier.deletedCount)
        assertEquals("Die neue Spaetschicht muss gemeldet werden", 1, notifier.createdCount)
        assertTrue("Der alte Systemwecker muss abgebrochen werden", protokoll.contains("cancel:500"))
        assertNull("Der alte Alarm ist weg", repo.current.find { it.id == 500 })
        assertNotNull("Der neue Alarm traegt die neue Schicht",
            repo.current.find { it.shiftId == "late" })
    }

    @Test
    fun `gleiche Schicht aber ANDERE Weckzeit - echte Aenderung, wird gemeldet`() = runTest {
        // Der Termin ist verschoben worden (anderer Tag) UND hat eine neue Kennung. Die Paarung
        // darf hier NICHT greifen - eine verschobene Weckzeit ist eine echte Aenderung.
        val bestand = listOf(bestehenderAlarm(id = 501, eventId = "alt-1", day = 1))
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(bestand, protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier)
            .syncAlarms(listOf(event("neu-1", "F", 2)), config)

        assertTrue(result.isSuccess)
        assertTrue("Die Verschiebung muss den Nutzer erreichen", notifier.meldungen > 0)
        assertTrue("Der alte Systemwecker muss abgebrochen werden", protokoll.contains("cancel:501"))
        assertNull("Der Alarm auf die alte Weckzeit darf nicht stehen bleiben",
            repo.current.find { it.triggerTime == weckzeit(1) })
        assertNotNull("Der Alarm auf die neue Weckzeit muss existieren",
            repo.current.find { it.triggerTime == weckzeit(2) })
    }

    @Test
    fun `Termin faellt wirklich weg - Loeschung samt Meldung, und vorher wurde gecancelt`() = runTest {
        val bleibt = bestehenderAlarm(id = 601, eventId = "evA", day = 1)
        val faelltWeg = bestehenderAlarm(id = 602, eventId = "evB", day = 2)
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(listOf(bleibt, faelltWeg), protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        // Nur evA wird uebergeben - evB ist aus dem Kalender verschwunden.
        val result = useCase(repo, manager, notifier)
            .syncAlarms(listOf(event("evA", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals("Ein wirklich gestrichener Termin wird gemeldet", 1, notifier.deletedCount)
        assertEquals(0, notifier.createdCount)
        assertNull("Der Alarm des gestrichenen Termins muss weg sein",
            repo.current.find { it.id == 602 })
        assertNotNull("Der andere Alarm bleibt", repo.current.find { it.id == 601 })

        val cancelIndex = protokoll.indexOf("cancel:602")
        val deleteIndex = protokoll.indexOf("delete:602")
        assertTrue("cancelSystemAlarm muss aufgerufen worden sein", cancelIndex >= 0)
        assertTrue("deleteAlarm muss aufgerufen worden sein", deleteIndex >= 0)
        assertTrue(
            "ERST cancelSystemAlarm, DANN deleteAlarm - umgekehrt bleibt ein armierter Alarm " +
                "zurueck, den niemand mehr abbrechen kann",
            cancelIndex < deleteIndex
        )
    }

    @Test
    fun `zwei Wecker mit gleicher Weckzeit und gleicher Schicht - keine Doppelpaarung, keiner geht verloren`() = runTest {
        // Doppelung im Feed: zwei Termine derselben Schicht am selben Tag. Jeder Kandidat darf
        // hoechstens EINEN bestehenden Alarm greifen.
        val bestand = listOf(
            bestehenderAlarm(id = 701, eventId = "alt-1", day = 1),
            bestehenderAlarm(id = 702, eventId = "alt-2", day = 1)
        )
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(bestand, protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier)
            .syncAlarms(listOf(event("neu-1", "F", 1), event("neu-2", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals("Keine Meldung - beide Schichten gibt es unveraendert weiter",
            0, notifier.meldungen)
        assertEquals("Kein Wecker darf abgebrochen werden",
            emptyList<String>(), protokoll.filter { it.startsWith("cancel:") })
        assertEquals("Beide bestehenden Wecker muessen ueberleben, jeder genau einmal",
            setOf(701, 702), repo.current.map { it.id }.toSet())
        assertEquals("Kein Wecker darf verloren gehen und keiner doppelt entstehen",
            2, repo.current.size)
        assertEquals("Beide haben eine eigene, neue Kennung bekommen",
            setOf("neu-1", "neu-2"), repo.current.map { it.eventId }.toSet())
    }

    // --- Der uebersprungene Wecker (Befund A der Pruefrunde zu diesem Fix) ---

    @Test
    fun `uebersprungener Wecker bleibt uebersprungen, auch wenn der Feed neue Kennungen vergibt`() = runTest {
        // DER BEFUND: `AlarmSkipUseCase.skipNextAlarm()` LOESCHT den Eintrag aus dem Repository -
        // "immer, fuer jede Alarmart". Beim naechsten Sync gibt es also gar keinen bestehenden
        // Alarm, den die Paarung wiedererkennen koennte; nach einem Feed-Neueinlesen baut der Sync
        // den Wecker mit einer NEUEN id aus der neuen Kennung auf. Beide Skip-Gates verglichen
        // aber ausschliesslich ids. Folge: der Nutzer drueckt abends "Ueberspringen", der Feed
        // rotiert nachts, und am freien Morgen klingelt der Wecker doch.
        val uebersprungen = AlarmSkipState(
            isNextAlarmSkipped = true,
            skippedAlarmId = 900,                 // die id von VOR der Rotation - jetzt wertlos
            skippedAlarmTriggerTime = weckzeit(1) // der stabile Anker
        )
        val anderer = bestehenderAlarm(id = 901, eventId = "alt-2", day = 2)
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(listOf(anderer), protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier, uebersprungen)
            .syncAlarms(listOf(event("neu-1", "F", 1), event("neu-2", "F", 2)), config)

        assertTrue(result.isSuccess)
        assertNull(
            "Der uebersprungene Wecker darf durch den Kennungswechsel nicht wieder entstehen",
            repo.current.find { it.triggerTime == weckzeit(1) }
        )
        assertEquals(
            "Und schon gar nicht als 'Neue Schicht erkannt'",
            0,
            notifier.createdCount
        )
        assertNotNull(
            "Der Wecker des naechsten Tages bleibt davon voellig unberuehrt",
            repo.current.find { it.id == 901 }
        )
    }

    @Test
    fun `der Backstop in scheduleSystemAlarm erkennt ihn ebenfalls nach einem Kennungswechsel`() = runTest {
        // Das Gate in syncAlarms() und dieser Backstop MUESSEN denselben Wecker erkennen - sonst
        // haelt nur die Haelfte, und je nach Weg (Sync, BootReceiver, Wartung) klingelt er doch.
        val uebersprungen = AlarmSkipState(
            isNextAlarmSkipped = true,
            skippedAlarmId = 900,
            skippedAlarmTriggerTime = weckzeit(1)
        )
        val manager = mockManager()
        val nachRotation = bestehenderAlarm(id = 12345, eventId = "neu-1", day = 1)

        val ergebnis = useCase(FakeAlarmRepository(), manager, FakeShiftChangeNotifier(), uebersprungen)
            .scheduleSystemAlarm(nachRotation)

        assertTrue("Der Backstop muss abweisen", ergebnis.isFailure)
        assertTrue(
            "Und zwar sichtbar - ein stiller Erfolg waere ein stummer Wecker MIT Anzeige",
            ergebnis.exceptionOrNull() is SkippedAlarmNotArmedException
        )
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
    }

    @Test
    fun `ein MANUELLER Wecker zur selben Weckzeit wird NICHT mit uebersprungen`() = runTest {
        // Die Gegenprobe zum Weckzeit-Anker: manuelle Wecker sind von der Kennungsrotation gar
        // nicht betroffen (ihre id ist stabil), und wer sich von Hand auf dieselbe Minute stellt,
        // darf durch das Ueberspringen einer Schicht nicht stumm werden.
        val uebersprungen = AlarmSkipState(
            isNextAlarmSkipped = true,
            skippedAlarmId = 900,
            skippedAlarmTriggerTime = weckzeit(1)
        )
        val manager = mockManager()
        val manuell = AlarmInfo(
            id = 777,
            shiftId = "manual_2035-06-01",
            shiftName = "Manueller Wecker",
            triggerTime = weckzeit(1),
            formattedTime = "x",
            eventId = ""
        )

        val ergebnis = useCase(FakeAlarmRepository(), manager, FakeShiftChangeNotifier(), uebersprungen)
            .scheduleSystemAlarm(manuell)

        assertTrue("Der manuelle Wecker muss gestellt werden", ergebnis.isSuccess)
        verify(manager).setAlarmFromShiftMatch(any(), any(), any())
    }

    // --- Der nicht lesbare Bestand (Befund B) ---

    @Test
    fun `nicht lesbarer Alarm-Bestand bricht den Sync ab statt auf einer erfundenen Leere weiterzurechnen`() = runTest {
        // Frueher war `id = eventId.hashCode()` eine reine Funktion des Events - ein auf leerem
        // Bestand neu angelegter Alarm ueberschrieb den gespeicherten Eintrag punktgenau, der
        // Lesefehler heilte sich selbst. Seit die id vom gepaarten Vorgaenger mitwandert, erzeugt
        // derselbe Lesefehler einen ZWEITEN Eintrag mit eigener id und einen zweiten armierten
        // Systemwecker: der Wecker feuert doppelt, und der naechste Sync raeumt den Waisen mit
        // einer sachlich falschen "Schicht entfernt"-Meldung ab.
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(
            initial = listOf(bestehenderAlarm(id = 950, eventId = "alt-1", day = 1)),
            protokoll = protokoll,
            lesefehler = IllegalStateException("Alarm-Bestand nicht lesbar (Test)")
        )
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier).syncAlarms(listOf(event("neu-1", "F", 1)), config)

        assertTrue("Ein nicht lesbarer Bestand ist keine leere Liste", result.isFailure)
        assertEquals(
            "Es darf kein Alarm geschrieben worden sein",
            emptyList<String>(),
            protokoll.filter { it.startsWith("save:") }
        )
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
        assertEquals("Und keine Meldung an den Nutzer", 0, notifier.meldungen)
    }

    // --- Kennungswechsel UND echte Aenderung zugleich (Befund D) ---

    @Test
    fun `Umbenennung zusammen mit einem Kennungswechsel wird gemeldet, nicht verschluckt`() = runTest {
        // Der Zweig fuer die stille Uebernahme stand VOR dem Vergleich und behandelte damit jede
        // weitere Abweichung als "hat sich nichts geaendert" - obwohl der ShiftChangeNotifier eine
        // Namensaenderung ausdruecklich als meldepflichtig fuehrt. Faellt eine Umbenennung mit
        // einem Feed-Neueinlesen zusammen, verschwand sie lautlos und wurde auch spaeter nie
        // gemeldet (beim naechsten Lauf ist der neue Name ja schon gespeichert).
        val bestand = listOf(
            bestehenderAlarm(id = 800, eventId = "alt-1", day = 1, shiftName = "Frueh (alter Name)")
        )
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(bestand, protokoll)
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier).syncAlarms(listOf(event("neu-1", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals(
            "Die Umbenennung muss den Notifier erreichen - was meldepflichtig ist, entscheidet er",
            listOf("Frueh (alter Name)" to "Frueh"),
            notifier.umbenennungen
        )
        assertEquals("Als Aenderung, nicht als Neuanlage", 0, notifier.createdCount)
        assertEquals("Und nicht als Loeschung", 0, notifier.deletedCount)
        assertNotNull(
            "Die Wecker-Identitaet bleibt trotzdem erhalten - die id wandert nicht",
            repo.current.find { it.id == 800 && it.shiftName == "Frueh" && it.eventId == "neu-1" }
        )
    }

    @Test
    fun `gesperrte Persistenz bricht den Sync ab - auch wenn getAllAlarms Erfolg meldet`() = runTest {
        // Die Gegenprobe zum Test darueber, und der Fall, der in der Praxis eintritt:
        // `AlarmRepository.getAllAlarms()` liefert IMMER `Result.success(_activeAlarms.value)` -
        // im Notfall eben die leere Liste. Ein `getOrThrow()` sieht davon nichts. Der einzige
        // ehrliche Zeuge ist `isPersistenceBlocked()`; ohne diese Frage haette der Sync jede
        // Schicht neu angelegt und - seit die id vom Vorgaenger mitwandert - einen ZWEITEN
        // armierten Wecker pro Schicht erzeugt.
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(
            initial = emptyList(),
            protokoll = protokoll,
            persistenzGesperrt = true
        )
        val manager = mockManager(protokoll)
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(repo, manager, notifier).syncAlarms(listOf(event("neu-1", "F", 1)), config)

        assertTrue("Gesperrte Persistenz ist kein brauchbarer Bestand", result.isFailure)
        assertEquals(
            "Es darf kein Alarm geschrieben worden sein",
            emptyList<String>(),
            protokoll.filter { it.startsWith("save:") }
        )
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
        assertEquals("Und keine Meldung an den Nutzer", 0, notifier.meldungen)
    }

    @Test
    fun `ein uebersprungener MANUELLER Wecker legt keine Schicht zur selben Weckzeit still`() = runTest {
        // Kriterium (b) der Skip-Erkennung vergleicht Weckzeitpunkte. Ohne die Bedingung
        // "auch der UEBERSPRUNGENE war kalenderbasiert" haette das Abschalten eines von Hand
        // gestellten 05:00-Weckers die Schicht mit derselben Weckzeit mit stumm gestellt - der
        // Nutzer haette an einem Arbeitstag verschlafen. Beide liegen auf vollen Minuten; die
        // Kollision ist keine Exotik.
        val uebersprungenerManueller = AlarmSkipState(
            isNextAlarmSkipped = true,
            skippedAlarmId = 12345,
            skippedAlarmTriggerTime = weckzeit(1),
            skippedManualAlarm = "{\"id\":12345}"
        )
        val manager = mockManager()
        val schicht = bestehenderAlarm(id = 42, eventId = "kalender-1", day = 1)

        val ergebnis = useCase(
            FakeAlarmRepository(), manager, FakeShiftChangeNotifier(), uebersprungenerManueller
        ).scheduleSystemAlarm(schicht)

        assertTrue("Die Schicht muss gestellt werden", ergebnis.isSuccess)
        verify(manager).setAlarmFromShiftMatch(any(), any(), any())
    }

    /**
     * Die Statuszeile im Status-Tab fasst das Neueinlesen mit dem Satz zusammen
     * "Am Dienstplan hat sich dadurch nichts geaendert". Also darf sie NUR die Faelle zaehlen, in
     * denen das auch stimmt.
     *
     * Faellt ein Kennungswechsel mit einer echten Aenderung zusammen (der Chef benennt um,
     * waehrend Google den Feed neu einliest), bekommt der Nutzer dafuer eine
     * "Schicht geaendert"-Meldung. Zaehlte die Statuszeile diesen Wecker mit, widerspraeche ihre
     * ruhige Auskunft genau der Meldung, die er ernst nehmen soll.
     *
     * OHNE DEN FIX faellt dieser Test: gezaehlt wurde `neueKennungCount`, und der zaehlt den
     * Aenderungszweig ausdruecklich mit (fuer die WARN-Zeile im Log ist das richtig).
     */
    @Test
    fun `ein Kennungswechsel MIT echter Aenderung zaehlt nicht fuer die Statuszeile`() = runTest {
        val bestand = listOf(
            bestehenderAlarm(id = 800, eventId = "alt-1", day = 1, shiftName = "Frueh (alter Name)")
        )
        val feedStore = FakeFeedNeueinlesenStore()
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(FakeAlarmRepository(bestand), mockManager(), notifier, feedStore = feedStore)
            .syncAlarms(listOf(event("neu-1", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals(
            "Die Umbenennung ist eine echte Aenderung und wird gemeldet",
            1, notifier.umbenennungen.size
        )
        assertEquals(
            "Aber die Statuszeile darf sie nicht als folgenlose Uebernahme fuehren",
            0, feedStore.merkeAufrufe
        )
        assertNull("Und damit steht dort auch kein Stand", feedStore.stand)
    }
}
