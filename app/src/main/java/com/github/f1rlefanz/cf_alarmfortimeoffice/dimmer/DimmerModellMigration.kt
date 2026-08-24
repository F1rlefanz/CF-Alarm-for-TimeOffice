package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einmalige, versionierte Überführung der ALTEN Dimmer-Konfiguration (drei Fenster-Quellen,
 * drei Schalter) in das Ein-Modell (nur noch [DimRule]n, ein Schalter [DimOverlayPrefs.Toggles]).
 *
 * WARUM ES SIE GEBEN MUSS: Der Umbau hat die Quellen „Wellness/Wind-down" und „Nacht-Standard"
 * ersatzlos ausgebaut, ihre Preference-Schlüssel aber absichtlich im Store liegen lassen. Ohne
 * diese Migration liest sie niemand mehr — der Dimmer eines bestehenden Nutzers wäre nach dem
 * Update lautlos aus (bzw. schaltete auf Regeln um, die er nie eingeschaltet hatte). Beides ist
 * ein Zustand, den er nicht bestellt hat.
 *
 * DIE ÜBERSETZUNG (Belege in `DimmerModellMigrationTest`, das jede Variante gegen die
 * eingefrorene `DimmerAltmodellReferenz` stellt):
 * - `dim_enabled` = true, sobald EINE der drei alten Quellen an war.
 * - Wellness an → Fenster `ALARM −windDown` → `ALARM +0`.
 * - Nacht-Standard an → Fenster `CLOCK nightStart` → `ALARM_SONST_CLOCK nightFreeEnd`
 *   (genau die Semantik, für die der Ende-Anker gebaut wurde: eine Nacht je Kalendertag, ohne
 *   Rückwärts-/Vorwärts-Fensterpaar und ohne Folgetag-Sonderfall).
 * - Seine Ausnahme-Schichten → je eine Regel OHNE Fenster (= Unterdrückung).
 *
 * DIE AUSSCHLIESSLICHKEIT DES ALTMODELLS IST DER GANZE WITZ, und sie ist auch die gefährlichste
 * Stelle: Der Nacht-Standard wirkte NUR an Tagen, die keine Regel ohnehin schon abdeckte, und nur
 * solange die Quelle „Regeln" aktiv war. Wer eine aktive UNIVERSAL-Regel hatte, bei dem war der
 * Nacht-Standard mitsamt seinen Ausnahmen VOLLSTÄNDIG WIRKUNGSLOS — auch wenn sein Schalter an
 * stand. Aus einer solchen Ausnahme-Schicht hier eine leere Regel zu machen, wäre deshalb kein
 * Übertragen, sondern eine NEUE Unterdrückung: sie wäre sofort scharf und nähme der UNIVERSAL-Regel
 * genau die Nächte weg, in denen sie heute dimmt. Deshalb [nachtStandardWirksam].
 *
 * FAIL-SAFE: Scheitert irgendetwas, wird der Dimmer ausgeschaltet (heller Bildschirm, Wecker
 * unberührt) und der Marker NICHT gesetzt — beim nächsten Start wird es erneut versucht. Ein halb
 * geschriebener Regelbestand ist dabei ungefährlich, weil `dim_enabled` erst ganz am Ende und nur
 * im Erfolgsfall auf true geht: bis dahin erzeugt kein einziger Fenster-Berechnungslauf ein Fenster.
 *
 * LÄUFT NIEMALS BEIM BAUEN DES HILT-GRAPHEN, sondern ausschließlich aus [migriereEinmalig] heraus.
 * Ein Zugriff auf den [MainDataStore] im Property-Initializer einer Graph-Klasse tötet den
 * Direct-Boot-Prozess, der die Wecker hält.
 *
 * ZWEI ANLÄSSE, NICHT NUR DER APP-START: `MainActivity.onCreate` (der schnelle Weg) UND der
 * 6h-Wartungslauf (`AlarmMaintenanceService.rescheduleSideChannels`). Nur der App-Start zu nehmen
 * war ein Fehler: `dim_enabled` ist ein NEUER Schlüssel, den es auf einem Bestandsgerät nicht gibt,
 * und sein Default ist `false` — bis die Migration lief, steigt `computeWindows()` sofort mit
 * leerer Fensterliste aus. Wer die App nur zum Wecken benutzt und sie nach einem
 * Play-Auto-Update tagelang nicht öffnet, hätte also ab der ersten Nacht nach dem Update gar kein
 * Dimmen mehr — und bei DND-Modus 1 fiele das Nachtfenster von „Nicht stören" gleich mit weg, ohne
 * dass er etwas umgestellt hat. Der Marker macht jeden weiteren Aufruf zum No-op, die Reihenfolge
 * der beiden Anlässe ist also gleichgültig.
 *
 * DESHALB DAS ENTSPERRUNGS-GATE: Der [MainDataStore] liegt im CE-Storage und liefert vor der ersten
 * Entsperrung still LEERE Preferences, ohne zu werfen (siehe `AlarmRepository`). Eine Migration in
 * diesem Zustand sähe eine leere Alt-Konfiguration, schriebe `dim_enabled = false` und setzte den
 * Marker — der Dimmer wäre dauerhaft aus, und niemand sähe je wieder nach. `MainActivity` kann gar
 * nicht vor der Entsperrung laufen; der Wartungsdienst ist nicht `directBootAware`, aber auf diese
 * Eigenschaft eines ANDEREN Aufrufers darf sich eine so folgenschwere Entscheidung nicht verlassen.
 */
