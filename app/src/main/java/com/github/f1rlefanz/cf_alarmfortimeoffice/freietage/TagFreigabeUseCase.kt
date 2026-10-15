package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.loescheDauerhaftMitNachfassen
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
 * #142: Ergebnis eines Zeitraums. [freigegebenBis] = letzter erfolgreich freigegebener Tag (null =
 * keiner); [fehler] = Fehler des ERSTEN gescheiterten Tags ([FreigabeZurueckgenommenException]
 * bleibt mit Identitaet erhalten - das ViewModel stellt damit die stumm gewordenen Wecker wieder).
 *
 * Bewusst KEIN `Result`: ein Teilerfolg ist weder Erfolg noch Fehlschlag. Die Tage bis
 * [freigegebenBis] SIND freigegeben (Eigentuemer-Entscheidung #142), und die Rueckmeldung muss
 * genau das sagen koennen - ein `Result.failure` verschwiege sie.
 */
data class TagFreigabeZeitraumResult(
    val von: LocalDate,
    val bis: LocalDate,
    val freigegebenBis: LocalDate?,
    val geloeschteWecker: Int,
    val ueberspringenAufgehoben: Boolean,
    val fehler: Throwable?
)

/**
 * "Tag freigeben": ein Kalendertag, an dem laut Dienstplan Dienst waere, findet nicht statt.
 *
 * Anders als `AlarmSkipUseCase` (nur der WECKER faellt weg) faellt hier der DIENST weg und mit ihm
 * alles, was daran haengt: die Wecker des Tages (Gate und Backstop in `AlarmUseCase`), "Nicht
 * stoeren" und Schicht-Dimmer (`FreieTageStore.filtereSpannen`; der Tag verhaelt sich wie jeder
 * freie Tag). Hue braucht keinen eigenen Zweig - es haengt an `getAllAlarms()` und am
 * `AlarmReceiver`. Hergang: Skill cfalarm-wecker-und-boot, tag-freigeben.md.
 *
 * Der Kalendertermin bleibt unangetastet. Die Freigabe ist eine Aussage des NUTZERS ueber den
 * Dienstplan, kein Abbild des Dienstplans - deshalb liegt sie in einem eigenen Speicher und nicht
 * als Feld an der Spanne (die wird bei jedem Sync vollstaendig ersetzt).
 *
 * #142 - ZEITRAUM UND ZWEI QUELLEN: [freigebenZeitraum] gibt mehrere Tage in einem Schritt frei
 * (ein einzelner Tag ist der Zeitraum mit von == bis, es gibt nur EINE Schrittfolge). Neben den
 * Freigaben des Nutzers ("Hand") traegt der [FreieTageStore] die Kalender-Krank-Tage, die NUR
 * `AlarmUseCase.syncAlarms()` schreibt. Dieser UseCase schreibt und nimmt ausschliesslich
 * Hand-Freigaben zurueck; die Anzeige liest beide Quellen getrennt ueber [freigabeQuellen].
 */
@Singleton
class TagFreigabeUseCase @Inject constructor(
    private val store: FreieTageStore,
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService,
    private val alarmSkipUseCase: IAlarmSkipUseCase,
    private val armierer: ZeitkettenArmierer
) {
    companion object {
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

        /**
         * #142, Eigentuemer-Entscheidung: hoechstens so viele Tage auf einmal. Gilt im UseCase UND
         * im Dialog (`DienstFaelltAusDialog`) - der Picker selbst kann die Grenze nicht erzwingen,
         * er sieht immer nur einen einzelnen Tag, nie den Beginn des Zeitraums.
         */
        const val MAX_ZEITRAUM_TAGE = 31
    }

    /** #142: beide Quellen getrennt - die Anzeige muss "Hand" und "Kalender" unterscheiden. */
    val freigabeQuellen: Flow<FreigabeQuellen> = store.freigabeQuellen

    suspend fun freigabeQuellenNow(): FreigabeQuellen = store.freigabeQuellenNow()

    /**
     * Gibt [datum] frei - der Zeitraum mit von == bis. Bewusst nur eine Huelle: eine zweite Kopie
     * der Schrittfolge liefe frueher oder spaeter auseinander (#142).
     */
    suspend fun freigeben(datum: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Result<TagFreigabeResult> {
        val ergebnis = freigebenZeitraum(datum, datum, zone)
        // Identitaet des Fehlers erhalten: das ViewModel braucht `alarmIds` und
        // `freigabeZurueckgenommen` der FreigabeZurueckgenommenException.
        ergebnis.fehler?.let { return Result.failure(it) }
        return Result.success(
            TagFreigabeResult(
                datum = datum,
                geloeschteWecker = ergebnis.geloeschteWecker,
                ueberspringenAufgehoben = ergebnis.ueberspringenAufgehoben
            )
        )
    }

    /**
     * #142: gibt [von] bis [bis] (beide einschliesslich) frei, Tag fuer Tag aufsteigend, je Tag die
     * volle Einzeltag-Schrittfolge ([freigebenEinTag]).
     *
     * Beim ERSTEN gescheiterten Tag ist Schluss: die Tage davor BLEIBEN freigegeben
     * (Eigentuemer-Entscheidung), der gescheiterte Tag ist wie bisher zurueckgerollt, die Tage
     * danach sind nicht angefasst. Die Neben-Ketten laufen GENAU EINMAL am Ende - auch im
     * Fehlerfall, denn die frueheren Tage stehen ja.
     */
    suspend fun freigebenZeitraum(
        von: LocalDate,
        bis: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): TagFreigabeZeitraumResult {
        // Pruefung VOR allem anderen, auch vor dem try/finally: ein abgewiesener Zeitraum darf
        // nichts anfassen, auch die Neben-Ketten nicht.
        val anzahlTage = ChronoUnit.DAYS.between(von, bis) + 1
        if (bis.isBefore(von) || anzahlTage > MAX_ZEITRAUM_TAGE) {
            return TagFreigabeZeitraumResult(
                von = von,
                bis = bis,
                freigegebenBis = null,
                geloeschteWecker = 0,
                ueberspringenAufgehoben = false,
                fehler = IllegalArgumentException(
                    "Zeitraum $von bis $bis ungueltig: Ende vor Beginn oder mehr als $MAX_ZEITRAUM_TAGE Tage"
                )
            )
        }

        // #142: DIE GANZE SCHLEIFE IST NICHT ABBRECHBAR. Der Nutzer tippt "Freigeben" und legt das
        // Handy weg - der viewModelScope stirbt. Ein Abbruch zwischen zwei Tagen hinterliesse einen
        // halben Zeitraum ohne jede Rueckmeldung, und die Neben-Ketten liefen gar nicht.
        return withContext(NonCancellable) {
            var markiert = false
            var freigegebenBis: LocalDate? = null
            var geloeschteWecker = 0
            var ueberspringenAufgehoben = false
            var fehler: Throwable? = null
            try {
                var tag = von
                while (!tag.isAfter(bis)) {
                    val ergebnis = freigebenEinTag(tag, zone) { markiert = true }
                    val tagesErgebnis = ergebnis.getOrNull()
                    if (tagesErgebnis == null) {
                        fehler = ergebnis.exceptionOrNull()
                        break
                    }
                    freigegebenBis = tag
                    geloeschteWecker += tagesErgebnis.geloeschteWecker
                    ueberspringenAufgehoben = ueberspringenAufgehoben || tagesErgebnis.ueberspringenAufgehoben
                    tag = tag.plusDays(1)
                }
            } finally {
                // 6. Neben-Ketten neu anwerfen: Dimmer und DND rechnen ihre Fenster erst beim
                // naechsten Tick neu, und der kann Stunden entfernt liegen. Ohne das bliebe "Nicht
                // stoeren" waehrend der gerade freigegebenen Schicht weiter an - genau der gemeldete
                // Vorfall. Vorbild: `AlarmMaintenanceService.rescheduleSideChannels`.
                //
                // #142: EINMAL fuer den ganzen Zeitraum statt je Tag, und auch dann, wenn ein Tag
                // gescheitert ist - sobald irgendeine Markierung geschrieben wurde, denn die Tage
                // davor stehen. Ein zurueckgerollter Tag schadet dabei nicht: armiere() rechnet
                // die Fenster nur aus dem aktuellen Stand neu.
                if (markiert) werfeNebenkettenAn()
            }
            TagFreigabeZeitraumResult(
                von = von,
                bis = bis,
                freigegebenBis = freigegebenBis,
                geloeschteWecker = geloeschteWecker,
                ueberspringenAufgehoben = ueberspringenAufgehoben,
                fehler = fehler
            )
        }
    }

    /**
     * Gibt EINEN Tag frei: Markierung setzen, Wecker des Tages abraeumen, ein kollidierendes
     * Ueberspringen aufheben. Die Neben-Ketten wirft [freigebenZeitraum] einmal fuer alle Tage an.
     *
     * Die Schrittfolge ist die von `AlarmSkipUseCase.skipNextAlarm()`, und zwar aus denselben
     * Gruenden - siehe die Begruendungen an den einzelnen Schritten.
     *
     * @param markiert laeuft, sobald die Markierung geschrieben ist - ab da muessen die Neben-Ketten
     *   laufen, auch wenn der Tag danach scheitert und zurueckgerollt wird.
     */
    private suspend fun freigebenEinTag(
        datum: LocalDate,
        zone: ZoneId,
        markiert: () -> Unit
    ): Result<TagFreigabeResult> {
        // Wie beim Ueberspringen: die Ruecknahme muss den Aufrufer MIT IHRER IDENTITAET erreichen.
        // SafeExecutor verwandelt jede Exception in einen AppError - `alarmIds` und
        // `freigabeZurueckgenommen` waeren unterwegs verloren, also genau die Angaben, mit denen
        // das ViewModel die stumm gewordenen Wecker wieder stellt.
        var ruecknahme: FreigabeZurueckgenommenException? = null
        val ergebnis = SafeExecutor.safeExecute("TagFreigabeUseCase.freigebenEinTag") {
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
                markiert()

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

                    val geloescht = alarmRepository.loescheDauerhaftMitNachfassen(
                        alarmId = alarm.id,
                        logTag = LogTags.ALARM,
                        versuchsText = "FREIGABE: Loeschen von Wecker ${alarm.id}",
                        nomen = "Wecker"
                    )
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

                // 5a. Ein schon GESCHLUMMERTER Wecker dieses Tages steht nicht mehr in `betroffene`
                // (der Bestand wirft Verstrichenes weg), und sein Snooze liegt in einem eigenen
                // Slot, den `cancelSystemAlarm` nie trifft - ohne diesen Schritt klingelte er am
                // freigegebenen Morgen noch einmal. Zuordnung ueber die Weckzeit, manuelle Wecker
                // ausgenommen (`AlarmManagerService.snoozesFuerFreigabe`). Wirft nie.
                // ERST HIER, nach der Schleife: scheitert dort ein Loeschen, wird die Freigabe
                // zurueckgenommen - ein vorher abgebrochener Snooze waere dann an einem NICHT
                // freigegebenen Tag weg, samt Merker (Review 14.10.2026). Stirbt der Prozess
                // zwischen Markierung und hier, faengt ihn der Backstop im `AlarmReceiver`.
                alarmManagerService.cancelSnoozesFuerFreigabe(datum, zone)

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

            TagFreigabeResult(
                datum = datum,
                geloeschteWecker = betroffene.size,
                ueberspringenAufgehoben = ueberspringenAufgehoben
            )
        }
        return ruecknahme?.let { Result.failure(it) } ?: ergebnis
    }

    /**
     * Nimmt die Freigaben [tage] zurueck. Die Wecker baut der Aufrufer ueber einen
     * Kalender-Refresh neu auf - denselben Weg geht `cancelSkip` im `AlarmViewModel`.
     *
     * #142: NUR Hand-Freigaben. Die Kalender-Krank-Tage gehoeren dem Sync - ein Tag, den beide
     * Quellen tragen, bleibt deshalb frei, solange der Krank-Termin im Kalender steht. Viele Tage
     * (Ausfall-Karte der Uebersicht) gehen in EINEM Schreibvorgang, armiert wird EINMAL.
     */
    suspend fun zuruecknehmenTage(tage: Set<LocalDate>): Result<Unit> =
        SafeExecutor.safeExecute("TagFreigabeUseCase.zuruecknehmenTage") {
            withContext(NonCancellable) {
                store.zuruecknehmenAlle(tage)
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

    private suspend fun werfeNebenkettenAn() = armierer.armiere("FREIGABE")
}
