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
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** #142: die beiden Quellen einer Freigabe, getrennt fuer die ANZEIGE. Wirkung hat nur [alle]. */
data class FreigabeQuellen(val hand: Set<LocalDate>, val kalender: Set<LocalDate>) {
    val alle: Set<LocalDate> get() = hand + kalender

    companion object {
        val LEER = FreigabeQuellen(emptySet(), emptySet())
    }
}

/** #142: was ein Sync am Kalender-Schluessel geaendert hat (Grundlage fuer Meldung und Neben-Ketten). */
data class KrankTageAenderung(val hinzu: Set<LocalDate>, val weg: Set<LocalDate>) {
    val geaendert: Boolean get() = hinzu.isNotEmpty() || weg.isNotEmpty()

    companion object {
        val KEINE = KrankTageAenderung(emptySet(), emptySet())
    }
}

/**
 * #142: ein Weckzeit-Anker - [anker] ist der Tag der WECKZEIT einer Schicht, die an [quelle] (einem
 * Krank-Tag laut Kalender) BEGINNT. Beispiel: Nachtschicht Di 00:30, Wecker Mo 23:30 -> Mo<-Di.
 */
data class KrankAnker(val anker: LocalDate, val quelle: LocalDate)

/**
 * Die vom Nutzer FREIGEGEBENEN Kalendertage: laut Dienstplan steht eine Schicht, der Dienst findet
 * aber nicht statt (Freigabe, Tausch, Krankmeldung). **Ueberspringen betrifft den WECKER, eine
 * Freigabe den DIENST** - deshalb ein eigener Speicher. **Gebunden an das DATUM**, nicht an
 * Schicht oder Kalender-Kennung: der Dienstplan-Feed rotiert seine Event-IDs, ein Datum ueberlebt
 * das. Gilt fuer den GANZEN Kalendertag (zwei Schichten: beide fallen weg).
 *
 * **#142: ZWEI Quellen, EINE Wirkung.** Neben den Hand-Freigaben (`freigegebene_tage`, schreibt NUR
 * der Nutzer ueber `TagFreigabeUseCase`) gibt es die Kalender-Krank-Tage (`krank_tage_kalender`,
 * schreibt NUR `AlarmUseCase.syncAlarms()`). [freieTage] liefert die VEREINIGUNG - jeder bisherige
 * Leser wirkt dadurch ohne eigenen Zweig. Getrennte Schluessel, weil ein verschwundener
 * Kalendertermin vom Nutzerwillen unterscheidbar bleiben muss. Ein Lesefehler des Stores degradiert
 * BEIDE Quellen auf die leere Menge, ein unlesbarer Einzeleintrag faellt nur in seinem Schluessel
 * weg (im Zweifel klingeln).
 * Hergang: Skill cfalarm-wecker-und-boot, tag-freigeben.md.
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
         * #142: die zweite Quelle - Kalendertage mit einem Krankmeldungs-Termin. Geschrieben NUR von
         * `syncAlarms()` ([setzeKalenderKrankTage]); der Hand-Schluessel gehoert dagegen NUR dem
         * Nutzer. Zwei Schluessel, weil ein verschwundener Termin vom Nutzerwillen unterscheidbar
         * bleiben muss (Spec: verworfene Variante b). Oeffentlich fuer `ConfigBackupFormat`.
         */
        const val KEY_KRANK_KALENDER_NAME = "krank_tage_kalender"

        private val KEY_KRANK_KALENDER = stringSetPreferencesKey(KEY_KRANK_KALENDER_NAME)

        /**
         * #142: die ENDEN der zeitgebundenen Krank-Termine (ISO-Datum-Uhrzeit, lokale Wanduhr).
         * Der Kalenderabruf beginnt bei JETZT, nicht bei Tagesbeginn - ein heute schon beendeter
         * Termin "K 06:00-08:00" fehlt in jeder spaeteren Lesung. Ohne dieses Gedaechtnis hiesse
         * "fehlt" fuer HEUTE "geloescht", heute waere wieder Diensttag, und die Nachtschicht am
         * Abend klingelte trotz Krankmeldung. Abgelaufen ist nicht geloescht (dieselbe
         * Unterscheidung wie `expiredEventIds`). Oeffentlich fuer `ConfigBackupFormat`.
         */
        const val KEY_KRANK_ZEITGEBUNDEN_ENDE_NAME = "krank_tage_kalender_zeitgebunden_ende"

        private val KEY_KRANK_ZEITGEBUNDEN_ENDE = stringSetPreferencesKey(KEY_KRANK_ZEITGEBUNDEN_ENDE_NAME)

        /**
         * #142: Krank-Tage, deren Meldung noch aussteht. Der Diff eines Syncs kommt nur EINMAL
         * vorbei - scheitert oder endet der Lauf danach, saehe der naechste den Tag schon im
         * Schluessel, und der Wecker verschwaende ohne jede Nachricht (genau das, wogegen
         * `KrankmeldungNotifier` gebaut ist). Vorgemerkt im Lock, quittiert erst nach der
         * zugestellten Meldung. Oeffentlich fuer `ConfigBackupFormat`.
         */
        const val KEY_KRANK_ZU_MELDEN_NAME = "krank_tage_zu_melden"

        private val KEY_KRANK_ZU_MELDEN = stringSetPreferencesKey(KEY_KRANK_ZU_MELDEN_NAME)

        /**
         * #142: die Herkunft der Weckzeit-Anker ("anker>quelle", siehe [KrankAnker]).
         *
         * WARUM ES DAS GIBT: Der Hauptfall des Ankers ist "TimeOffice ersetzt die Nachtschicht durch
         * K" - dann kennt nur noch der gestellte Wecker seinen Schichtbeginn. Genau diesen Wecker
         * loescht der Lauf aber, der den Anker setzt. Ohne Gedaechtnis fiele der Vortag beim
         * NAECHSTEN Sync wieder heraus: ein anderer Wecker dieses Tages entstuende neu (samt "Neue
         * Schicht erkannt"), und ob er klingelte, hinge nur davon ab, ob zwischen zwei Syncs seine
         * Weckzeit lag. Ein gemerkter Anker gilt, solange seine Quelle Krank-Tag bleibt - nicht
         * laenger: faellt die Krankmeldung weg, faellt der Vortag mit (im Zweifel klingeln).
         * Oeffentlich fuer `ConfigBackupFormat`.
         */
        const val KEY_KRANK_ANKER_NAME = "krank_tage_kalender_anker"

        private val KEY_KRANK_ANKER = stringSetPreferencesKey(KEY_KRANK_ANKER_NAME)

        private const val ANKER_TRENNER = ">"

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

        /**
         * #142: Mischsemantik des Kalender-Schluessels - rein und ohne DataStore testbar.
         *
         * @param horizontTag der Kalendertag, der das Abruf-Horizontende enthaelt; `null` bei leerer
         *        Eventliste oder Kalender-Abwahl.
         * @param heuteAbgelaufen ein zeitgebundener Krank-Termin von HEUTE ist schon vorbei
         *        ([heuteAbgelaufen]) - dann kann ihn keine Lesung mehr enthalten, sein Fehlen ist
         *        kein Loeschen. Gilt nur fuer eine echte Lesung (`horizontTag != null`).
         */
        internal fun mischeKalenderKrank(
            alt: Set<String>,
            neu: Set<LocalDate>,
            heute: LocalDate,
            horizontTag: LocalDate?,
            heuteAbgelaufen: Boolean = false
        ): Set<String> {
            val behalten = alt.filter { roh ->
                // Unlesbare Eintraege fliegen raus - sie duerfen keinen Tag freigeben.
                val datum = parseOderNull(roh) ?: return@filter false
                // Tage VOR heute bleiben: Rueckschau fuer Naechte ueber Mitternacht, prune raeumt sie.
                datum < heute ||
                    // HEUTE bleibt, wenn sein zeitgebundener Termin schon vorbei ist: der Abruf
                    // beginnt bei jetzt, der Termin KANN nicht mehr darin stehen (abgelaufen ist
                    // nicht geloescht). Ein ganztaegiger Termin ist bis 23:59 in jeder Lesung
                    // sichtbar - fuer ihn gilt weiter Review M2 (weg = Diensttag).
                    (heuteAbgelaufen && horizontTag != null && datum == heute) ||
                    // Ab dem Horizont-Tag bleibt der alte Stand: nicht gelesen ist nicht geloescht
                    // (#51). Der Horizont-Tag selbst zaehlt als NICHT gelesen (Review S2, `>=` wie
                    // SyncHorizonStore.istJenseitsDesAbrufs). Ohne Horizont: alles ab heute weg.
                    (horizontTag != null && datum >= horizontTag)
            }
            // Dazwischen (heute bis vor den Horizont-Tag) ERSETZT der neue Stand den alten - auch
            // HEUTE: ist der Termin weg, ist heute wieder Diensttag (Review M2, im Zweifel klingeln).
            return prune((behalten + neu.map { it.toString() }).toSet(), heute)
        }

        /**
         * #142: Welche Weckzeit-Anker gelten nach diesem Lauf? Die NEUEN dieses Laufs, dazu jeder
         * gemerkte, dessen Quelle im gemischten Kalender-Schluessel [basis] steht (also weiter
         * Krank-Tag ist - gelesen oder, hinter dem Horizont, unangetastet). Faellt die Quelle weg,
         * faellt der Anker mit. Verstrichenes (Anker vor gestern) und Unlesbares fliegt raus.
         */
        internal fun mischeAnker(
            alt: Set<String>,
            neu: Set<KrankAnker>,
            basis: Set<String>,
            heute: LocalDate
        ): Set<KrankAnker> {
            val gueltigAlt = alt.mapNotNull { parseAnkerOderNull(it) }
                .filter { it.quelle.toString() in basis }
            val grenze = heute.minusDays(RUECKSCHAU_TAGE)
            return (gueltigAlt + neu).filter { !it.anker.isBefore(grenze) }.toSet()
        }

        internal fun ankerAlsText(anker: KrankAnker): String = "${anker.anker}$ANKER_TRENNER${anker.quelle}"

        /** `null` statt Wurf, wie [parseOderNull]. */
        internal fun parseAnkerOderNull(roh: String): KrankAnker? {
            val teile = roh.split(ANKER_TRENNER)
            if (teile.size != 2) return null
            val anker = parseOderNull(teile[0]) ?: return null
            val quelle = parseOderNull(teile[1]) ?: return null
            return KrankAnker(anker, quelle)
        }

        /** Raeumt verstrichene und unlesbare Anker - Gegenstueck zu [prune] fuer [KEY_KRANK_ANKER_NAME]. */
        internal fun pruneAnker(roh: Set<String>, heute: LocalDate): Set<String> {
            val grenze = heute.minusDays(RUECKSCHAU_TAGE)
            return roh.filter { eintrag ->
                val anker = parseAnkerOderNull(eintrag)
                anker != null && !anker.anker.isBefore(grenze)
            }.toSet()
        }

        /**
         * #142: Ist ein gemerkter zeitgebundener Krank-Termin HEUTE schon zu Ende? Dann fehlt er
         * in jeder Lesung ab jetzt, und das ist kein Loeschen ([mischeKalenderKrank]). Endet er
         * heute erst spaeter und fehlt trotzdem, wurde er geloescht - der Tag faellt weg.
         */
        internal fun heuteAbgelaufen(enden: Set<String>, jetzt: LocalDateTime): Boolean =
            enden.any { roh ->
                val ende = parseZeitOderNull(roh) ?: return@any false
                ende.toLocalDate() == jetzt.toLocalDate() && !ende.isAfter(jetzt)
            }

        /**
         * #142: die zu merkenden Enden - die zeitgebundenen Termine dieser Lesung, die heute oder
         * spaeter enden, plus die gemerkten von HEUTE, die schon vorbei sind (sonst verloere
         * schon der uebernaechste Sync das Wissen wieder). Aelteres faellt weg.
         */
        internal fun mischeEnden(
            alt: Set<String>,
            neu: Set<LocalDateTime>,
            jetzt: LocalDateTime
        ): Set<String> {
            val heute = jetzt.toLocalDate()
            val vorbeiHeute = alt.filter { roh ->
                val ende = parseZeitOderNull(roh) ?: return@filter false
                ende.toLocalDate() == heute && !ende.isAfter(jetzt)
            }
            return (vorbeiHeute + neu.filter { !it.toLocalDate().isBefore(heute) }.map { it.toString() }).toSet()
        }

        /** `null` statt Wurf, wie [parseOderNull]. */
        internal fun parseZeitOderNull(roh: String): LocalDateTime? = try {
            LocalDateTime.parse(roh)
        } catch (_: Exception) {
            null
        }

        /**
         * #142: geparste Differenz zwischen altem und neuem Kalender-Schluessel. Bewusst ungefiltert:
         * auch das Wegraeumen von gestern zaehlt als Aenderung - harmlos, es armiert die
         * Neben-Ketten hoechstens einmal mehr.
         */
        internal fun aenderungZwischen(alt: Set<String>, neu: Set<String>): KrankTageAenderung {
            val altTage = alt.mapNotNull { parseOderNull(it) }.toSet()
            val neuTage = neu.mapNotNull { parseOderNull(it) }.toSet()
            return KrankTageAenderung(hinzu = neuTage - altTage, weg = altTage - neuTage)
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
         * und das ist Absicht: `DimWindowResolver.buildRuleSpans` wendet dann die FREI-Regel an
         * (bzw. die UNIVERSAL-Regel, falls es keine gibt). Wer hier eine Sonderbehandlung "gar kein
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
     *
     * #142: gilt fuer BEIDE Quellen - ein Lesefehler leert Hand- und Kalender-Menge gleichermassen.
     */
    val freigabeQuellen: Flow<FreigabeQuellen> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.SHIFT,
                "Freigegebene Tage nicht lesbar - degradiert auf KEINE Freigabe (beide Quellen; lieber wecken als still bleiben)",
                e
            )
            emit(emptyPreferences())
        }
        .map { prefs ->
            FreigabeQuellen(
                hand = leseTage(prefs[KEY_FREIE_TAGE]),
                kalender = leseTage(prefs[KEY_KRANK_KALENDER])
            )
        }

    /**
     * #142: die VEREINIGUNG beider Quellen - alle bisherigen Leser (Gate, Backstop,
     * Snooze-Backstop, Dimmer, DND, Rufbereitschaft) wirken dadurch ohne eigenen Zweig.
     */
    val freieTage: Flow<Set<LocalDate>> = freigabeQuellen.map { it.alle }

    suspend fun freieTageNow(): Set<LocalDate> = freieTage.first()

    suspend fun freigabeQuellenNow(): FreigabeQuellen = freigabeQuellen.first()

    /**
     * Beim LESEN mitfiltern, nicht nur beim Aufraeumen in der 6h-Wartung: laeuft die Wartung mal
     * nicht (Master-Pause, Geraet aus), stuende ein abgelaufener Tag sonst weiter in der Liste -
     * und eine Freigabe fuer gestern ist eine Anzeige ohne Wirkung.
     */
    private fun leseTage(roh: Set<String>?): Set<LocalDate> =
        prune(roh ?: emptySet(), LocalDate.now()).mapNotNull { parseOderNull(it) }.toSet()

    /**
     * Read-Modify-Write innerhalb von `edit{}` - sonst verschwindet eine parallel gesetzte Freigabe.
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
     * #142: nimmt NUR Hand-Freigaben zurueck (Kalender-Tage gehoeren dem Sync), mehrere Tage in
     * EINEM `edit{}`. Eigener Name statt Ueberladung von [zuruecknehmen]: ein
     * `whenever(store.zuruecknehmen(any()))` in den Tests waere sonst mehrdeutig.
     */
    suspend fun zuruecknehmenAlle(tage: Set<LocalDate>) {
        if (tage.isEmpty()) return
        dataStore.edit { p ->
            p[KEY_FREIE_TAGE] = (p[KEY_FREIE_TAGE] ?: emptySet()) - tage.map { it.toString() }.toSet()
        }
        Logger.business(LogTags.SHIFT, "🏖️ Freigaben zurueckgenommen: ${tage.sorted()}")
    }

    /**
     * #142: EINZIGER Schreiber des Kalender-Schluessels ist `AlarmUseCase.syncAlarms()`. EIN
     * `edit{}` (Read-Modify-Write), Mischsemantik in [mischeKalenderKrank]. Geschrieben wird nur
     * bei einer echten Aenderung.
     *
     * @param neu die Krank-Tage dieser Lesung, INKLUSIVE der Anker-Tage dieses Laufs.
     * @param zeitgebundeneEnden die Enden der zeitgebundenen Krank-Termine DIESER Lesung - im
     *        selben `edit{}` gemerkt, damit ein heute schon beendeter Termin heute nicht als
     *        geloescht gilt ([KEY_KRANK_ZEITGEBUNDEN_ENDE_NAME]).
     * @param anker die Weckzeit-Anker DIESES Laufs - gemerkt, damit der Vortag auch dann frei
     *        bleibt, wenn der Wecker, aus dem der Anker stammt, geloescht ist ([KEY_KRANK_ANKER_NAME]).
     * @return was sich am Kalender-Schluessel geaendert hat.
     */
    suspend fun setzeKalenderKrankTage(
        neu: Set<LocalDate>,
        heute: LocalDate,
        horizontTag: LocalDate?,
        zeitgebundeneEnden: Set<LocalDateTime> = emptySet(),
        jetzt: LocalDateTime = LocalDateTime.now(),
        anker: Set<KrankAnker> = emptySet()
    ): KrankTageAenderung {
        var aenderung = KrankTageAenderung.KEINE
        dataStore.edit { p ->
            val alt = p[KEY_KRANK_KALENDER] ?: emptySet()
            val altEnden = p[KEY_KRANK_ZEITGEBUNDEN_ENDE] ?: emptySet()
            val altAnker = p[KEY_KRANK_ANKER] ?: emptySet()
            val basis = mischeKalenderKrank(alt, neu, heute, horizontTag, heuteAbgelaufen(altEnden, jetzt))
            val gueltigeAnker = mischeAnker(altAnker, anker, basis, heute)
            // Gemerkte Anker halten ihren Tag auch ohne den (inzwischen geloeschten) Wecker frei.
            val gemischt = prune(basis + gueltigeAnker.map { it.anker.toString() }, heute)
            if (gemischt != alt) p[KEY_KRANK_KALENDER] = gemischt
            val ankerText = gueltigeAnker.map { ankerAlsText(it) }.toSet()
            if (ankerText != altAnker) p[KEY_KRANK_ANKER] = ankerText
            val enden = mischeEnden(altEnden, zeitgebundeneEnden, jetzt)
            if (enden != altEnden) p[KEY_KRANK_ZEITGEBUNDEN_ENDE] = enden
            aenderung = aenderungZwischen(alt, gemischt)
            // Ein Tag, der wieder Diensttag ist, braucht keine Krankmeldung mehr.
            if (aenderung.weg.isNotEmpty()) {
                val offen = p[KEY_KRANK_ZU_MELDEN] ?: emptySet()
                val rest = offen - aenderung.weg.map { it.toString() }.toSet()
                if (rest != offen) p[KEY_KRANK_ZU_MELDEN] = rest
            }
        }
        if (aenderung.geaendert) {
            Logger.business(
                LogTags.SHIFT,
                "🤒 Krank laut Kalender: +${aenderung.hinzu.sorted()} -${aenderung.weg.sorted()}"
            )
        }
        return aenderung
    }

    /**
     * #142: merkt Krank-Tage fuer die Meldung vor - im Lock, direkt nach dem Schreiben des
     * Kalender-Schluessels. Gemeldet und quittiert wird erst nach einem erfolgreichen Sync
     * ([quittiereKrankMeldung]); scheitert oder endet der Lauf vorher, holt der naechste es nach.
     */
    suspend fun vormerkenKrankMeldung(tage: Set<LocalDate>) {
        if (tage.isEmpty()) return
        dataStore.edit { p ->
            p[KEY_KRANK_ZU_MELDEN] = (p[KEY_KRANK_ZU_MELDEN] ?: emptySet()) + tage.map { it.toString() }
        }
    }

    /**
     * #142: die noch nicht gemeldeten Krank-Tage ab [heute]. Lesefehler -> leere Menge: eine
     * ausgefallene Nachmeldung ist laestig, ein Wurf hier faerbte den Sync.
     */
    suspend fun offeneKrankMeldungNow(heute: LocalDate): Set<LocalDate> =
        dataStore.data
            .catch { e ->
                Logger.w(LogTags.SHIFT, "Vorgemerkte Krankmeldungen nicht lesbar - keine Nachmeldung", e)
                emit(emptyPreferences())
            }
            .first()[KEY_KRANK_ZU_MELDEN]
            .orEmpty()
            .mapNotNull { parseOderNull(it) }
            .filter { !it.isBefore(heute) }
            .toSet()

    /** #142: die zugestellten (oder nicht mehr meldenswerten) Tage aus der Vormerkung nehmen. */
    suspend fun quittiereKrankMeldung(tage: Set<LocalDate>) {
        if (tage.isEmpty()) return
        dataStore.edit { p ->
            val offen = p[KEY_KRANK_ZU_MELDEN] ?: return@edit
            p[KEY_KRANK_ZU_MELDEN] = offen - tage.map { it.toString() }.toSet()
        }
    }

    /**
     * Raeumt abgelaufene Freigaben weg. Anders als beim [ShiftSpanStore] reicht "beim Schreiben
     * mitraeumen" hier nicht: geschrieben wird nur, wenn der Nutzer einen Tag anfasst - wer
     * monatelang keinen freigibt, raeumt nie auf. Deshalb ruft die 6h-Wartung das hier.
     *
     * #142 (Review S5): raeumt BEIDE Schluessel - der Sync behaelt Kalender-Tage vor heute bewusst
     * (Rueckschau), ohne Prune hier blieben sie liegen. Ebenso die Vormerkung der Meldungen und die
     * gemerkten Weckzeit-Anker.
     */
    suspend fun aufraeumen(heute: LocalDate) {
        dataStore.edit { p ->
            for (key in listOf(KEY_FREIE_TAGE, KEY_KRANK_KALENDER, KEY_KRANK_ZU_MELDEN, KEY_KRANK_ANKER)) {
                val vorher = p[key] ?: emptySet()
                val nachher = if (key == KEY_KRANK_ANKER) pruneAnker(vorher, heute) else prune(vorher, heute)
                if (vorher != nachher) {
                    p[key] = nachher
                    Logger.d(
                        LogTags.SHIFT,
                        "Freigegebene Tage aufgeraeumt (${key.name}): ${vorher.size - nachher.size} abgelaufen verworfen"
                    )
                }
            }
        }
    }
}
