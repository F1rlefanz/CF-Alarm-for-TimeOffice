package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die Freigabe wurde MITTEN IM VORGANG zurueckgenommen, weil sich ein Wecker des Tages nicht
 * loeschen liess.
 *
 * Zustand, den der Aufrufer danach vorfindet: der Eintrag liegt noch im Bestand, sein Systemalarm
 * ist aber schon gecancelt - ein sichtbarer, stummer Wecker, die schlimmste Klasse ueberhaupt.
 * Deshalb tragen [alarmIds] und [freigabeZurueckgenommen] den Fehler mit: der Aufrufer muss die
 * Systemalarme wieder stellen, und das kommt nur durch, wenn die Freigabe wirklich weg ist -
 * sonst weist sie der Backstop in `AlarmUseCase.scheduleSystemAlarm()` ab.
 *
 * Wortgleiche Rolle wie `SkipRolledBackException`; bewusst eine eigene Klasse, weil der Aufrufer
 * beide Faelle unterschiedlich beschriftet.
 */
class FreigabeZurueckgenommenException(
    val datum: LocalDate,
    val alarmIds: List<Int>,
    val freigabeZurueckgenommen: Boolean,
    cause: Throwable?
) : Exception(
    "Freigabe fuer $datum zurueckgenommen: ein Wecker des Tages liess sich nicht loeschen",
    cause
)

/** Was eine erfolgreiche Freigabe bewirkt hat - fuer die Rueckmeldung in der Oberflaeche. */
data class TagFreigabeResult(
    val datum: LocalDate,
    val geloeschteWecker: Int,
    val ueberspringenAufgehoben: Boolean
)

/**
 * "Tag freigeben": ein Kalendertag, an dem laut Dienstplan Dienst waere, findet nicht statt.
 *
 * **Abgrenzung zum Ueberspringen, und warum es beides braucht.** `AlarmSkipUseCase` schaltet den
 * naechsten WECKER ab und laesst den Dienst bestehen - "Nicht stoeren" und der Schicht-Dimmer
 * richten sich weiter nach der Schicht (gedacht fuer den Morgen, an dem man ohne Wecker wach ist).
 * Hier faellt der DIENST weg, und damit alles, was an ihm haengt:
 *
 * - **Wecker**: die Alarme des Tages werden geloescht und entstehen nicht neu (Gate und Backstop
 *   in `AlarmUseCase`).
 * - **"Nicht stoeren"** und **Schicht-Dimmer**: `FreieTageStore.filtereSpannen` nimmt die
 *   Schichtspannen des Tages aus beiden Fensterquellen; der Tag verhaelt sich danach wie jeder
 *   andere freie Tag (FREI-Regel, Nacht-Standard).
 * - **Hue**: braucht keinen eigenen Zweig. `HueSmartScheduler` zieht seine Zeiten ausschliesslich
 *   aus `getAllAlarms()`, und die Regelausfuehrung haengt am `AlarmReceiver` - ohne Wecker kein
 *   Sonnenaufgang und kein Licht.
 *
 * Der Kalendertermin bleibt unangetastet. Die Freigabe ist eine Aussage des NUTZERS ueber den
 * Dienstplan, kein Abbild des Dienstplans - deshalb liegt sie in einem eigenen Speicher und nicht
 * als Feld an der Spanne (die wird bei jedem Sync vollstaendig ersetzt).
 */
