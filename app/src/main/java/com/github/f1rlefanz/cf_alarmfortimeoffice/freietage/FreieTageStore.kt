package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die vom Nutzer FREIGEGEBENEN Kalendertage: Tage, an denen laut Dienstplan eine Schicht steht,
 * an denen der Dienst aber nicht stattfindet (Chef hat freigegeben, Tausch, Krankmeldung).
 *
 * **Warum das nicht dasselbe ist wie "Wecker ueberspringen".** Eine [ShiftSpan] kennt bewusst kein
 * "uebersprungen": ein ausgelassener Weckruf aendert nichts daran, dass der Dienst stattfindet -
 * wer ohne Wecker wach ist, will trotzdem "Nicht stoeren" und den Schicht-Dimmer. Genau diese
 * Trennung hat am 24.08.2026 am Fairphone zugeschlagen: der Nutzer hatte frei, uebersprang den
 * Wecker (der Wecker blieb korrekt stumm) - und um 14:48 Uhr, dem Beginn der Schicht `S2`, ging
 * "Nicht stoeren" an, weil die Spanne den Dienst weiterhin als stattfindend auswies
 * (`zen_mode=1`, Regel `CFAlarm Ruhezeit` `STATE_TRUE`). Die Geste fuer den umgekehrten Fall -
 * **der Dienst faellt aus** - fehlte, also griff der Nutzer zur einzigen vorhandenen.
 *
 * Deshalb dieser zweite, unabhaengige Speicher: **Ueberspringen betrifft den WECKER, eine
 * Freigabe betrifft den DIENST.**
 *
 * **Gebunden an das DATUM, nicht an eine Schicht oder Kalender-Kennung.** Der Dienstplan kommt aus
 * einem abonnierten Feed, dem Google alle paar Tage neue Event-IDs gibt (am Geraet gemessen:
 * 11 geloescht, 11 angelegt, Schnittmenge null). Eine an der Kennung oder am Schichtnamen
 * haengende Freigabe waere nach so einer Rotation lautlos wirkungslos - und ein lautlos
 * wirkungsloser freier Tag ist ein Wecker am freien Morgen. Ein Datum ueberlebt das.
 *
 * Der Tag gilt fuer den GANZEN Kalendertag: hat ein Tag zwei Schichten, fallen beide weg.
 */
