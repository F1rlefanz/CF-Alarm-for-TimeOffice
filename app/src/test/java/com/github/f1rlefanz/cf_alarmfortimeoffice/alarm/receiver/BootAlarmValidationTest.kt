package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import com.github.f1rlefanz.cf_alarmfortimeoffice.Kerntest
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Boot-Recovery darf nicht auf einem veralteten Alarm-Snapshot loeschen.
 *
 * SZENARIO (Grund fuer diese Absicherung): Beim Boot laufen seit dem Wartungslauf-Sicherheitsnetz
 * ZWEI unabhaengige Sync-Pipelines. `performAlarmRecovery()` liest seinen Alarmbestand einmal oben,
 * holt danach Kalender-Events (Sekunden) und entscheidet erst dann pro Alarm. Korrigiert der
 * parallele Wartungslauf in diesem Fenster eine verschobene Schicht (gleiche `id`, neuer
 * `eventChecksum`) und stellt den System-Alarm korrekt, sah die Recovery danach ihren ALTEN
 * Checksum gegen den frischen Event-Checksum, erkannte einen Mismatch und loeschte den gerade
 * korrigierten Alarm - ohne ihn neu anzulegen. Der Wecker war bis zur naechsten 6h-Wartung weg.
 */
class BootAlarmValidationTest {

    @Test
    fun `unveraenderter Alarm darf normal validiert werden`() {
        assertTrue(BootAlarmValidation.snapshotStillCurrent("chk-1", "chk-1"))
    }

    @Test
    fun `parallel neu geschriebener Alarm blockiert die Entscheidung auf altem Stand`() {
        // Der Wartungslauf hat denselben Alarm korrigiert - der Snapshot ist wertlos.
        assertFalse(BootAlarmValidation.snapshotStillCurrent("chk-alt", "chk-neu"))
    }

    @Test
    fun `parallel geloeschter Alarm wird nicht mehr angefasst`() {
        // null = nicht mehr im Repository. Wuerde hier weiter entschieden, koennte der Alarm als
        // verwaister System-Alarm wieder auferstehen (Re-Arming eines geloeschten Eintrags).
        assertFalse(BootAlarmValidation.snapshotStillCurrent("chk-1", null))
    }

    @Test
    fun `leerer Checksum ist kein Freifahrtschein`() {
        // Alte Alarme ohne Checksum: gleich bleibt gleich, verschieden bleibt verschieden -
        // die Funktion darf hier keinen Sonderfall erfinden.
        assertTrue(BootAlarmValidation.snapshotStillCurrent("", ""))
        assertFalse(BootAlarmValidation.snapshotStillCurrent("", "chk-neu"))
        assertFalse(BootAlarmValidation.snapshotStillCurrent("", null))
    }
}

/**
 * Ein Kennungswechsel im abonnierten Dienstplan-Feed ist KEINE geloeschte Schicht.
 *
 * SZENARIO (am Geraet belegt, 20.08.2026): Google liest den abonnierten TimeOffice-ICS-Feed alle
 * paar Tage neu ein und vergibt dabei ALLEN Terminen neue Event-IDs - bei voellig unveraenderten
 * Schichten und Weckzeiten (11 Wecker geloescht, 11 neu angelegt, Schnittmenge der Event-IDs leer).
 * Zwischen so einem Neueinlesen und dem naechsten vollstaendigen Sync (bis zu 6 h) tragen ALLE
 * gespeicherten Alarme Kennungen, die es im Kalender nicht mehr gibt. `performAlarmRecovery()`
 * kannte bisher nur "Kennung nicht gefunden = Termin geloescht" und raeumte bei einem Neustart in
 * diesem Fenster den gesamten Bestand samt Systemweckern ab - ohne Neuanlage. Kam der Sync im
 * selben Boot-Ablauf nicht durch (kein Netz nach dem Neustart, unvollstaendige Kalenderliste),
 * war der Nutzer ohne Wecker.
 */
class BootAlarmKennungswechselTest {

    private val fruehdienst = BootAlarmValidation.Weckpunkt(triggerTime = 4_000L, shiftId = "frueh")
    private val spaetdienst = BootAlarmValidation.Weckpunkt(triggerTime = 9_000L, shiftId = "spaet")
    private val lesung = setOf(fruehdienst, spaetdienst)