@Singleton
class DimmerModellMigration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MainDataStore private val dataStore: DataStore<Preferences>,
    private val dimRuleUseCase: DimRuleUseCase,
    private val dimSchedule: DimScheduleUseCase
) {
    companion object {
        /**
         * Der Versions-Marker. Absichtlich ein Int und kein Boolean: eine künftige zweite
         * Modelländerung braucht eine ZWEITE Stufe, und die soll sich von „noch nie gelaufen"
         * unterscheiden lassen.
         *
         * Geräte-lokal, deshalb in `ConfigBackupFilter` ausgeschlossen: ein importierter Marker
         * würde auf einem noch nicht migrierten Gerät die Migration überspringen und dessen alte
         * Konfiguration dauerhaft unlesbar machen.
         */
        const val KEY_MARKER_NAME = "dim_modell_migration"
        private val KEY_MARKER = intPreferencesKey(KEY_MARKER_NAME)

        /** Stand, den [migriereEinmalig] herstellt. */
        internal const val STAND = 1

        private val KEY_DIM_ON = booleanPreferencesKey("dim_enabled")

        // Die ALTEN Schlüssel. Sie werden hier gelesen und danach nie wieder - geloescht werden
        // sie bewusst nicht (ein Downgrade soll den alten Zustand noch vorfinden).
        private val KEY_WELLNESS = booleanPreferencesKey("dim_wellness_enabled")
        private val KEY_RULES_ON = booleanPreferencesKey("dim_rules_enabled")
        private val KEY_NIGHT_ON = booleanPreferencesKey("dim_night_default_enabled")
        private val KEY_WINDDOWN_MIN = intPreferencesKey("dim_winddown_min")
        private val KEY_NIGHT_START_MIN = intPreferencesKey("dim_night_default_start_min")
        private val KEY_NIGHT_FREE_END_MIN = intPreferencesKey("dim_night_default_free_end_min")
        private val KEY_NIGHT_STRENGTH = intPreferencesKey("dim_night_default_strength")
        private val KEY_NIGHT_WARMTH = intPreferencesKey("dim_night_default_warmth")
        private val KEY_NIGHT_EXCLUDED = stringSetPreferencesKey("dim_night_default_excluded_shifts")
        private val KEY_STRENGTH = intPreferencesKey("dim_strength")
        private val KEY_WARMTH = intPreferencesKey("dim_warmth")

        // Die Grenzen des Altmodells, wortgleich uebernommen: die Migration muss denselben Wert
        // sehen, den der alte Scheduler gesehen haette - nicht den rohen Speicherwert.
        private const val DEFAULT_WINDDOWN_MIN = 120
        private const val WINDDOWN_MIN_LIMIT = 15
        private const val WINDDOWN_MAX_LIMIT = 8 * 60
        private const val DEFAULT_NIGHT_START_MIN = 22 * 60
        private const val DEFAULT_NIGHT_FREE_END_MIN = 7 * 60

        /**
         * Präfix der von der Migration ANGELEGTEN Regeln. Ihre IDs sind deterministisch, damit ein
         * zweiter Lauf dieselbe Regel per `upsert` ERSETZT statt sie zu verdoppeln - die Migration
         * ist dadurch auch dann idempotent, wenn sie nach einem Fehlschlag erneut anläuft.
         */
        internal const val MIGRATION_ID_PRAEFIX = "dimrule_migriert_"
        internal const val ID_UNIVERSAL = MIGRATION_ID_PRAEFIX + "universal"

        internal fun ausnahmeId(shiftName: String): String =
            MIGRATION_ID_PRAEFIX + "ausnahme_" + shiftName.lowercase().replace(Regex("[^a-z0-9]"), "_")

        /** Liest die alten Werte MIT den Klemmungen des Altmodells (siehe die Konstanten oben). */
        internal fun lies(p: Preferences): AltZustand = AltZustand(
            wellnessAn = p[KEY_WELLNESS] ?: false,
            regelnAn = p[KEY_RULES_ON] ?: false,
            nachtStandardAn = p[KEY_NIGHT_ON] ?: false,
            windDownMinuten = (p[KEY_WINDDOWN_MIN] ?: DEFAULT_WINDDOWN_MIN)
                .coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT),
            globalStrength = (p[KEY_STRENGTH] ?: DimOverlayPrefs.DEFAULT_STRENGTH)
                .coerceIn(0, DimOverlayPrefs.STRENGTH_MAX),
            globalWarmth = (p[KEY_WARMTH] ?: DimOverlayPrefs.DEFAULT_WARMTH)
                .coerceIn(0, DimOverlayPrefs.WARMTH_MAX),
            nachtStartMinuten = (p[KEY_NIGHT_START_MIN] ?: DEFAULT_NIGHT_START_MIN).coerceIn(0, 24 * 60 - 1),
            nachtEndeMinuten = (p[KEY_NIGHT_FREE_END_MIN] ?: DEFAULT_NIGHT_FREE_END_MIN).coerceIn(0, 24 * 60 - 1),
            nachtStrength = (p[KEY_NIGHT_STRENGTH] ?: DimOverlayPrefs.DEFAULT_STRENGTH)
                .coerceIn(0, DimOverlayPrefs.STRENGTH_MAX),
            nachtWarmth = (p[KEY_NIGHT_WARMTH] ?: DimOverlayPrefs.DEFAULT_WARMTH)
                .coerceIn(0, DimOverlayPrefs.WARMTH_MAX),
            nachtAusnahmen = p[KEY_NIGHT_EXCLUDED] ?: emptySet()
        )

        /**
         * Die eigentliche Übersetzung - rein, ohne Speicher, damit sie gegen die eingefrorene
         * `DimmerAltmodellReferenz` gestellt werden kann.
         *
         * REIHENFOLGE DER ÜBERLEGUNGEN (jede einzelne stammt aus der Ausschließlichkeit des Altmodells):
         *
         * 1. **Waren die Regeln aus, ist der vorhandene Regelbestand INERT gewesen.** Er darf durch
         *    den einen neuen Schalter nicht plötzlich scharf werden - deshalb werden die Regeln in
         *    diesem Fall deaktiviert (nicht gelöscht: der Nutzer soll sie wiederfinden und einschalten
         *    können). Das gilt nur, wenn der Dimmer überhaupt anspringt; war ALLES aus, bleibt der
         *    Bestand unangetastet, weil `dimEnabled = false` ohnehin kein Fenster erzeugt und der
         *    Nutzer beim nächsten Einschalten seine Regeln vorfinden soll.
         * 2. **Der Nacht-Standard war wirkungslos, sobald eine aktive UNIVERSAL-Regel existierte** -
         *    die deckt JEDEN Tag ab (Schicht-Tag über UNIVERSAL, freier Tag über UNIVERSAL), und ein
         *    von einer Regel gedeckter Tag fiel aus dem Nacht-Standard heraus. Dann wird weder sein
         *    Fenster noch eine seiner Ausnahmen übernommen. Siehe Klassen-KDoc.
         * 3. **Wellness lief als eigene Quelle NEBEN allem anderen** - auch an Tagen, die eine Regel
         *    unterdrückt hat. Sein Fenster wird deshalb an jede wirksame Regel angehängt - mit einer
         *    Ausnahme, die keine Nachlässigkeit ist: an eine Regel mit LEERER Fensterliste NICHT.
         *    Ihre Leere ist das einzige Merkmal, an dem die tagesweite Unterdrückung hängt; ein
         *    angehängtes Fenster nähme sie ihr (Begründung und Preis an der Codestelle).
         * 4. **Was keine Regel traf, traf im Altmodell trotzdem Wellness und Nacht-Standard.** Dafür
         *    entsteht eine UNIVERSAL-Regel mit den übernommenen Fenstern - es sei denn, es gibt schon
         *    eine aktive (dann hat Schritt 3 sie bereits ergänzt, und Schritt 2 hat den Nacht-Standard
         *    ohnehin verworfen).
         */
        internal fun plane(alt: AltZustand, bestand: List<DimRule>): Plan {
            val dimEnabled = alt.wellnessAn || alt.regelnAn || alt.nachtStandardAn

            // Eigene Erzeugnisse eines frueheren (fehlgeschlagenen) Laufs zaehlen nicht zum Altbestand -
            // sonst entwertete ein zweiter Lauf die Regeln, die der erste gerade angelegt hat.
            val altbestand = bestand.filterNot { it.id.startsWith(MIGRATION_ID_PRAEFIX) }
            val regelnWirksam = alt.regelnAn && altbestand.any { it.enabled }
            val wirksamerBestand = if (alt.regelnAn) altbestand.filter { it.enabled } else emptyList()
            val universalVorhanden =
                regelnWirksam && wirksamerBestand.any { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
            val nachtStandardWirksam = alt.nachtStandardAn && !universalVorhanden

            val wellnessFenster = if (alt.wellnessAn) {
                DimWindow(
                    startAnchor = DimAnchor.ALARM,
                    startOffsetMinutes = -alt.windDownMinuten,
                    endAnchor = DimAnchor.ALARM,
                    endOffsetMinutes = 0
                )
            } else {
                null
            }
            val nachtFenster = if (nachtStandardWirksam) {
                DimWindow(
                    startAnchor = DimAnchor.CLOCK,
                    startClockMinutes = alt.nachtStartMinuten,
                    endAnchor = DimAnchor.ALARM_SONST_CLOCK,
                    endClockMinutes = alt.nachtEndeMinuten
                )
            } else {
                null
            }

            val geplant = mutableListOf<DimRule>()

            // (1) Regelquelle war aus -> Bestand entwerten, damit der eine neue Schalter ihn nicht scharf macht.
            if (!alt.regelnAn && dimEnabled) {
                altbestand.filter { it.enabled }.forEach { geplant += it.copy(enabled = false) }
            }

            // (3) Wellness an jede DIMMENDE Regel anhaengen (contains-Pruefung = Idempotenz).
            //
            // EINE REGEL MIT LEERER FENSTERLISTE BLEIBT LEER, und das ist keine Nachlaessigkeit,
            // sondern die Bedingung dafuer, dass die Unterdrueckung die Migration ueberlebt:
            // `DimWindowResolver.regelFuerTag()` erkennt die Nachtdienst-Ausnahme an genau EINEM
            // Merkmal - `windows.isEmpty()` - und laesst sie fuer den GANZEN Kalendertag gelten,
            // auch wenn eine ANDERE Schicht desselben Tages eine dimmende Regel traegt
            // (Pruefrunde 8). Haengt man hier "nur" das Wind-down an, ist die Regel nicht mehr
            // leer: die Tages-Prioritaet faellt weg, der Tag wird ueber den normalen Vorrang
            // entschieden, und an einem Tag mit zwei Diensten gewinnt die Regel der FRUEHEREN
            // Schicht - deren Nacht-Fenster dimmt dann ausgerechnet die Nacht, die der Nutzer
            // ausdruecklich ausgenommen hatte.
            //
            // DER PREIS, bewusst gezahlt und in `DimmerModellMigrationTest` mit Zahlen
            // festgehalten ("Abweichung - an einem unterdrueckten Tag entfaellt das Wind-down"):
            // an genau diesen Tagen dimmt das Wind-down nicht mehr, das die alte Wellness-Quelle
            // dort noch legte. Beides zugleich ist im Ein-Modell nicht ausdrueckbar, und die
            // Richtung "bleibt hell" ist die harmlose - "im Zweifel klingeln und hell".
            if (wellnessFenster != null) {
                wirksamerBestand
                    .filter { it.windows.isNotEmpty() }
                    .filterNot { wellnessFenster in it.windows }
                    .forEach { geplant += it.copy(windows = it.windows + wellnessFenster) }
            }

            // (4) Auffang-Regel fuer alles, was keine Regel traf.
            val universalFenster = listOfNotNull(wellnessFenster, nachtFenster)
            if (universalFenster.isNotEmpty() && !universalVorhanden) {
                // Bei zwei Fenstern in EINER Regel gibt es nur EINE Verdunkelung. Gewaehlt wird die
                // dunklere - genau die, die im Altmodell in der Ueberlappung ohnehin gewonnen haette
                // ("darkest wins", DimWindowResolver.activeSpan). Siehe die dokumentierte Abweichung
                // im Test: ausserhalb der Ueberlappung wird der mildere Teil dadurch dunkler.
                val nachtIstDunkler = alt.nachtStrength > alt.globalStrength ||
                    (alt.nachtStrength == alt.globalStrength && alt.nachtWarmth >= alt.globalWarmth)
                val nimmNacht = nachtFenster != null && (wellnessFenster == null || nachtIstDunkler)
                geplant += DimRule(
                    id = ID_UNIVERSAL,
                    name = when {
                        nachtFenster != null && wellnessFenster != null -> "Nachtruhe und Wind-down (uebernommen)"
                        nachtFenster != null -> "Nachtruhe (uebernommen)"
                        else -> "Wind-down vor dem Wecker (uebernommen)"
                    },
                    shiftPattern = DimRule.SHIFT_UNIVERSAL,
                    enabled = true,
                    windows = universalFenster,
                    strength = if (nimmNacht) alt.nachtStrength else alt.globalStrength,
                    warmth = if (nimmNacht) alt.nachtWarmth else alt.globalWarmth
                )
            }

            // (2) Ausnahme-Schichten des Nacht-Standards - NUR wenn er ueberhaupt gewirkt hat.
            if (nachtStandardWirksam) {
                alt.nachtAusnahmen
                    .filter { it.isNotBlank() }
                    .filterNot { name -> wirksamerBestand.any { it.betrifftSchicht(name) } }
                    .forEach { name ->
                        geplant += DimRule(
                            id = ausnahmeId(name),
                            // Wellness lief auch an ausgeschlossenen Tagen weiter (siehe Punkt 3) -
                            // eine LEERE Fensterliste waere hier also zu viel des Guten.
                            name = "$name: keine Nachtruhe (uebernommen)",
                            shiftPattern = name,
                            enabled = true,
                            windows = listOfNotNull(wellnessFenster),
                            strength = alt.globalStrength,
                            warmth = alt.globalWarmth
                        )
                    }
            }

            return Plan(dimEnabled = dimEnabled, regeln = geplant)
        }

        /** Wie `DimRuleUseCase.betrifftSchicht`: Sondermuster sind keine Schichtnamen. */
        private fun DimRule.betrifftSchicht(shiftName: String): Boolean =
            shiftPattern != DimRule.SHIFT_UNIVERSAL &&
                shiftPattern != DimRule.SHIFT_FREE &&
                shiftPattern.equals(shiftName, ignoreCase = true)
    }

    /** Die gelesene ALTE Konfiguration - genau die Werte, aus denen das Altmodell seine Fenster baute. */
    internal data class AltZustand(
        val wellnessAn: Boolean,
        val regelnAn: Boolean,
        val nachtStandardAn: Boolean,
        val windDownMinuten: Int,
        val globalStrength: Int,
        val globalWarmth: Int,
        val nachtStartMinuten: Int,
        val nachtEndeMinuten: Int,
        val nachtStrength: Int,
        val nachtWarmth: Int,
        val nachtAusnahmen: Set<String>
    )

    /**
     * Was geschrieben werden muss. [regeln] sind die zu `upsert`enden Regeln (angelegte UND
     * geänderte); alles, was hier nicht steht, bleibt unverändert. Gelöscht wird NIE - eine Regel,
     * die der Nutzer selbst angelegt hat, darf eine Modelländerung nicht verschlucken.
     */
    internal data class Plan(val dimEnabled: Boolean, val regeln: List<DimRule>)

    /**
     * Führt die Migration aus, falls sie noch nicht gelaufen ist.
     *
     * @return true, wenn sich dabei etwas an den Fenstergrenzen geändert hat - dann muss der
     *         Aufrufer auch die DND-Kette neu armieren (Invariante seit v1.32.1: ein Setter, der
     *         Dimm-FENSTERGRENZEN verschiebt, armiert BEIDE Ketten). Die DIMM-Kette armiert diese
     *         Funktion selbst; die DND-Kette kann sie nicht anfassen, weil `dnd/` von `dimmer/`
     *         liest und niemals umgekehrt.
     */
    /**
     * Ist der Nutzer entsperrt? `null` (kein UserManager) wird als "ja" gewertet - dieselbe
     * Richtung wie in `AlarmRepository`/`ShiftConfigRepository`: die Frage stellt sich real nur im
     * Direct-Boot-Prozess, und dort GIBT es den Dienst.
     */
    private fun nutzerEntsperrt(): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    suspend fun migriereEinmalig(): Boolean = withContext(NonCancellable) {
        // NonCancellable: die Migration stellt einen Zustand HER. Angestossen wird sie aus dem
        // lifecycleScope einer Activity - eine Drehung des Geraets im falschen Moment duerfte
        // nicht auf halber Strecke abbrechen.
        try {
            // ENTSPERRUNGS-GATE, siehe Klassen-KDoc: vor der ersten Entsperrung ist der CE-Store
            // still leer, und eine Migration darueber waere ein dauerhaft ausgeschalteter Dimmer.
            if (!nutzerEntsperrt()) {
                Logger.w(
                    LogTags.DIMMER,
                    "Dimmer-Modellmigration verschoben - Geraet noch nicht entsperrt (CE-Store waere leer)"
                )
                return@withContext false
            }
            val prefs = dataStore.data.first()
            if ((prefs[KEY_MARKER] ?: 0) >= STAND) return@withContext false

            val alt = lies(prefs)
            val bestand = dimRuleUseCase.getAllRules()
            val plan = plane(alt, bestand)

            plan.regeln.forEach { dimRuleUseCase.saveRule(it) }

            // NACHPRUEFEN STATT ANNEHMEN - dieselbe Ueberlegung wie in
            // DimRuleUseCase.renameShiftPattern: `upsert` meldet einen abgebrochenen Edit
            // (defektes Regel-JSON) bewusst NICHT nach oben, es loggt und laesst den Bestand
            // liegen. Ohne diese Kontrolle setzten wir den Marker ueber einen Bestand, in dem die
            // Uebersetzung gar nicht angekommen ist - und niemand sieht je wieder nach.
            val nachher = dimRuleUseCase.getAllRules()
            val fehlend = plan.regeln.filterNot { gewollt -> nachher.any { it == gewollt } }
            check(fehlend.isEmpty()) {
                "Dimmer-Modellmigration nicht vollstaendig geschrieben: ${fehlend.size} von " +
                    "${plan.regeln.size} Regel(n) fehlen (IDs ${fehlend.map { it.id }})"
            }

            dataStore.edit {
                it[KEY_DIM_ON] = plan.dimEnabled
                it[KEY_MARKER] = STAND
            }

            Logger.business(
                LogTags.DIMMER,
                "🔁 Dimmer-Modellmigration: Dimmer ${if (plan.dimEnabled) "AN" else "aus"}, " +
                    "${plan.regeln.size} Regel(n) uebernommen/angepasst"
            )

            // Invariante "jeder Schreibvorgang an den Dimmer-Prefs ruft danach enable()": ohne das
            // bliebe der rollende Tick auf den Fenstergrenzen des ALTEN Modells stehen.
            runCatching { dimSchedule.enable() }
                .onFailure { Logger.w(LogTags.DIMMER, "⚠️ Dimm-Kette nach der Migration nicht neu armiert", it) }
            true
        } catch (e: Exception) {
            // WARN+ landet im Release-Log. Fail-safe-Richtung wie ueberall im Dimmer: im Zweifel
            // hell. Der Marker bleibt ungesetzt, der naechste Start versucht es erneut - der Plan
            // ist idempotent (deterministische IDs, Fenster werden nur angehaengt, wenn sie fehlen).
            Logger.e(
                LogTags.DIMMER,
                "❌ Dimmer-Modellmigration fehlgeschlagen - Dimmen bleibt AUS, bis sie durchlaeuft",
                e
            )
            runCatching {
                if (dataStore.data.first()[KEY_DIM_ON] == true) {
                    dataStore.edit { it[KEY_DIM_ON] = false }
                }
            }.onFailure { Logger.e(LogTags.DIMMER, "❌ Dimmer liess sich nicht abschalten", it) }
            false
        }
    }
}