@Singleton
class TagFreigabeUseCase @Inject constructor(
    private val store: FreieTageStore,
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService,
    private val alarmSkipUseCase: IAlarmSkipUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val dndSchedule: DndScheduleUseCase
) {
    companion object {
        /** Loeschversuche je Wecker, inklusive des ersten - Vorbild `AlarmSkipUseCase`. */
        internal const val DELETE_ATTEMPTS = 2
        internal const val DELETE_RETRY_DELAY_MS = 250L

        /**
         * Gehoert dieser Wecker zum freigegebenen Tag?
         *
         * **Anker ist die Weckzeit**, dieselbe Wahl wie in `FreieTageStore.tagVon` fuer die
         * Schichtspannen. Waeren es hier der Schichtbeginn und dort die Weckzeit, gaebe es bei
         * einer Schicht, deren Wecker vor Mitternacht und deren Dienst danach liegt, einen Tag,
         * an dem der Wecker geloescht ist, "Nicht stoeren" aber trotzdem laeuft.
         */
        internal fun gehoertZuTag(alarm: AlarmInfo, datum: LocalDate, zone: ZoneId): Boolean {
            // MANUELLE WECKER BLEIBEN STEHEN (`eventId.isEmpty()`). Eine Freigabe streicht den
            // DIENST, nicht die eigenen Wecker des Nutzers - und ein geloeschter manueller Wecker
            // kommt nie zurueck: `zuruecknehmen()` baut ueber den Kalender wieder auf, und in
            // keinem Kalender steht er. Dieselbe Schonung haelt jede andere Raeumstelle des
            // Projekts (`clearInternalAlarms(keepManualAlarms)`, der Loeschzweig in `syncAlarms`),
            // und `AlarmUseCase.istTagFreigegeben` nimmt sie aus demselben Grund ebenfalls aus.
            if (alarm.eventId.isEmpty()) return false
            return Instant.ofEpochMilli(alarm.triggerTime).atZone(zone).toLocalDate() == datum
        }
    }

    val freieTage: Flow<Set<LocalDate>> = store.freieTage

    suspend fun freieTageNow(): Set<LocalDate> = store.freieTageNow()

    /**
     * Gibt [datum] frei: Markierung setzen, Wecker des Tages abraeumen, ein kollidierendes
     * Ueberspringen aufheben, Neben-Ketten neu anwerfen.
     *
     * Die Schrittfolge ist die von `AlarmSkipUseCase.skipNextAlarm()`, und zwar aus denselben
     * Gruenden - siehe die Begruendungen an den einzelnen Schritten.
     */
    suspend fun freigeben(datum: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Result<TagFreigabeResult> {
        // Wie beim Ueberspringen: die Ruecknahme muss den Aufrufer MIT IHRER IDENTITAET erreichen.
        // SafeExecutor verwandelt jede Exception in einen AppError - `alarmIds` und
        // `freigabeZurueckgenommen` waeren unterwegs verloren, also genau die Angaben, mit denen
        // das ViewModel die stumm gewordenen Wecker wieder stellt.
        var ruecknahme: FreigabeZurueckgenommenException? = null
        val ergebnis = SafeExecutor.safeExecute("TagFreigabeUseCase.freigeben") {
            // 1. Ist der Bestand gar nicht speicherbar, kann diese Freigabe nicht gelingen - und
            // zwar STILL: `deleteAlarm()` raeumt dann nur den Arbeitsspeicher und meldet trotzdem
            // Erfolg, waehrend Preferences-Datei und Direct-Boot-Spiegel den Wecker weitertragen.
            // Also abbrechen, BEVOR Markierung und Systemalarme angefasst sind. Der Wecker bleibt
            // stehen und klingelt - das ist die sichere Richtung.
            if (alarmRepository.isPersistenceBlocked()) {
                throw IllegalStateException(
                    "Tag freigeben nicht moeglich: der Alarm-Bestand laesst sich gerade nicht " +
                        "speichern - die Wecker dieses Tages bleiben bestehen und klingeln"
                )
            }

            val betroffene = alarmRepository.getAllAlarms().getOrThrow()
                .filter { gehoertZuTag(it, datum, zone) }

            // Ab hier NICHT MEHR ABBRECHBAR - dieselbe Lage wie bei `skipNextAlarm()` und
            // `pause()`/`resume()`: die Schritte stellen einen Zustand HER, und zwischen ihnen
            // liegt mit dem Nachfass-Warten ein echter Abbruchpunkt. Ein Abbruch dazwischen
            // hinterliesse gesetzte Markierung, gecancelte Systemalarme und Eintraege im Bestand.
            var ueberspringenAufgehoben = false
            withContext(NonCancellable) {
                // 2. Markierung zuerst. Faellt der Prozess danach, ist der schlimmste Zustand ein
                // freigegebener Tag, dessen Wecker noch steht - der naechste Sync raeumt ihn ueber
                // das Gate weg. Umgekehrt (erst loeschen, dann markieren) waere derselbe Absturz
                // ein spurlos verschwundener Wecker, den nichts wiederherstellt.
                store.freigeben(datum)

                // 3. Je Wecker: erst cancelSystemAlarm, dann deleteAlarm. DIE REIHENFOLGE IST
                // PFLICHT - umgekehrt entsteht ein armierter Alarm, den weder Bestand noch
                // Direct-Boot-Spiegel kennen: unsichtbar UND unabbrechbar bis zum Neustart.
                for (alarm in betroffene) {
                    try {
                        alarmManagerService.cancelSystemAlarm(alarm.id)
                    } catch (e: Exception) {
                        // Wie beim Ueberspringen nur geloggt: ein fehlgeschlagenes Cancel darf das
                        // Loeschen nicht verhindern. Der Gegenbeweis kaeme sonst nie zustande.
                        Logger.e(LogTags.ALARM, "❌ FREIGABE: Systemalarm ${alarm.id} nicht abbrechbar", e)
                    }

                    val geloescht = loescheMitNachfassen(alarm.id)
                    if (geloescht.isFailure) {
                        // 4. Ein Wecker, der nicht weggeht, macht die ganze Freigabe zur Luege:
                        // der Direct-Boot-Spiegel armiert ihn nach einem naechtlichen Neustart
                        // ungefiltert wieder, und die Oberflaeche sagt "freigegeben". Also
                        // zuruecknehmen und laut scheitern - dieselbe Entscheidung, die beim
                        // Ueberspringen seit v1.28.0 gilt.
                        val zurueck = runCatching { store.zuruecknehmen(datum) }
                        if (zurueck.isFailure) {
                            Logger.e(
                                LogTags.ALARM,
                                "❌ FREIGABE: Ruecknahme der Markierung fuer $datum ebenfalls fehlgeschlagen",
                                zurueck.exceptionOrNull()
                            )
                        }
                        val abgebrochen = FreigabeZurueckgenommenException(
                            datum = datum,
                            alarmIds = betroffene.map { it.id },
                            freigabeZurueckgenommen = zurueck.isSuccess,
                            cause = geloescht.exceptionOrNull()
                        )
                        ruecknahme = abgebrochen
                        throw abgebrochen
                    }
                }

                // 5. Ein Ueberspringen desselben Tages ist jetzt gegenstandslos - die Freigabe ist
                // die staerkere Aussage. Bliebe es stehen, muesste der Nutzer zwei Zustaende
                // zuruecknehmen, um einen Wecker wiederzubekommen, und der zweite waere unsichtbar,
                // sobald der erste weg ist.
                if (betrifftSkipDiesenTag(datum, zone)) {
                    alarmSkipUseCase.cancelSkip()
                        .onFailure { Logger.w(LogTags.ALARM, "⚠️ FREIGABE: Ueberspringen liess sich nicht aufheben", it) }
                        .onSuccess { ueberspringenAufgehoben = true }
                }
            }

            // 6. Neben-Ketten neu anwerfen: Dimmer und DND rechnen ihre Fenster erst beim naechsten
            // Tick neu, und der kann Stunden entfernt liegen. Ohne das bliebe "Nicht stoeren"
            // waehrend der gerade freigegebenen Schicht weiter an - genau der gemeldete Vorfall.
            // Vorbild: `AlarmMaintenanceService.rescheduleSideChannels`.
            //
            // EBENFALLS NICHT ABBRECHBAR, und zwar aus genau diesem Grund: schliesst der Nutzer die
            // App direkt nach dem Tippen (der Normalfall - man gibt frei und legt das Handy weg),
            // stirbt der viewModelScope hier. Die Markierung stuende dann, die Wecker waeren weg,
            // und "Nicht stoeren" liefe bis zum naechsten Tick weiter - also genau der Zustand,
            // gegen den diese ganze Funktion gebaut ist.
            withContext(NonCancellable) { werfeNebenkettenAn() }

            TagFreigabeResult(
                datum = datum,
                geloeschteWecker = betroffene.size,
                ueberspringenAufgehoben = ueberspringenAufgehoben
            )
        }
        return ruecknahme?.let { Result.failure(it) } ?: ergebnis
    }

    /**
     * Nimmt die Freigabe zurueck. Die Wecker baut der Aufrufer ueber einen Kalender-Refresh neu
     * auf - denselben Weg geht `cancelSkip` im `AlarmViewModel`. Von hier aus ginge es nicht:
     * `AlarmUseCase` haengt fuer sein Gate bereits an diesem UseCase, die Gegenrichtung waere ein
     * Zyklus im DI-Graphen.
     */
    suspend fun zuruecknehmen(datum: LocalDate): Result<Unit> =
        SafeExecutor.safeExecute("TagFreigabeUseCase.zuruecknehmen") {
            withContext(NonCancellable) {
                store.zuruecknehmen(datum)
                werfeNebenkettenAn()
            }
        }

    /**
     * Der Ueberspringen-Merker kennt nur eine Weckzeit, kein Datum - der Vergleich laeuft deshalb
     * ueber den Kalendertag der gemerkten Weckzeit. `AlarmSkipState.skippedAlarmTriggerTime` ist
     * `0`, solange nichts uebersprungen ist.
     */
    private suspend fun betrifftSkipDiesenTag(datum: LocalDate, zone: ZoneId): Boolean {
        // Ein nicht lesbarer Merker ist hier kein Grund zu scheitern: die Freigabe steht, der
        // Wecker ist weg, und ein stehengebliebener Merker laeuft ohnehin zeitbasiert ab.
        val zustand = alarmSkipUseCase.getSkipStatus().getOrElse { fehler ->
            Logger.w(LogTags.ALARM, "⚠️ FREIGABE: Ueberspringen-Zustand nicht lesbar", fehler)
            return false
        }
        return zustand.isNextAlarmSkipped &&
            zustand.skippedAlarmTriggerTime > 0L &&
            Instant.ofEpochMilli(zustand.skippedAlarmTriggerTime).atZone(zone).toLocalDate() == datum
    }

    private suspend fun werfeNebenkettenAn() {
        runCatching {
            dimSchedule.applyCurrentState()
            dimSchedule.scheduleNextTransition()
        }.onFailure { Logger.w(LogTags.DIMMER, "⚠️ FREIGABE: Dimmer-Kette nicht neu angeworfen", it) }
        runCatching {
            dndSchedule.applyCurrentState()
            dndSchedule.scheduleNextTransition()
        }.onFailure { Logger.w(LogTags.DND, "⚠️ FREIGABE: DND-Kette nicht neu angeworfen", it) }
    }

    /**
     * Loescht einen Wecker und fasst bei einem Fehlschlag genau einmal nach - Wortlaut und
     * Begruendung wie `AlarmSkipUseCase.loescheMitNachfassen`: der haeufige Fall ist ein
     * voruebergehender DataStore-Schreibfehler.
     */
    private suspend fun loescheMitNachfassen(alarmId: Int): Result<Unit> {
        var ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId)
        var versuch = 1
        while (ergebnis.isFailure && versuch < DELETE_ATTEMPTS) {
            Logger.w(
                LogTags.ALARM,
                "⚠️ FREIGABE: Loeschen von Wecker $alarmId fehlgeschlagen " +
                    "(Versuch $versuch/$DELETE_ATTEMPTS) - wird wiederholt",
                ergebnis.exceptionOrNull()
            )
            delay(DELETE_RETRY_DELAY_MS)
            ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId)
            versuch++
        }
        return ergebnis
    }

    /**
     * `deleteAlarm()` meldet auch dann Erfolg, wenn nur der Arbeitsspeicher geraeumt wurde (bei
     * gesperrter Persistenz kehrt `persistToDataStore()` sofort zurueck). Ohne diese Nachfrage
     * spraenge die Ruecknahme genau im wichtigsten Fall nicht an. Die Sperre kann auch erst nach
     * der Vorpruefung entstehen, deshalb wird sie hier erneut gefragt.
     */
    private suspend fun loescheUndPruefeDauerhaftigkeit(alarmId: Int): Result<Unit> {
        val ergebnis = alarmRepository.deleteAlarm(alarmId)
        if (ergebnis.isFailure) return ergebnis
        return if (alarmRepository.isPersistenceBlocked()) {
            Result.failure(
                IllegalStateException(
                    "Wecker $alarmId wurde nur aus dem Arbeitsspeicher entfernt - die Persistenz " +
                        "ist gesperrt, Alarm-Bestand und Direct-Boot-Spiegel behalten ihn"
                )
            )
        } else {
            ergebnis
        }
    }
}