    @Category(Kerntest::class)
    @Test
    fun `neue Kennung bei gleicher Weckzeit und Schicht loescht den Wecker nicht`() {
        // Genau der belegte Fall: der Termin ist da, nur unter neuer Event-ID.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Category(Kerntest::class)
    @Test
    fun `der ganze Bestand ueberlebt ein Feed-Neueinlesen`() {
        // 11 Alarme, 11 neue Kennungen - kein einziger darf geloescht werden.
        val bestand = (1..11).map { i -> BootAlarmValidation.Weckpunkt(i * 1_000L, "schicht-$i") }
        val urteile = bestand.map { alarm ->
            BootAlarmValidation.beurteile(
                alarmTriggerTime = alarm.triggerTime,
                alarmShiftId = alarm.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = bestand.toSet()
            )
        }
        assertTrue(urteile.all { it == BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG })
    }

    @Test
    fun `wirklich gestrichene Schicht wird weiterhin geloescht`() {
        // Der Kennungs-Freibrief darf keine echte Streichung verdecken.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 20_000L,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `gleiche Weckzeit aber andere Schicht ist kein Kennungswechsel`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `gleiche Schicht aber verschobene Weckzeit ist kein Kennungswechsel`() {
        // Eine verschobene Schicht ist eine echte Aenderung - der Sync legt sie neu an.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime + 1,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Category(Kerntest::class)
    @Test
    fun `ohne auswertbare Schichterkennung wird nie wegen fehlender Kennung geloescht`() {
        // weckpunkte == null: die Schichterkennung ist gescheitert. "Im Zweifel wecken" - lieber
        // ein Wecker zu viel (er klingelt hoerbar) als ein geloeschter Bestand (der ist still).
        assertEquals(
            BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 20_000L,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = null
            )
        )
    }

    @Test
    fun `bestehende Kennung mit geaendertem Termin wird weiterhin geloescht`() {
        // Unveraendertes Verhalten: gleiche Kennung, anderer Inhalt.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = "chk-neu",
                weckpunkte = lesung
            )
        )
    }

    @Category(Kerntest::class)
    @Test
    fun `unveraenderter Termin wird ganz normal wiederhergestellt`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.WIEDERHERSTELLEN,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-1",
                terminChecksum = "chk-1",
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `der Checksum-Vergleich braucht die Weckpunkte nicht`() {
        // Die Kennung ist da - dann entscheidet allein der Inhalt, auch wenn die Schichterkennung
        // gescheitert ist (weckpunkte == null). Sonst wuerde ein Fehler der Schichterkennung das
        // Erkennen echter Terminaenderungen mit abschalten.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = "chk-neu",
                weckpunkte = null
            )
        )
    }
}

/**
 * #51: Nach einer VERKLEINERTEN Vorausschau ist die Kalenderlesung vollstaendig - aber nur fuer
 * das kleinere Fenster. Ein gespeicherter Wecker dahinter hat in der Lesung weder Kennung noch
 * Weckpunkt; ohne die Horizont-Frage galt er als "Termin weg" und ein Neustart raeumte alle Wecker
 * hinter dem neuen Fenster ab. Dieselbe Regel wie `AlarmUseCase.syncAlarms()` Schritt 1.
 */
class BootAlarmHorizontTest {

    private val lesung = setOf(BootAlarmValidation.Weckpunkt(triggerTime = 4_000L, shiftId = "frueh"))
    private val horizont = 100_000L