@Singleton
class FreieTageStore @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /** Oeffentlich, damit `ConfigBackupFormat` denselben Namen ausschliesst statt eines Duplikats. */
        const val KEY_FREIE_TAGE_NAME = "freigegebene_tage"

        private val KEY_FREIE_TAGE = stringSetPreferencesKey(KEY_FREIE_TAGE_NAME)

        /**
         * Rueckschau beim Aufraeumen - **`heute - 1`, nicht `heute`**.
         *
         * Entspricht `DimWindowResolver.LOOKBACK_DAYS` und `ShiftSpanStore.RETENTION_MS`: die
         * Fenster-Schleifen beginnen einen Kalendertag VOR heute, weil eine am Vorabend begonnene
         * Nacht nach dem Datumswechsel noch dazugehoert. Wuerde die Freigabe um 00:00 verschwinden,
         * kippte eine ueber Mitternacht laufende, ausgesetzte Nacht schlagartig zurueck in
         * "dimmen + Nicht stoeren an" - mitten im Schlaf.
         */
        const val RUECKSCHAU_TAGE = 1L

        /**
         * Verwirft Freigaben, die aelter als [RUECKSCHAU_TAGE] sind. Rein und ohne DataStore
         * testbar (Projektkonvention: reine Logik wird getestet, duenne Android-Wrapper nicht).
         *
         * Unlesbare Eintraege (kein ISO-Datum) fliegen ebenfalls raus: ein Wert, den niemand
         * deuten kann, darf nicht als "dieser Tag ist frei" durchgehen.
         */
        internal fun prune(tage: Set<String>, heute: LocalDate): Set<String> {
            val grenze = heute.minusDays(RUECKSCHAU_TAGE)
            return tage.filter { roh ->
                val datum = parseOderNull(roh)
                datum != null && !datum.isBefore(grenze)
            }.toSet()
        }

        /** `null` statt Wurf: ein einzelner Schrottwert darf nicht den ganzen Bestand kippen. */
        internal fun parseOderNull(roh: String): LocalDate? = try {
            LocalDate.parse(roh)
        } catch (_: Exception) {
            null
        }

        /**
         * Der Kalendertag einer Schichtspanne.
         *
         * **Anker ist die WECKZEIT ([ShiftSpan.alarmTriggerTime]), nicht der Schichtbeginn.** Das
         * ist dieselbe Ableitung, die `DimWindowResolver.slotsByDate` fuer die Tagesverankerung
         * aller Dimm-Fenster benutzt. Wuerde hier der Schichtbeginn ankern (wie es
         * `DndOnCallCutoffResolver` fuer seinen Cutoff tut), fiele ein freigegebener Tag bei einer
         * abends beginnenden Schicht in Dimmer und DND auf verschiedene Kalendertage - und der
         * Nutzer haette einen halb freigegebenen Tag, den ihm keine Oberflaeche erklaeren kann.
         */
        internal fun tagVon(spanne: ShiftSpan, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(spanne.alarmTriggerTime).atZone(zone).toLocalDate()

        /**
         * Entfernt jede Spanne, deren Kalendertag freigegeben ist - die EINE Stelle, an der aus
         * einer Freigabe eine Wirkung auf Dimmer und "Nicht stoeren" wird.
         *
         * **Der uebrig bleibende Tag sieht fuer die Fensterlogik aus wie ein echter freier Tag**,
         * und das ist Absicht: `DimWindowResolver.buildRuleSpans` wendet dann die FREI-Regel an,
         * `buildDefaultNightSpans` den Nacht-Standard. Wer hier eine Sonderbehandlung "gar kein
         * Dimmen" einbaut, baut einen dritten Tageszustand, den die Regelliste nicht anzeigen kann.
         */
        internal fun filtereSpannen(
            spannen: List<ShiftSpan>,
            freieTage: Set<LocalDate>,
            zone: ZoneId
        ): List<ShiftSpan> {
            if (freieTage.isEmpty()) return spannen
            return spannen.filterNot { tagVon(it, zone) in freieTage }
        }
    }

    /**
     * **Degradationsrichtung: Lesefehler -> LEERE Menge, also "kein Tag ist freigegeben".**
     *
     * Dieselbe Abwaegung wie bei `MasterPausePrefs`: ein faelschlich gestellter Wecker klingelt
     * hoerbar und wird abgestellt, ein faelschlich unterdrueckter ist STILL und faellt erst beim
     * Verschlafen auf. Wer hier auf "alles frei" degradiert, hat den stillen Ausfall gebaut.
     *
     * Der `ReplaceFileCorruptionHandler` des [MainDataStore] faengt nur eine `CorruptionException`;
     * eine IOException reicht DataStore durch - deshalb hier ein eigenes `.catch`. Der Fehlerfall
     * wird als solcher geloggt, sonst ist er im Log von "keine Freigabe gesetzt" nicht zu
     * unterscheiden.
     */
    val freieTage: Flow<Set<LocalDate>> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.SHIFT,
                "Freigegebene Tage nicht lesbar - degradiert auf KEINE Freigabe (lieber wecken als still bleiben)",
                e
            )
            emit(emptyPreferences())
        }
        .map { prefs ->
            // Beim LESEN mitfiltern, nicht nur beim Aufraeumen in der 6h-Wartung: laeuft die
            // Wartung mal nicht (Master-Pause, Geraet aus), stuende ein abgelaufener Tag sonst
            // weiter in der Liste - und eine Freigabe fuer gestern ist eine Anzeige ohne Wirkung.
            prune(prefs[KEY_FREIE_TAGE] ?: emptySet(), LocalDate.now())
                .mapNotNull { parseOderNull(it) }
                .toSet()
        }

    suspend fun freieTageNow(): Set<LocalDate> = freieTage.first()

    suspend fun istFreigegeben(datum: LocalDate): Boolean = datum in freieTageNow()

    /**
     * Read-Modify-Write INNERHALB von `edit{}` - wie `DimOverlayPrefs` es fuer seine
     * Ausschlusslisten tut. Ausserhalb waere zwischen Lesen und Schreiben Platz fuer einen
     * zweiten Schreiber, und eine gerade gesetzte Freigabe verschwaende wieder.
     */
    suspend fun freigeben(datum: LocalDate) {
        dataStore.edit { p ->
            p[KEY_FREIE_TAGE] = (p[KEY_FREIE_TAGE] ?: emptySet()) + datum.toString()
        }
        Logger.business(LogTags.SHIFT, "🏖️ Tag freigegeben: $datum")
    }

    suspend fun zuruecknehmen(datum: LocalDate) {
        dataStore.edit { p ->
            p[KEY_FREIE_TAGE] = (p[KEY_FREIE_TAGE] ?: emptySet()) - datum.toString()
        }
        Logger.business(LogTags.SHIFT, "🏖️ Freigabe zurueckgenommen: $datum")
    }

    /**
     * Raeumt abgelaufene Freigaben weg. Anders als beim [ShiftSpanStore] reicht "beim Schreiben
     * mitraeumen" hier nicht: geschrieben wird nur, wenn der Nutzer einen Tag anfasst - wer
     * monatelang keinen freigibt, raeumt nie auf. Deshalb ruft die 6h-Wartung das hier.
     */
    suspend fun aufraeumen(heute: LocalDate) {
        dataStore.edit { p ->
            val vorher = p[KEY_FREIE_TAGE] ?: emptySet()
            val nachher = prune(vorher, heute)
            if (vorher != nachher) {
                p[KEY_FREIE_TAGE] = nachher
                Logger.d(
                    LogTags.SHIFT,
                    "Freigegebene Tage aufgeraeumt: ${vorher.size - nachher.size} abgelaufen verworfen"
                )
            }
        }
    }
}
