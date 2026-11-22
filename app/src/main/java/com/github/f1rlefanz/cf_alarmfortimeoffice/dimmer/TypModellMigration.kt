package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
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
 * faellt die Stufe auf 0 und der naechste Anlass migriert erneut - die Nutzeraenderung bleibt.
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
    private val armierer: ZeitkettenArmierer
) {
    /** Was die Karte im System-Status anzeigt. Nur im Speicher: kein neuer Preference-Schluessel. */
    sealed interface Stand {
        /** In diesem Prozess noch nicht entschieden (oder nur verschoben, weil gesperrt). */
        data object Offen : Stand

        /** Die Konfiguration traegt die Stufe - gerade migriert oder schon vorher. */
        data object Erledigt : Stand

        data class Fehlgeschlagen(val grund: Grund) : Stand
    }

    /** Warum nicht migriert wurde, in Nutzersprache (eine Zeile auf der Karte). */
    enum class Grund(val text: String) {
        KONFIGURATION_NICHT_LESBAR("Die Schichttypen ließen sich nicht lesen."),
        BESTAND_NICHT_LESBAR("Die gespeicherten Daten der App ließen sich nicht lesen."),
        DIMMER_UMSTELLUNG_AUSSTEHEND("Die vorherige Umstellung des Dimmers ist noch nicht abgeschlossen."),
        RUFBEREITSCHAFT_AUSSTEHEND("Die Übernahme der Rufbereitschaft ist noch nicht abgeschlossen."),
        ALTBESTAND_NICHT_LESBAR("Die bisherigen Einstellungen für Dimmen und „Nicht stören“ ließen sich nicht lesen."),
        SPEICHERN_FEHLGESCHLAGEN("Die Schichttypen ließen sich nicht speichern."),
        KEINE_EIGENEN_SCHICHTTYPEN("Die App hat keine eigenen Schichttypen gefunden.")
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

    /**
     * Migriert, falls die Konfiguration noch keine [TypEinstellungenUebersetzung.MODELL_STUFE]
     * traegt. Wirft nie.
     *
     * @return `true`, wenn geschrieben wurde. Dimmer und DND sind dann schon neu armiert.
     */
    suspend fun migriereEinmalig(): Boolean = withContext(NonCancellable) {
        // NonCancellable: angestossen wird das auch aus dem lifecycleScope einer Activity.
        try {
            fuehreAus()
        } catch (e: Abbruch) {
            _stand.value = Stand.Fehlgeschlagen(e.grund)
            Logger.w(
                LogTags.DIMMER,
                "Typ-Modellmigration nicht ausgefuehrt (${e.grund}) - nichts geschrieben, " +
                    "Altbestand unveraendert, naechster Anlass versucht es erneut",
                e.cause
            )
            false
        } catch (e: Exception) {
            _stand.value = Stand.Fehlgeschlagen(Grund.SPEICHERN_FEHLGESCHLAGEN)
            Logger.w(LogTags.DIMMER, "Typ-Modellmigration fehlgeschlagen - naechster Anlass versucht es erneut", e)
            false
        }
    }

    private suspend fun fuehreAus(): Boolean {
        // Tor 1 - kein Fehlerzustand: gesperrt gibt es keine Oberflaeche, und nach dem Entsperren
        // kommt der naechste Anlass.
        if (!NutzerEntsperrung.istEntsperrt(context, LogTags.DIMMER)) {
            Logger.w(LogTags.DIMMER, "Typ-Modellmigration verschoben - Geraet noch nicht entsperrt (CE-Store waere leer)")
            return false
        }

        // Tor 2 - zuerst, damit ein schon migriertes Geraet nie wieder einen Fehler meldet.
        if (konfiguration().modellStufe >= TypEinstellungenUebersetzung.MODELL_STUFE) {
            _stand.value = Stand.Erledigt
            return false
        }

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
        if (config.modellStufe >= TypEinstellungenUebersetzung.MODELL_STUFE) {
            _stand.value = Stand.Erledigt
            return false
        }
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
            return false
        }

        val ziel = TypEinstellungenUebersetzung.uebersetze(config, alt, dimRuleUseCase)
        shiftUseCase.saveShiftConfig(ziel).getOrElse { throw Abbruch(Grund.SPEICHERN_FEHLGESCHLAGEN, it) }

        _stand.value = Stand.Erledigt
        Logger.business(
            LogTags.DIMMER,
            "🔁 Typ-Modellmigration: ${config.definitions.size} Typ(en) und die feste Zeile uebernommen " +
                "(${regeln.size} Regel(n), Dimmen ${if (dimmenAn) "an" else "aus"}) - Altbestand unveraendert"
        )
        // Nach Erfolg beide Ketten neu armieren (#216). Wirft nicht, faengt selbst.
        armierer.armiere(ANLASS)
        return true
    }

    private suspend fun konfiguration() =
        shiftUseCase.getCurrentShiftConfig().getOrElse { throw Abbruch(Grund.KONFIGURATION_NICHT_LESBAR, it) }
}