    @Category(Kerntest::class)
    @Test
    fun `Wecker hinter dem Abruf-Horizont bleibt - er wurde nur nicht gelesen`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.JENSEITS_DES_ABRUFS,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 150_000L,
                alarmShiftId = "spaet",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung,
                alarmSchichtBeginn = 160_000L,
                abrufHorizontEnde = horizont
            )
        )
    }

    @Test
    fun `Grenze - ein Schichtbeginn genau auf dem Horizont ist nicht gelesen`() {
        // Die Kalender-API filtert timeMax EXKLUSIV auf den Terminbeginn.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.JENSEITS_DES_ABRUFS,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = horizont - 1_000L,
                alarmShiftId = "spaet",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung,
                alarmSchichtBeginn = horizont,
                abrufHorizontEnde = horizont
            )
        )
    }

    @Test
    fun `innerhalb des Horizonts bleibt eine fehlende Schicht eine Streichung`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 50_000L,
                alarmShiftId = "spaet",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung,
                alarmSchichtBeginn = 60_000L,
                abrufHorizontEnde = horizont
            )
        )
    }

    @Test
    fun `entscheidend ist der Schichtbeginn, nicht die Weckzeit`() {
        // Weckzeit vor, Schichtbeginn hinter dem Horizont: die Abfrage filtert nach dem Beginn -
        // der Termin konnte nicht in der Lesung stehen.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.JENSEITS_DES_ABRUFS,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = horizont - 10_000L,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung,
                alarmSchichtBeginn = horizont + 10_000L,
                abrufHorizontEnde = horizont
            )
        )
    }

    @Test
    fun `ohne bekannten Horizont gilt das bisherige Urteil`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 150_000L,
                alarmShiftId = "spaet",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung,
                alarmSchichtBeginn = 160_000L,
                abrufHorizontEnde = null
            )
        )
    }

    @Test
    fun `ein vorhandener Termin wird auch hinter dem Horizont normal beurteilt`() {
        // Steht die Kennung in der Lesung, war der Termin gelesen - der Inhaltsvergleich gilt.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 150_000L,
                alarmShiftId = "spaet",
                alarmChecksum = "chk-alt",
                terminChecksum = "chk-neu",
                weckpunkte = lesung,
                alarmSchichtBeginn = 160_000L,
                abrufHorizontEnde = horizont
            )
        )
    }
}

/**
 * #182: Ein Neustart behaelt die Wecker an Tagen ohne Kalendereintrag.
 *
 * SZENARIO: "Ohne Kalendereintrag" wie <Typ> stellt an einem leeren Tag einen Wecker mit der
 * Kennung `ohne-eintrag:<Datum>` (#175). So eine Kennung steht nie in der Kalenderlesung, und ihr
 * Weckpunkt auch nicht - es gibt keinen Termin. Die Boot-Validierung urteilte deshalb bei jedem
 * Neustart mit vollstaendigem Abruf "Termin weg" und loeschte den Wecker. Zurueck kam er nur, wenn
 * der parallele Wartungs-Anker NACH dem Loeschen synchronisierte; sonst fehlte er bis zur naechsten
 * 6-h-Wartung, im schlimmsten Fall der Wecker des naechsten Morgens.
 *
 * Ob der Tag noch leer ist, entscheidet allein `syncAlarms()` (`tageOhneEintrag`); die
 * Boot-Validierung urteilt ueber diese Wecker nicht, sondern stellt sie wieder her.
 */
class BootAlarmOhneEintragTest {

    private val jetzt = System.currentTimeMillis()

    /** Der Wecker, den "Ohne Kalendereintrag" wie Frei an einem leeren Tag gestellt hat. */
    private val ohneEintrag = AlarmUseCase.ohneEintragKennung(LocalDate.now().plusDays(2)).let { kennung ->
        AlarmInfo(
            id = kennung.hashCode(),
            shiftId = "frei",
            shiftName = "Frei",
            triggerTime = jetzt + 2 * TAG_MS,
            formattedTime = "08:00",
            eventId = kennung,
            eventChecksum = ""
        )
    }

    /** Ein echter Schicht-Wecker, dessen Termin aus dem Kalender gestrichen ist. */
    private val gestrichen = AlarmInfo(
        id = 4711,
        shiftId = "frueh",
        shiftName = "Fruehdienst",
        triggerTime = jetzt + TAG_MS,
        formattedTime = "05:00",
        eventId = "evtgestrichen",
        eventChecksum = "chk-alt",
        shiftStartTime = jetzt + TAG_MS + STUNDE_MS
    )

    /**
     * Vollstaendige, nicht leere Lesung - die Validierung ist also aktiv. Sie enthaelt einen
     * anderen Termin, keinen der beiden Wecker oben, und keinen bekannten Horizont (sonst koennte
     * der gestrichene Wecker als "nicht gelesen" durchrutschen).
     */
    private val lesung = LocalDateTime.now().plusDays(3).let { beginn ->
        CalendarFetchOutcome(
            events = listOf(
                CalendarEvent(
                    id = "evtanderer",
                    title = "S",
                    startTime = beginn,
                    endTime = beginn.plusHours(8),
                    calendarId = "dienstplan"
                )
            ),
            requestedCalendars = 1,
            horizontEnde = null
        )
    }

