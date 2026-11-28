package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NutzerEntsperrung
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Umbau Etappe 3 (#216, SPEC Abschnitt 4): fuehrt die Uebersetzung [TypEinstellungenUebersetzung]
 * auf dem Geraet aus - einmalig, idempotent und nur ueber einen LESBAREN Bestand.
 *
 * VORBILD IST [DimmerModellMigration], mit denselben drei Anlaessen: `MainActivity.onCreate`,
 * `AlarmMaintenanceService.rescheduleSideChannels` (beide NACH der Dimmer- und der
 * Rufbereitschaft-Migration) und der Konfigurations-Import (nach `migriereNachImport`).
 *
 * DER MARKER IST [com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.modellStufe] und
 * liegt im JSON der Konfiguration, nicht als eigener Schluessel: ein importierter alter Export
 * traegt ihn nicht und wird deshalb ohne Zuruecksetzen migriert; ein Revert verliert ihn mit den
 * neuen Feldern. Es gibt hier also kein `migriereNachImport` - der Import ruft [migriereEinmalig].
 *
 * DIE TORE, in dieser Reihenfolge (jedes schreibt bei "nein" NICHTS und setzt keine Stufe):
 * 1. Entsperrt? Vor der ersten Entsperrung liest der CE-Store still LEER (Skill
 *    cfalarm-persistenz-und-auth); eine Migration darueber schriebe "alles aus" an jeden Typ.
 * 2. Konfiguration lesbar ([IShiftUseCase.getCurrentShiftConfig], `getOrThrow`-Semantik: ein
 *    Fehler ist nie eine leere oder Standard-Konfiguration) - und schon migriert? Dann ist nichts
 *    zu tun, auch wenn der Altbestand inzwischen unlesbar waere.
 * 3. Alarm-Bestand lesbar ([IAlarmRepository.isPersistenceBlocked], SPEC 4)? Er liegt im selben
 *    `settings`-Store wie Regeln und Schalter.
 * 4. Dimmer-Modellmigration gelaufen (`dim_modell_migration`)? Sonst steht ein alter Export mit
 *    Wellness-Schluesseln noch nicht in den Regeln, und die Stufe wuerde ueber einen halben
 *    Bestand gesetzt. Der Marker ist zugleich ein Kanarienvogel fuer einen still leeren Store.
 * 5. Rufbereitschaft-Migration gelaufen (Altliste `dnd_oncall_shifts` weg)? Sonst ist der Ruf-Typ
 *    noch nicht als solcher markiert und verloere seinen Cutoff dauerhaft.
 * 6. Altbestand STRIKT lesbar ([DimRuleRepository.regelnOderFehler],
 *    [DimOverlayPrefs.dimmenAnOderFehler], [DndPrefs.altbestandOderFehler]) - die Anzeige-Flows
 *    daneben degradieren auf leer bzw. aus, und das darf nie Schreibwahrheit werden.
 * 7. Die Konfiguration ist weder LEER noch die STANDARD-Konfiguration (Festlegung #216).
 *    `getCurrentShiftConfig()` liefert die Standard-Konfiguration auch dann als Erfolg, wenn
 *    `shift_prefs` in diesem Prozess vor der Entsperrung gelesen wurde (`ShiftConfigRepository`,
 *    "noch nie konfiguriert" fuer die restliche Prozesslaufzeit) - darueber geschrieben, waere die
 *    echte Konfiguration weg. Preis: wer die unveraenderten Standard-Typen nutzt, wird erst
 *    migriert, sobald er einen Typ speichert; traegt sein Altbestand etwas, sagt es die Karte.
 *    Was er dabei schon am Typ gespeichert hat (die Typ-Seite speichert auch auf Stufe 0), bleibt
 *    stehen: die Uebersetzung nimmt nur Einstellungs-Objekte, die noch dem Standard entsprechen,
 *    und bei leerem Altbestand setzt sie nur die Stufe (#234, [TypEinstellungenUebersetzung]).
 *
 * KOPIEREN STATT VERSCHIEBEN: am Altbestand wird nichts geschrieben. Geschrieben werden nur die
 * neuen Felder und die Stufe, in EINEM Vorgang ueber [IShiftUseCase.saveShiftConfig] (der UseCase,
 * nicht das Repository: nur er leert den Erkennungs-Cache, siehe `RufbereitschaftMigration`).
 *
 * GLEICHZEITIGE NUTZERAENDERUNG: Die Konfiguration wird NACH dem Altbestand ein zweites Mal
 * gelesen und erst diese Fassung uebersetzt - eine Aenderung waehrend der Lesevorgaenge bleibt
 * so erhalten. Offen bleibt das kurze Stueck zwischen diesem letzten Lesen und dem Schreiben:
 * `saveShiftConfig` schreibt die ganze Konfiguration, einen atomaren Lese-Schreib-Weg hat das
 * Repository nicht. Schreibt umgekehrt ein Bildschirm mit einem Stand VOR der Migration zurueck,
 * faellt die Stufe auf 0 und der naechste Anlass migriert erneut. Die Nutzeraenderung bleibt dabei,
 * weil die Uebersetzung jedes Einstellungs-Objekt (Dimmen, „Nicht stoeren“ je Typ; Dimmen und
 * „Solange gedimmt wird“ der festen Zeile) stehen laesst, das vom Standard abweicht (#234) - bis
 * #234 ersetzte sie es durch den Altbestand, und diese Zusage galt nicht. Restfall: eine Eingabe,
 * die genau dem Standard entspricht (etwa „Dimmen an“ ohne Fenster), gilt als unberuehrt und wird
 * aus dem Altbestand uebersetzt.
 *
 * FAIL-SAFE: Scheitert ein Tor, wird nichts geschrieben, die Stufe bleibt ungesetzt, es gibt ein
 * WARN im Release-Log, [stand] meldet den Grund (Karte im System-Status), und der naechste Anlass
 * versucht es erneut. Bis dahin finden Dimmer und DND ab Paket D keine Fenster am Typ - die
 * harmlose Richtung (hell, erreichbar).
 *
 * `dimmer/` liest hier `dnd/` (DndPrefs) - der Ort der Datei ist vorgegeben, und wie beim
 * [ZeitkettenArmierer] ist das keine Fensterlogik: die Einbahnstrasse betrifft die Fensterquellen.
 *
 * LAEUFT NIE BEIM BAUEN DES GRAPHEN, nur aus [migriereEinmalig] (kein CE-Zugriff im Konstruktor).
 *
 * ZWEITER SCHRITT: DAS LICHT (Umbau Etappe 4, #248, SPEC E8 und Abschnitt 4). Nach dem Schritt oben
 * zieht [lichtSchritt] die heutigen Hue-Regeln einmalig an die Typen ([TypLichtUebersetzung], #247).
 * Eigener Marker [ShiftConfig.lichtStufe] (nicht `modellStufe = 2`, siehe dort), eigene Tore, ein
 * eigener Schreibvorgang. Er laeuft AUCH, wenn der Schritt oben gescheitert ist: die Uebersetzung
 * braucht nichts aus ihm (Name, Kategorie, `isSilent`, das eigene Licht), und eine haengende
 * Dimmer-Umstellung soll das Licht nicht mit festhalten. Die Tore, jedes schreibt bei "nein" nichts:
 * 1. Entsperrt? (gemeinsam mit oben) - davor liest auch `hue_settings` still leer, und eine leere
 *    Liste setzt die Stufe endgueltig.
 * 2. `lichtStufe` gesetzt? Dann erledigt, nie ein Fehler.
 * 3. [IAlarmRepository.isPersistenceBlocked].
 * 4. Die Hue-Regeln STRIKT ([IHueConfigRepository.getScheduleRules]): ein Fehlschlag ist
 *    [Grund.HUE_REGELN_NICHT_LESBAR], nie "keine Regeln". Ueber das Repository, nicht ueber
 *    `HueRuleUseCase`: das zoege den Hue-Graphen (OkHttp) in jeden Anlass. Gelesen wird erst hier,
 *    nie beim Bauen.
 * 5. Die Konfiguration erneut lesen (gleichzeitige Nutzeraenderung, wie oben) und Tor 7 von oben:
 *    nie ueber eine leere oder die Standard-Konfiguration - die Uebersetzung schreibt die GANZE
 *    Konfiguration, ueber einen vor dem Entsperren gelesenen Standard waere die echte weg. Die
 *    Betreuer-Reihenfolge in #248 nennt das Tor nicht; ohne es waere genau dieser Fall offen.
 * Der Altbestand im Hue-Store bleibt unveraendert (kopieren statt verschieben). Kein Armieren:
 * Dimmer und DND lesen das Licht nicht. NEU GEPLANT wird nach einem geschriebenen Licht-Schritt nur
 * der Vorab-Sonnenaufgang (`HueSmartScheduler.recalculateSchedule()`, #249): seit #249 liest die
 * Hue-Ausfuehrung die Regeln am Typ, und `scheduleSunriseStarts()` raeumt zuerst ab und liest dann -
 * eine Planung vor dem Schreiben (etwa beim Prozessstart der Wartung) fand keine Regeln, die Rampe
 * fehlte bis zum naechsten Anlass (bis zu 24 h). Mit #248 ("zoege den Hue-Graphen") vertraeglich:
 * ueber `dagger.Lazy` entsteht der Scheduler nie beim Bauen, sondern nur nach Tor 1 in genau dem
 * einen Anlass, der schreibt - einmal je Geraet. Beim Import plant es dadurch zweimal (hier und am
 * Ende von `ConfigBackupUseCase.import`) - harmlos, `scheduleSunriseStarts()` raeumt ab und plant
 * mit `REPLACE`. Ein Fehler beim Neuplanen kippt den Ausgang nicht.
 * Rest: hat ein anderer Leser `hue_settings` in diesem Prozess vor dem Entsperren gelesen, liest der Store danach still leer (Skill cfalarm-persistenz-und-auth),
 * und Tor 1 faengt das nicht - einen Kanarienvogel wie den Dimmer-Marker hat der Hue-Store nicht.
 * Dagegen steht die Regel, dass jeder Leser im Prozessstart vorher `istEntsperrt()` fragt.
 */
@Singleton
class TypModellMigration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    // NUR fuer das Lesen des Dimmer-Markers (Tor 4) - geschrieben wird hier nie.
    @param:MainDataStore private val dataStore: DataStore<Preferences>,
    private val dimRuleRepository: DimRuleRepository,
    private val dimRuleUseCase: DimRuleUseCase,
    private val dimOverlayPrefs: DimOverlayPrefs,
    private val dndPrefs: DndPrefs,
    private val shiftUseCase: IShiftUseCase,
    private val alarmRepository: IAlarmRepository,
    // NUR gelesen, im Licht-Schritt (Tor 4) - der Altbestand bleibt unveraendert.
    private val hueConfigRepository: IHueConfigRepository,
    private val armierer: ZeitkettenArmierer,
    // NUR nach einem geschriebenen Licht-Schritt (#249). `dagger.Lazy`, damit der Scheduler (und
    // mit ihm der Hue-Graph) nie beim Bauen entsteht - Muster `ConfigBackupUseCase`.
    private val hueSmartScheduler: dagger.Lazy<HueSmartScheduler>
) {
    /** Was die Karte im System-Status anzeigt. Nur im Speicher: kein neuer Preference-Schluessel. */
    sealed interface Stand {
        /** In diesem Prozess noch nicht entschieden (oder nur verschoben, weil gesperrt). */
        data object Offen : Stand

        /** Die Konfiguration traegt beide Stufen - gerade migriert oder schon vorher. */
        data object Erledigt : Stand

        /**
         * Ein Schritt ist gescheitert; scheitern beide, steht hier der erste ([Schritt.DIMMEN_UND_DND]).
         * [schritt] sagt, welcher - derselbe [Grund] kann in beiden vorkommen.
         */
        data class Fehlgeschlagen(val grund: Grund, val schritt: Schritt = Schritt.DIMMEN_UND_DND) : Stand
    }

    /** Welcher Teil der Uebernahme gescheitert ist. */
    enum class Schritt {
        /** Dimm-Regeln, Dimm-Schalter und „Nicht stoeren“ (#216). */
        DIMMEN_UND_DND,

        /** Die Hue-Regeln (#248). */
        LICHT
    }

    /** Warum nicht migriert wurde, in Nutzersprache (eine Zeile auf der Karte). */
    enum class Grund(val text: String) {
        KONFIGURATION_NICHT_LESBAR("Die Schichttypen ließen sich nicht lesen."),
        BESTAND_NICHT_LESBAR("Die gespeicherten Daten der App ließen sich nicht lesen."),
        DIMMER_UMSTELLUNG_AUSSTEHEND("Die vorherige Umstellung des Dimmers ist noch nicht abgeschlossen."),
        RUFBEREITSCHAFT_AUSSTEHEND("Die Übernahme der Rufbereitschaft ist noch nicht abgeschlossen."),
        ALTBESTAND_NICHT_LESBAR("Die bisherigen Einstellungen für Dimmen und „Nicht stören“ ließen sich nicht lesen."),
        SPEICHERN_FEHLGESCHLAGEN("Die Schichttypen ließen sich nicht speichern."),
        KEINE_EIGENEN_SCHICHTTYPEN("Die App hat keine eigenen Schichttypen gefunden."),
        HUE_REGELN_NICHT_LESBAR("Die bisherigen Hue-Regeln ließen sich nicht lesen.")
    }

    companion object {
        private val _stand = MutableStateFlow<Stand>(Stand.Offen)

        /**
         * Prozessweit, damit die Oberflaeche ihn ohne ViewModel lesen kann (Vorbild
         * [DimBedienungshilfenWunsch]); geschrieben nur von [migriereEinmalig].
         */
        val stand: StateFlow<Stand> = _stand.asStateFlow()

        /** Nur fuer Tests - der Zustand ist prozessweit und ueberlebte sonst den Testfall. */
        internal fun standZuruecksetzen() {
            _stand.value = Stand.Offen
        }

        private const val ANLASS = "TYP-MIGRATION"
    }

    private class Abbruch(val grund: Grund, cause: Throwable? = null) : Exception(grund.name, cause)

    /** Wie ein Schritt ausgegangen ist; daraus setzt [migriereEinmalig] den [stand]. */
    private sealed interface Ausgang {
        /** Gerade geschrieben. */
        data object Geschrieben : Ausgang

        /** Die Stufe stand schon. */
        data object SchonErledigt : Ausgang

        /** Verschoben ohne Fehler (es liegt nichts brach); der naechste Anlass versucht es erneut. */
        data object Verschoben : Ausgang

        data class Gescheitert(val grund: Grund) : Ausgang
    }

    /**
     * Migriert, falls die Konfiguration noch keine [TypEinstellungenUebersetzung.MODELL_STUFE] bzw.
     * keine [TypLichtUebersetzung.LICHT_STUFE] traegt - erst Dimmen und DND, dann das Licht. Wirft nie.
     *
     * @return `true`, wenn einer der beiden Schritte geschrieben hat. Hat der erste geschrieben, sind
     *   Dimmer und DND schon neu armiert; hat der zweite geschrieben, ist der Vorab-Sonnenaufgang
     *   schon neu geplant (angestossen, best-effort).
     */
    suspend fun migriereEinmalig(): Boolean = withContext(NonCancellable) {
        // NonCancellable: angestossen wird das auch aus dem lifecycleScope einer Activity.
        // Tor 1 beider Schritte - kein Fehlerzustand: gesperrt gibt es keine Oberflaeche, und nach
        // dem Entsperren kommt der naechste Anlass.
        if (!NutzerEntsperrung.istEntsperrt(context, LogTags.DIMMER)) {
            Logger.w(LogTags.DIMMER, "Typ-Modellmigration verschoben - Geraet noch nicht entsperrt (CE-Store waere leer)")
            return@withContext false
        }
        val dimmenUndDnd = schritt("Typ-Modellmigration") { fuehreAus() }
        val licht = schritt("Licht-Migration") { lichtSchritt() }

        _stand.value = when {
            dimmenUndDnd is Ausgang.Gescheitert -> Stand.Fehlgeschlagen(dimmenUndDnd.grund, Schritt.DIMMEN_UND_DND)
            licht is Ausgang.Gescheitert -> Stand.Fehlgeschlagen(licht.grund, Schritt.LICHT)
            dimmenUndDnd != Ausgang.Verschoben && licht != Ausgang.Verschoben -> Stand.Erledigt
            else -> Stand.Offen
        }
        dimmenUndDnd == Ausgang.Geschrieben || licht == Ausgang.Geschrieben
    }

    /** Fuehrt einen Schritt aus; jeder Fehler wird zu [Ausgang.Gescheitert], mit WARN im Release-Log. */
    private suspend fun schritt(name: String, block: suspend () -> Ausgang): Ausgang = try {
        block()
    } catch (e: Abbruch) {
        Logger.w(
            LogTags.DIMMER,
            "$name nicht ausgefuehrt (${e.grund}) - nichts geschrieben, " +
                "Altbestand unveraendert, naechster Anlass versucht es erneut",
            e.cause
        )
        Ausgang.Gescheitert(e.grund)
    } catch (e: Exception) {
        Logger.w(LogTags.DIMMER, "$name fehlgeschlagen - naechster Anlass versucht es erneut", e)
        Ausgang.Gescheitert(Grund.SPEICHERN_FEHLGESCHLAGEN)
    }

    /** Der Schritt Dimmen und DND (#216), nach Tor 1 in [migriereEinmalig]. */
    private suspend fun fuehreAus(): Ausgang {
        // Tor 2 - zuerst, damit ein schon migriertes Geraet nie wieder einen Fehler meldet.
        if (konfiguration().modellStufe >= TypEinstellungenUebersetzung.MODELL_STUFE) return Ausgang.SchonErledigt

        // Tor 3
        if (alarmRepository.isPersistenceBlocked()) throw Abbruch(Grund.BESTAND_NICHT_LESBAR)

        // Tor 4 und 5 - beide Werte strikt (ein Lesefehler ist kein "nicht gelaufen").
        val dimmerStufe = try {
            dataStore.data.first()[intPreferencesKey(DimmerModellMigration.KEY_MARKER_NAME)] ?: 0
        } catch (e: Exception) {
            throw Abbruch(Grund.ALTBESTAND_NICHT_LESBAR, e)
        }
        if (dimmerStufe < DimmerModellMigration.STAND) throw Abbruch(Grund.DIMMER_UMSTELLUNG_AUSSTEHEND)
        val rufAltliste = try {
            dndPrefs.legacyOnCallShiftsNow()
        } catch (e: Exception) {
            throw Abbruch(Grund.ALTBESTAND_NICHT_LESBAR, e)
        }
        if (rufAltliste != null) throw Abbruch(Grund.RUFBEREITSCHAFT_AUSSTEHEND)

        // Tor 6
        val regeln = dimRuleRepository.regelnOderFehler().getOrElse { throw Abbruch(Grund.ALTBESTAND_NICHT_LESBAR, it) }
        val dimmenAn = dimOverlayPrefs.dimmenAnOderFehler().getOrElse { throw Abbruch(Grund.ALTBESTAND_NICHT_LESBAR, it) }
        val dnd = dndPrefs.altbestandOderFehler().getOrElse { throw Abbruch(Grund.ALTBESTAND_NICHT_LESBAR, it) }
        val alt = TypEinstellungenUebersetzung.Altbestand(
            regeln = regeln,
            dimmenAn = dimmenAn,
            dndSolangeGedimmt = dnd.folgtDimmer,
            dndWaehrendEintrag = dnd.waehrendDienst,
            dndAusnahmen = dnd.dienstAusnahmen,
            rufCutoffMinuten = dnd.rufCutoffMinuten
        )

        // Erst JETZT die Fassung lesen, die geschrieben wird - siehe Klassen-KDoc
        // (gleichzeitige Nutzeraenderung). Jeder Schreiber im Prozess aktualisiert den
        // Repository-Cache, dieser Read sieht also auch eine Aenderung von eben.
        val config = konfiguration()
        if (config.modellStufe >= TypEinstellungenUebersetzung.MODELL_STUFE) return Ausgang.SchonErledigt
        // Tor 7 - siehe Klassen-KDoc. Eine Karte nur, wenn dabei wirklich etwas liegen bleibt:
        // eine frische Installation (keine Regel, DND aus) hat nichts zu melden.
        if (config.definitions.isEmpty() || config == ShiftConfig.getDefaultConfig()) {
            val traegtEtwas = regeln.isNotEmpty() || dnd.folgtDimmer || dnd.waehrendDienst
            Logger.w(
                LogTags.DIMMER,
                "Typ-Modellmigration verschoben - keine eigene Schicht-Konfiguration " +
                    "(${config.definitions.size} Typ(en)), Altbestand " +
                    (if (traegtEtwas) "traegt Einstellungen" else "leer") + " - nichts geschrieben"
            )
            if (traegtEtwas) throw Abbruch(Grund.KEINE_EIGENEN_SCHICHTTYPEN)
            return Ausgang.Verschoben
        }

        val ziel = TypEinstellungenUebersetzung.uebersetze(config, alt, dimRuleUseCase)
        shiftUseCase.saveShiftConfig(ziel).getOrElse { throw Abbruch(Grund.SPEICHERN_FEHLGESCHLAGEN, it) }

        Logger.business(
            LogTags.DIMMER,
            if (alt.istLeer) {
                "🔁 Typ-Modellmigration: Altbestand leer - nur die Stufe gesetzt, ${config.definitions.size} Typ(en) unveraendert"
            } else {
                "🔁 Typ-Modellmigration: ${config.definitions.size} Typ(en) und die feste Zeile uebernommen " +
                    "(${regeln.size} Regel(n), Dimmen ${if (dimmenAn) "an" else "aus"}; vorher Gespeichertes bleibt) " +
                    "- Altbestand unveraendert"
            }
        )
        // Nach Erfolg beide Ketten neu armieren (#216). Wirft nicht, faengt selbst.
        armierer.armiere(ANLASS)
        return Ausgang.Geschrieben
    }

    /** Der Schritt Licht (#248), nach Tor 1 in [migriereEinmalig]; die Tore im Klassen-KDoc. */
    private suspend fun lichtSchritt(): Ausgang {
        // Tor 2 - zuerst, damit ein schon migriertes Geraet nie wieder einen Fehler meldet.
        if (konfiguration().lichtStufe >= TypLichtUebersetzung.LICHT_STUFE) return Ausgang.SchonErledigt

        // Tor 3
        if (alarmRepository.isPersistenceBlocked()) throw Abbruch(Grund.BESTAND_NICHT_LESBAR)

        // Tor 4 - strikt: `getScheduleRules()` meldet einen Lese- oder Dekodierfehler als Failure.
        val altRegeln = hueConfigRepository.getScheduleRules().getOrElse { throw Abbruch(Grund.HUE_REGELN_NICHT_LESBAR, it) }

        // Tor 5 - erst JETZT die Fassung lesen, die geschrieben wird (wie oben), und nie ueber eine
        // leere oder die Standard-Konfiguration. Eine Karte nur, wenn dabei Regeln liegen bleiben.
        val config = konfiguration()
        if (config.lichtStufe >= TypLichtUebersetzung.LICHT_STUFE) return Ausgang.SchonErledigt
        if (config.definitions.isEmpty() || config == ShiftConfig.getDefaultConfig()) {
            Logger.w(
                LogTags.DIMMER,
                "Licht-Migration verschoben - keine eigene Schicht-Konfiguration " +
                    "(${config.definitions.size} Typ(en)), ${altRegeln.size} Hue-Regel(n) - nichts geschrieben"
            )
            if (altRegeln.isNotEmpty()) throw Abbruch(Grund.KEINE_EIGENEN_SCHICHTTYPEN)
            return Ausgang.Verschoben
        }

        val ergebnis = TypLichtUebersetzung.uebersetze(config, altRegeln)
        shiftUseCase.saveShiftConfig(ergebnis.config).getOrElse { throw Abbruch(Grund.SPEICHERN_FEHLGESCHLAGEN, it) }

        val z = ergebnis.zaehler
        Logger.business(
            LogTags.DIMMER,
            if (altRegeln.isEmpty()) {
                "💡 Licht-Migration: keine Hue-Regeln - nur die Stufe gesetzt, ${config.definitions.size} Typ(en) unveraendert"
            } else {
                "💡 Licht-Migration: ${altRegeln.size} Hue-Regel(n), ${config.definitions.size} Typ(en) - " +
                    "${z.uebernommen} uebernommen, ${z.kopiert} Kopie(n) allgemeiner Regeln, " +
                    "${z.stummAbgeschaltet} am stummen Typ ausgeschaltet, ${z.verwaist} ohne Typ, " +
                    "${z.schonBelegt} entfallen (Typ hatte schon Licht) - Hue-Altbestand unveraendert"
            }
        )
        // Den Vorab-Sonnenaufgang neu planen (#249, Klassen-KDoc). Best-effort und um `get()` UND
        // den Aufruf gelegt: ein Wurf hier liesse `schritt()` die geschriebene Uebernahme als
        // gescheitert melden. Master-Pause und "keine Bridge" prueft der Scheduler selbst.
        runCatching { hueSmartScheduler.get().recalculateSchedule() }
            .onFailure { Logger.w(LogTags.DIMMER, "Licht-Migration: Vorab-Sonnenaufgang nicht neu geplant - naechster Anlass plant", it) }
        return Ausgang.Geschrieben
    }

    private suspend fun konfiguration() =
        shiftUseCase.getCurrentShiftConfig().getOrElse { throw Abbruch(Grund.KONFIGURATION_NICHT_LESBAR, it) }
}
