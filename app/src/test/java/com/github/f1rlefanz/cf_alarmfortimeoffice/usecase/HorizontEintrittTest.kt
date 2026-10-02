package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SyncHorizonStore
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Der wandernde Abruf-Horizont ist KEINE Dienstplan-Aenderung - und eine vergroesserte
 * Vorausschau auch nicht (#51).
 *
 * DER BEFUND (Fairphone, v1.29.2, aus echter Nutzung gemeldet): Der Nutzer bekam "seit Tagen immer
 * wieder" die Meldung "Neue Schicht erkannt" und hielt sie fuer eine Aenderung seines Chefs. Sie
 * war keine. Die App holt Termine fuer die eingestellte Vorausschau (Standard 14 Tage) ab JETZT; dieses
 * Fenster wandert taeglich einen Tag weiter, und der jeweils neue Randtag sieht fuer den Delta-Sync
 * aus wie ein neues Event. Der Alarm-Bestand war dabei voellig stabil ("Created: 0, Updated: 0,
 * Deleted: 0" im Folge-Sync), die Meldung lebte laut `dumpsys` ~6 Tage und wurde jeden Tag mit dem
 * neuen Randtag aktualisiert.
 *
 * Der einzige Daempfer davor - `isFirstSync = existingAlarms.isEmpty()` - griff nur nach einer
 * Neuinstallation.
 *
 * Diese Tests halten die Unterscheidung fest:
 *  - Randtag rutscht ins Fenster  -> Wecker JA, Meldung NEIN
 *  - Schicht innerhalb des bereits abgedeckten Zeitraums -> Wecker JA, Meldung JA
 *  - unvollstaendiger Lauf        -> der Bezugspunkt wird NICHT fortgeschrieben, die echte
 *                                    Aenderung wird im naechsten Lauf gemeldet
 *  - Vorausschau vergroessert     -> was hinter dem GESPEICHERTEN Fenster neu sichtbar wird, ist
 *                                    ein Horizont-Eintritt (keine Meldungsflut, #51)
 *
 * "Schicht geaendert"/"Schicht entfernt" sind hier bewusst nicht beruehrt - sie betreffen
 * bestehende Eintraege und sind immer echte Aenderungen.
 */
class HorizontEintrittTest {

    // --- Fakes ---

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
    }

    /**
     * [scheiterndeEventIds] laesst `saveAlarm` fuer genau diese Events fehlschlagen - so entsteht
     * ein Lauf mit `skippedCount > 0`, ohne den restlichen Sync anzufassen (das pro-Event-try/catch
     * faengt ihn ab).
     */
    private class FakeAlarmRepository(
        initial: List<AlarmInfo> = emptyList(),
        var scheiterndeEventIds: Set<String> = emptySet()
    ) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        val current: List<AlarmInfo> get() = state.value

        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        override val activeAlarms: Flow<List<AlarmInfo>> = state

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            if (alarmInfo.eventId in scheiterndeEventIds) {
                return Result.failure(IllegalStateException("Schreibfehler (Test) fuer ${alarmInfo.eventId}"))
            }
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }
    }

    private class FakeSkipUseCase : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used"))
        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)
        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)
        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())
        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)
        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    /** Merkt sich, WELCHE Schichten als "neu erkannt" gemeldet wurden - nicht nur wie viele. */
    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        val gemeldetNeu = mutableListOf<String>()
        val gemeldetGeaendert = mutableListOf<String>()
        val gemeldetEntfernt = mutableListOf<String>()

        override suspend fun notifyCreated(new: AlarmInfo) { gemeldetNeu += new.shiftName }
        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) { gemeldetGeaendert += new.shiftName }
        override suspend fun notifyDeleted(old: AlarmInfo) { gemeldetEntfernt += old.shiftName }
    }

    // --- Fixtures ---

    /** Spaetschicht wie im gemeldeten Fall: Beginn 12:30, Weckzeit 10:30 desselben Tages. */
    private val spaetschicht = ShiftDefinition(
        id = "spaet",
        name = "Spaet",
        keywords = listOf("S"),
        alarmTime = LocalTime.of(10, 30)
    )

    /**
     * ZWEITE, UNTERSCHEIDBARE Schicht - und das ist kein Beiwerk.
     *
     * Solange Randtag und echte Nachtragung beide "Spaet" hiessen, konnte der Kerntest nur
     * zaehlen ("eine Meldung statt zwei") und waere auch dann gruen geblieben, wenn die
     * Unterdrueckung genau falsch herum gewirkt haette - der Randtag gemeldet, die echte
     * Nachtragung verschluckt. Deshalb bekommt jeder Fall eine eigene Identitaet, und die Tests
     * pruefen, WELCHE Schicht gemeldet wurde.
     */
    private val fruehschicht = ShiftDefinition(
        id = "frueh",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(6, 30)
    )

    private val config = ShiftConfig(
        autoAlarmEnabled = true,
        definitions = listOf(spaetschicht, fruehschicht)
    )

    /**
     * Ein Ereignis [tage] Tage in der Zukunft. Uhrzeit fest auf 12:30, damit die Weckzeit (10:30
     * bzw. 06:30) am selben Tag liegt, garantiert in der Zukunft ist und die Schichterkennung
     * greift. [titel] entscheidet, WELCHE Schicht erkannt wird.
     */
    private fun eventInTagen(id: String, tage: Long, titel: String = "S Dienst"): CalendarEvent {
        val start = LocalDateTime.now().plusDays(tage).withHour(12).withMinute(30).withSecond(0).withNano(0)
        return CalendarEvent(
            id = id,
            title = titel,
            startTime = start,
            endTime = start.plusHours(8),
            calendarId = "test"
        )
    }

    /** Irgendein bereits bestehender Alarm - nur damit `isFirstSync` false ist. */
    private fun bestandsAlarm(eventId: String) = AlarmInfo(
        id = eventId.hashCode(),
        shiftId = "spaet",
        shiftName = "Spaet",
        triggerTime = 111L,
        formattedTime = "x",
        eventId = eventId,
        eventChecksum = "alt"
    )

    private fun mockManager(): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(status)
        whenever(m.cancelSystemAlarm(any())).thenReturn(status)
        return m
    }

    private fun useCase(
        repo: FakeAlarmRepository,
        manager: AlarmManagerService,
        notifier: FakeShiftChangeNotifier,
        horizonStore: FakeSyncHorizonStore,
        feedStore: FakeFeedNeueinlesenStore = FakeFeedNeueinlesenStore()
    ) = AlarmUseCase(
        repo,
        manager,
        FakeShiftConfigRepository(config),
        ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
        FakeSkipUseCase(),
        notifier,
        mock<MasterPausePrefs>().also {
            kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
        },
        mock<ShiftSpanStore>(),
        horizonStore,
        feedStore,
        keineFreienTage(),
        FakeKalenderVorausschauPrefs()
    )

    /** Der letzte vollstaendige Sync lief GESTERN - der bekannte Horizont endet also in 13 Tagen. */
    private fun gestern(): Long = System.currentTimeMillis() - 24L * 60 * 60 * 1000

    /**
     * Ein Zeitpunkt fuer den letzten vollstaendigen Sync, dessen Horizont eine Minute VOR
     * [event] endet - [event] ist damit eindeutig gerade erst hereingerutscht.
     *
     * Bewusst aus dem Ereignis zurueckgerechnet statt "gestern, 14 Tage drauf": ein fester
     * Tagesabstand faellt je nach Uhrzeit des Testlaufs mal vor und mal hinter die Grenze - so ein
     * Test waere nur bis mittags gruen.
     */
    private fun letzterSyncKnappVor(event: CalendarEvent): Long {
        val beginn = event.startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return beginn - SyncHorizonStore.ALTBESTAND_FENSTER_TAGE * 24L * 60 * 60 * 1000 - 60_000L
    }

    // --- Tests ---

    @Test
    fun `Randtag rutscht in den Horizont - Wecker wird gestellt, aber NICHT als neue Schicht gemeldet`() = runTest {
        // Der Bezugspunkt ist so gelegt, dass der bekannte Horizont eine Minute VOR diesem Termin
        // endete - er war beim letzten Sync also noch gar nicht abrufbar. Genau der Fall, der dem
        // Nutzer taeglich eine Meldung gebracht hat.
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val randtag = eventInTagen("evRand", 13)
        val horizonStore = FakeSyncHorizonStore(letzterSync = letzterSyncKnappVor(randtag))

        val result = useCase(repo, manager, notifier, horizonStore)
            .syncAlarms(listOf(randtag, eventInTagen("evBestand", 2)), config)

        assertTrue(result.isSuccess)
        assertEquals(
            "Ein blosser Horizont-Eintritt darf NICHT als neue Schicht gemeldet werden",
            emptyList<String>(),
            notifier.gemeldetNeu
        )
        // Der Wecker selbst bleibt davon unberuehrt - das ist die eigentliche Aufgabe der App.
        assertNotNull(
            "Der Wecker fuer den Randtag muss trotzdem angelegt werden",
            repo.current.find { it.eventId == "evRand" }
        )
        verify(manager).setAlarmFromShiftMatch(any(), any(), eq("evRand".hashCode()))
    }

    @Test
    fun `Schicht innerhalb des bekannten Zeitraums wird gemeldet - der Randtag daneben nicht`() = runTest {
        // EIN Sync, ZWEI neue Events: der Chef traegt fuer naechste Woche etwas nach (Tag 3, lag
        // gestern laengst im Fenster) UND der Horizont wandert um einen Tag weiter (Tag 13).
        // Ohne die Unterscheidung meldet die App beides; richtig ist genau eine Meldung.
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val randtag = eventInTagen("evRand", 13)
        val horizonStore = FakeSyncHorizonStore(letzterSync = letzterSyncKnappVor(randtag))

        val result = useCase(repo, manager, notifier, horizonStore).syncAlarms(
            listOf(
                // Die Nachtragung ist eine FRUEHSCHICHT, der Randtag eine SPAETSCHICHT - so
                // beweist die Zusicherung unten, welche der beiden gemeldet wurde. Mit zweimal
                // demselben Namen waere "eine Meldung statt zwei" auch dann erfuellt, wenn genau
                // die falsche durchkaeme.
                eventInTagen("evNachtrag", 3, titel = "F Dienst"),
                randtag,
                eventInTagen("evBestand", 2)
            ),
            config
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "Gemeldet wird die echte Nachtragung (Frueh) - und NUR sie, nicht der " +
                "Horizont-Eintritt (Spaet)",
            listOf("Frueh"),
            notifier.gemeldetNeu
        )
        assertNotNull("Auch der Randtag bekommt seinen Wecker", repo.current.find { it.eventId == "evRand" })
        assertNotNull("Die Nachtragung bekommt ihren Wecker", repo.current.find { it.eventId == "evNachtrag" })
    }

    @Test
    fun `ohne Bezugspunkt wird gemeldet - die Degradation geht bewusst in Richtung Meldung`() = runTest {
        // Kein Merker (erster Lauf nach dem Update) oder Lesefehler: MELDEN. Eine ueberfluessige
        // Meldung ist laestig, eine verschwiegene echte Aenderung kostet Vertrauen.
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val horizonStore = FakeSyncHorizonStore(leseFehler = java.io.IOException("Store kaputt (Test)"))

        useCase(repo, manager, notifier, horizonStore)
            .syncAlarms(
                listOf(eventInTagen("evRand", 13, titel = "F Dienst"), eventInTagen("evBestand", 2)),
                config
            )

        assertEquals(
            "Ohne lesbaren Bezugspunkt wird im Zweifel gemeldet - und zwar genau der Randtag",
            listOf("Frueh"),
            notifier.gemeldetNeu
        )
    }

    @Test
    fun `unvollstaendiger Sync schiebt den Bezugspunkt nicht vor und verliert die Meldung nicht`() = runTest {
        val manager = mockManager()
        val horizonStore = FakeSyncHorizonStore(letzterSync = gestern())
        val vorher = horizonStore.letzterSync

        // Lauf 1: das Speichern der Nachtragung scheitert -> skippedCount > 0.
        val repo = FakeAlarmRepository(
            initial = listOf(bestandsAlarm("evBestand")),
            scheiterndeEventIds = setOf("evNachtrag")
        )
        val notifier1 = FakeShiftChangeNotifier()
        useCase(repo, manager, notifier1, horizonStore)
            .syncAlarms(
                listOf(eventInTagen("evNachtrag", 3, titel = "F Dienst"), eventInTagen("evBestand", 2)),
                config
            )

        assertEquals("Der gescheiterte Alarm darf nicht gemeldet worden sein", emptyList<String>(), notifier1.gemeldetNeu)
        assertEquals("Ein unvollstaendiger Lauf schreibt den Bezugspunkt NICHT fort", 0, horizonStore.merkeAufrufe)
        assertNull(horizonStore.gemerkt)
        assertEquals(vorher, horizonStore.letzterSync)

        // Lauf 2: das Speichern klappt wieder - die echte Aenderung muss jetzt gemeldet werden.
        repo.scheiterndeEventIds = emptySet()
        val notifier2 = FakeShiftChangeNotifier()
        useCase(repo, manager, notifier2, horizonStore)
            .syncAlarms(
                listOf(eventInTagen("evNachtrag", 3, titel = "F Dienst"), eventInTagen("evBestand", 2)),
                config
            )

        assertEquals(
            "Die im ersten Lauf gescheiterte echte Aenderung darf nicht verloren gehen",
            listOf("Frueh"),
            notifier2.gemeldetNeu
        )
        assertNotNull("Und ihr Wecker muss jetzt stehen", repo.current.find { it.eventId == "evNachtrag" })
        assertEquals("Erst der vollstaendige Lauf schreibt fort", 1, horizonStore.merkeAufrufe)
        assertNotNull(horizonStore.gemerkt)
    }

    @Test
    fun `vollstaendiger Sync schreibt den Bezugspunkt fort`() = runTest {
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val horizonStore = FakeSyncHorizonStore(letzterSync = gestern())
        val vorher = System.currentTimeMillis()

        useCase(repo, manager, notifier, horizonStore)
            .syncAlarms(listOf(eventInTagen("evBestand", 2)), config)

        val gemerkt = horizonStore.gemerkt
        assertNotNull("Nach einem vollstaendigen Lauf muss der Bezugspunkt stehen", gemerkt)
        assertTrue("Der Bezugspunkt ist der BEGINN dieses Laufs", gemerkt!! >= vorher)
        assertEquals(
            "Ohne mitgelieferten Abruf-Horizont gilt die eingestellte Vorausschau (Fake: 14)",
            14,
            horizonStore.gemerktesFenster
        )
    }

    /**
     * #51, VERGROESSERN: Der Nutzer stellt von 14 auf 56 Tage. Der folgende Sync sieht auf einen
     * Schlag sechs weitere Wochen Dienstplan - keine davon ist eine Aenderung des Chefs. Weil der
     * Merker sein GESPEICHERTES Fenster (14) kennt, gelten sie als Horizont-Eintritt: Wecker ja,
     * Meldung nein. Fortgeschrieben wird danach das Fenster des tatsaechlichen Abrufs (56).
     */
    @Test
    fun `vergroesserte Vorausschau meldet die neu sichtbaren Wochen nicht`() = runTest {
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val horizonStore = FakeSyncHorizonStore(letzterSync = gestern(), fensterTage = 14)
        val tag = 24L * 60 * 60 * 1000
        val syncBeginn = System.currentTimeMillis()

        val result = useCase(repo, manager, notifier, horizonStore).syncAlarms(
            listOf(
                eventInTagen("evWoche3", 20, titel = "F Dienst"),
                eventInTagen("evWoche7", 45),
                eventInTagen("evBestand", 2)
            ),
            config,
            abrufHorizontEnde = syncBeginn + 56 * tag
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "Neu sichtbar durch die groessere Vorausschau ist KEINE neue Schicht",
            emptyList<String>(),
            notifier.gemeldetNeu
        )
        assertNotNull("Der Wecker in Woche 3 steht trotzdem", repo.current.find { it.eventId == "evWoche3" })
        assertNotNull("Der Wecker in Woche 7 steht trotzdem", repo.current.find { it.eventId == "evWoche7" })
        assertEquals("Fortgeschrieben wird das Fenster des Abrufs", 56, horizonStore.gemerktesFenster)
    }

    /**
     * Gegenprobe zum Vergroessern: der Merker kennt bereits das grosse Fenster (56). Eine Schicht
     * in Woche 3 lag damit beim letzten Lauf schon im Blick - kommt sie neu dazu, ist sie eine
     * ECHTE Nachtragung und wird gemeldet. Das gespeicherte Fenster wirkt in beide Richtungen.
     */
    @Test
    fun `mit gespeichertem grossem Fenster wird eine Nachtragung in Woche 3 gemeldet`() = runTest {
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val horizonStore = FakeSyncHorizonStore(letzterSync = gestern(), fensterTage = 56)
        val tag = 24L * 60 * 60 * 1000

        useCase(repo, manager, notifier, horizonStore).syncAlarms(
            listOf(eventInTagen("evNachtrag", 20, titel = "F Dienst"), eventInTagen("evBestand", 2)),
            config,
            abrufHorizontEnde = System.currentTimeMillis() + 56 * tag
        )

        assertEquals(listOf("Frueh"), notifier.gemeldetNeu)
    }

    // --- Die reine Funktion, ohne Sync-Maschinerie ---

    @Test
    fun `istHorizontEintritt - ohne Bezugspunkt gilt nichts als Horizont-Eintritt`() {
        val jetzt = System.currentTimeMillis()
        assertFalse(
            "null (kein Merker oder Lesefehler) muss zur Meldung fuehren",
            SyncHorizonStore.istHorizontEintritt(null, jetzt + 20L * 24 * 60 * 60 * 1000)
        )
    }

    private fun merker(syncAt: Long, fensterTage: Int = 14) = SyncHorizonStore.SyncMerker(syncAt, fensterTage)

    @Test
    fun `istHorizontEintritt - zwei Tage Pause lassen mehrere Tage auf einmal still hereinrutschen`() {
        val jetzt = System.currentTimeMillis()
        val tag = 24L * 60 * 60 * 1000
        val vorZweiTagen = jetzt - 2 * tag

        // Bekannt war bis vorZweiTagen + 14 Tage = jetzt + 12 Tage.
        assertFalse(
            "Was schon bekannt war, ist kein Horizont-Eintritt",
            SyncHorizonStore.istHorizontEintritt(merker(vorZweiTagen), jetzt + 5 * tag, jetzt)
        )
        assertTrue(
            "Tag 13 und 14 rutschen erst jetzt herein - still",
            SyncHorizonStore.istHorizontEintritt(merker(vorZweiTagen), jetzt + 13 * tag, jetzt)
        )
    }

    @Test
    fun `istHorizontEintritt - ein zu alter Merker zaehlt wie keiner, also wird gemeldet`() {
        // DER BEFUND: Der Merker wird nur nach einem VOLLSTAENDIGEN Sync fortgeschrieben - die
        // vier Frueh-Ausstiege (Master-Pause, Auto-Alarm aus, leere Eventliste, kein Treffer)
        // erreichen ihn gar nicht. Nach zwei Wochen Urlaub mit aktiver Master-Pause unterdrueckte
        // ein veralteter Merker die Meldung fuer ALLES, was weiter als (14 - Alter) Tage voraus
        // liegt - der komplette neu eingetragene Dienstplan waere lautlos gewesen.
        val jetzt = System.currentTimeMillis()
        val tag = 24L * 60 * 60 * 1000
        val schichtBeginn = jetzt + 13 * tag

        assertTrue(
            "An der Altersgrenze gilt der Merker noch",
            SyncHorizonStore.istHorizontEintritt(
                merker(jetzt - SyncHorizonStore.maxMerkerAlterMs(14)),
                schichtBeginn,
                jetzt
            )
        )
        assertFalse(
            "Eine Millisekunde darueber zaehlt er wie keiner - es wird gemeldet",
            SyncHorizonStore.istHorizontEintritt(
                merker(jetzt - SyncHorizonStore.maxMerkerAlterMs(14) - 1),
                schichtBeginn,
                jetzt
            )
        )
        assertFalse(
            "Und erst recht nach zwei Wochen Pause",
            SyncHorizonStore.istHorizontEintritt(merker(jetzt - 14 * tag), schichtBeginn, jetzt)
        )
    }

    @Test
    fun `nach langer Pause wird der neue Dienstplan gemeldet statt verschwiegen`() = runTest {
        // Dasselbe im ganzen Sync: der letzte vollstaendige Lauf ist zehn Tage her (Master-Pause
        // im Urlaub - die schreibt den Merker gar nicht fort). Ohne Altersgrenze laege der
        // errechnete Alt-Horizont bei jetzt + 4 Tagen, und die Fruehschicht in 13 Tagen waere
        // stillschweigend unter den Tisch gefallen.
        val repo = FakeAlarmRepository(listOf(bestandsAlarm("evBestand")))
        val manager = mockManager()
        val notifier = FakeShiftChangeNotifier()
        val zehnTage = 10L * 24 * 60 * 60 * 1000
        val horizonStore = FakeSyncHorizonStore(letzterSync = System.currentTimeMillis() - zehnTage)

        val result = useCase(repo, manager, notifier, horizonStore).syncAlarms(
            listOf(eventInTagen("evNeu", 13, titel = "F Dienst"), eventInTagen("evBestand", 2)),
            config
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "Ein zehn Tage alter Merker darf den neuen Dienstplan nicht verschweigen",
            listOf("Frueh"),
            notifier.gemeldetNeu
        )
    }

    @Test
    fun `horizontEndeFuer - der Horizont ist genau das GESPEICHERTE Fenster nach dem Sync`() {
        val syncAt = 1_700_000_000_000L
        val tag = 24L * 60 * 60 * 1000
        assertEquals(syncAt + 14 * tag, SyncHorizonStore.horizontEndeFuer(merker(syncAt, 14)))
        assertEquals(
            "Mit 56 Tagen gespeichert reicht er 56 Tage - nicht die aktuelle Einstellung",
            syncAt + 56 * tag,
            SyncHorizonStore.horizontEndeFuer(merker(syncAt, 56))
        )
    }

    /** Das Hoechstalter waechst mit dem GESPEICHERTEN Fenster: halbe Fensterbreite. */
    @Test
    fun `maxMerkerAlterMs ist das halbe gespeicherte Fenster`() {
        val tag = 24L * 60 * 60 * 1000
        assertEquals(7 * tag, SyncHorizonStore.maxMerkerAlterMs(14))
        assertEquals(3 * tag + tag / 2, SyncHorizonStore.maxMerkerAlterMs(7))
        assertEquals(28 * tag, SyncHorizonStore.maxMerkerAlterMs(56))

        // Ein 56-Tage-Merker von vor 20 Tagen gilt noch (bei 14 Tagen waere er laengst verfallen).
        val jetzt = System.currentTimeMillis()
        assertTrue(
            SyncHorizonStore.istHorizontEintritt(merker(jetzt - 20 * tag, 56), jetzt + 40 * tag, jetzt)
        )
        assertFalse(
            SyncHorizonStore.istHorizontEintritt(merker(jetzt - 20 * tag, 14), jetzt + 40 * tag, jetzt)
        )
    }

    /** Das Fenster wird aus dem Abruf-Horizont auf ganze Tage gerundet - nie kleiner als 1. */
    @Test
    fun `fensterTageFuer rundet auf ganze Tage`() {
        val tag = 24L * 60 * 60 * 1000
        val syncAt = 1_700_000_000_000L
        assertEquals(14, SyncHorizonStore.fensterTageFuer(syncAt, syncAt + 14 * tag - 30_000L))
        assertEquals(28, SyncHorizonStore.fensterTageFuer(syncAt, syncAt + 28 * tag))
        assertEquals(
            "Eine drei Stunden alte Liste deckt trotzdem ~14 Tage ab",
            14,
            SyncHorizonStore.fensterTageFuer(syncAt, syncAt + 14 * tag - 3 * 60 * 60 * 1000L)
        )
        assertEquals(1, SyncHorizonStore.fensterTageFuer(syncAt, syncAt - tag))
    }
}