    /**
     * @param bestandBeimNachlesen was die Frisch-Nachlese pro Wecker sieht - der parallele
     *   Wartungs-Anker kann den Bestand zwischen Snapshot und Entscheidung geaendert haben.
     */
    private fun receiver(
        bestand: List<AlarmInfo>,
        bestandBeimNachlesen: List<AlarmInfo> = bestand
    ): BootReceiver {
        val r = BootReceiver()
        r.alarmUseCase = mock<IAlarmUseCase> {
            onBlocking { getAllAlarms() }.doReturn(
                Result.success(bestand),
                Result.success(bestandBeimNachlesen)
            )
            onBlocking { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
            onBlocking { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        r.calendarSelectionRepository = mock<CalendarSelectionRepository> {
            onBlocking { getCurrentSelectedCalendarIds() } doReturn Result.success(setOf("dienstplan"))
        }
        r.calendarUseCase = mock<ICalendarUseCase> {
            onBlocking { getCalendarEventsWithStatus(any(), any()) } doReturn Result.success(lesung)
        }
        r.shiftUseCase = mock<IShiftUseCase> {
            // Erkennung gelingt, findet aber keine der beiden Schichten: Weckpunkte sind eine
            // LEERE Menge, nicht null - nur dann wird wegen fehlender Kennung ueberhaupt geloescht.
            onBlocking { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
            // Schritt 4 (Nachlegen aus dem Kalender) bleibt aus - er ist hier nicht Gegenstand.
            onBlocking { getCurrentShiftConfig() } doReturn Result.failure(IllegalStateException("nicht Gegenstand"))
        }
        return r
    }

    @Test
    fun `Neustart mit vollstaendiger Lesung behaelt den Wecker eines Tages ohne Kalendereintrag`() {
        val r = receiver(bestand = listOf(ohneEintrag, gestrichen))

        runBlocking { r.performAlarmRecoveryPerReflexion() }

        verifyBlocking(r.alarmUseCase) { scheduleSystemAlarm(ohneEintrag) }
        verifyBlocking(r.alarmUseCase, never()) { deleteAlarm(ohneEintrag.id) }
        // Die Ausnahme ist eng: ein echter Wecker, dessen Termin fehlt, wird weiter geloescht.
        verifyBlocking(r.alarmUseCase) { deleteAlarm(gestrichen.id) }
        verifyBlocking(r.alarmUseCase, never()) { scheduleSystemAlarm(gestrichen) }
    }

    @Test
    fun `ein parallel abgeraeumter Ohne-Eintrag-Wecker wird nicht wieder scharf gestellt`() {
        // Anders als ein manueller Wecker gehoert dieser dem Sync: der parallele Wartungs-Anker
        // raeumt ihn ab, sobald der Tag einen Termin hat. Wuerde die Recovery ihn trotzdem aus
        // ihrem alten Snapshot armieren, stuende ein System-Alarm, den das Repository nicht kennt.
        val r = receiver(
            bestand = listOf(ohneEintrag, gestrichen),
            bestandBeimNachlesen = listOf(gestrichen)
        )

        runBlocking { r.performAlarmRecoveryPerReflexion() }

        verifyBlocking(r.alarmUseCase, never()) { scheduleSystemAlarm(ohneEintrag) }
        verifyBlocking(r.alarmUseCase, never()) { deleteAlarm(ohneEintrag.id) }
    }

    /** Wie in `BootReceiverKalenderNachlageTest`: die Recovery bleibt `private`. */
    private suspend fun BootReceiver.performAlarmRecoveryPerReflexion(): String {
        val methode = BootReceiver::class.java
            .getDeclaredMethod("performAlarmRecovery", Continuation::class.java)
            .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn<Any?> { fortsetzung ->
            methode.invoke(this, fortsetzung)
        } as String
    }

    private companion object {
        const val STUNDE_MS = 3_600_000L
        const val TAG_MS = 24 * STUNDE_MS
    }
}
