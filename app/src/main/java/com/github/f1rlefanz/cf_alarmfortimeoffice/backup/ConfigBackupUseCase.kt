package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Was ein Import tatsaechlich geschrieben hat - fuer eine ehrliche Rueckmeldung an den Nutzer. */
data class ImportSummary(
    val shiftDefinitions: Int,
    val settingsKeys: Int,
    val hueKeys: Int,
    /** Schluessel aus der Datei, die der Filter abgelehnt hat - benannt statt verschwiegen. */
    val rejectedKeys: List<String>,
    /**
     * Wie viele Hue-Regel-Ziele auf der GERADE VERBUNDENEN Bridge nicht zuzuordnen waren.
     *
     * `null` heisst NICHT "alles in Ordnung", sondern "nicht geprueft" - keine Bridge gekoppelt
     * oder sie hat nicht geantwortet. Der Unterschied gehoert in die Rueckmeldung: ein "0
     * unbekannt" fuer eine Pruefung, die gar nicht stattgefunden hat, waere genau die stille
     * Erfolgsmeldung, gegen die dieser Import sonst ueberall verteidigt.
     */
    val unresolvedHueTargets: Int? = null
)

/**
 * Exportiert und importiert die Konfiguration als Datei - der Weg, der die Handarbeit nach jeder
 * Neuinstallation erspart.
 *
 * BEWUSST OHNE ZUGANGSDATEN. Keine Tokens, kein Tink-Keyset, kein Hue-Bridge-Username, keine
 * Anmeldedaten. Der Token-Store wird von dieser Klasse gar nicht angefasst; was aus den beiden
 * gelesenen Stores nicht mit darf, entscheidet [ConfigBackupFilter].
 *
 * UNABHAENGIG VON GOOGLES BACKUP. Die Auto-Backup-Regeln sind eine zweite, andere Sache (sie
 * greifen bei einem Geraetewechsel automatisch, aber nur wenn der Nutzer sie hat und Google sie
 * ausfuehrt). Diese Datei funktioniert immer, auch bei Neuinstallation auf demselben Geraet, und
 * laesst sich an eine Kollegin weitergeben.
 *
 * DER FILTER GILT IN BEIDE RICHTUNGEN. Beim Import wird jeder Schluessel erneut geprueft, nicht nur
 * beim Export. Eine handbearbeitete Datei, eine Datei aus einer aelteren Version mit anderem Filter
 * oder eine fremde Datei kann so keinen Laufzeitzustand und keine Zugangsdaten einschleusen -
 * insbesondere keine aktive Master-Pause, die den Wecker stumm schalten wuerde.
 */
@Singleton
class ConfigBackupUseCase @Inject constructor(
    @param:MainDataStore private val mainDataStore: DataStore<Preferences>,
    @param:HueDataStore private val hueDataStore: DataStore<Preferences>,
    // BEWUSST der UseCase und NICHT das Repository: nur IShiftUseCase.saveShiftConfig() ruft
    // invalidateAllCaches() und damit ShiftRecognitionEngine.clearRecognitionCache(). Ueber das
    // Repository geschrieben blieb der Erkennungs-Cache stehen, und die anschliessend nachgezogene
    // Erkennung lieferte bei unveraenderter Eventliste einen Cache-Treffer mit den ALTEN
    // Weckzeiten - der Import waere bis zum Ablauf der Cache-Gueltigkeit wirkungslos gewesen.
    private val shiftUseCase: IShiftUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val dndSchedule: DndScheduleUseCase,
    // Nur fuer den Ziel-Abgleich der importierten Hue-Regeln (siehe
    // reconcileImportedHueTargets). Beide werden ausschliesslich im Import benutzt und beruehren
    // die Bridge nur, wenn sie erreichbar ist.
    private val hueLightUseCase: IHueLightUseCase,
    private val hueRuleUseCase: IHueRuleUseCase
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * @param appVersion nur zur Nachvollziehbarkeit in der Datei
     * @param createdAt Zeitstempel als Text, vom Aufrufer - haelt diese Klasse frei von
     *        Uhrzeit-Abhaengigkeiten
     */
    suspend fun export(appVersion: String, createdAt: String): Result<String> = runCatching {
        val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
        if (shiftConfig == null) {
            // Kein Abbruch: die restliche Konfiguration ist trotzdem wertvoll. Aber sichtbar machen.
            Logger.w(
                LogTags.SHIFT_CONFIG,
                "⚠️ EXPORT: Schicht-Konfiguration nicht lesbar - Datei enthaelt sie NICHT"
            )
        }

        val backup = ConfigBackup(
            formatVersion = ConfigBackup.CURRENT_FORMAT_VERSION,
            appVersion = appVersion,
            createdAt = createdAt,
            shiftConfig = shiftConfig,
            settings = exportableValues(mainDataStore),
            hue = exportableValues(hueDataStore)
        )

        Logger.business(
            LogTags.APP,
            "📤 EXPORT: ${backup.shiftConfig?.definitions?.size ?: 0} Schichtdefinitionen, " +
                "${backup.settings.size} Einstellungen, ${backup.hue.size} Hue-Werte"
        )
        json.encodeToString(ConfigBackup.serializer(), backup)
    }

    suspend fun import(fileContent: String): Result<ImportSummary> = runCatching {
        val backup = json.decodeFromString(ConfigBackup.serializer(), fileContent)

        require(backup.formatVersion <= ConfigBackup.CURRENT_FORMAT_VERSION) {
            "Die Datei stammt aus einer neueren App-Version (Format ${backup.formatVersion}, " +
                "diese App kann bis ${ConfigBackup.CURRENT_FORMAT_VERSION}). Bitte die App aktualisieren."
        }

        val rejected = mutableListOf<String>()

        // Schichtdefinitionen ueber das typisierte Repository - dort greifen Validierung und
        // Serialisierung, und der Defekt-Schutz bleibt zustaendig.
        var definitions = 0
        backup.shiftConfig?.let { fromFile ->
            // `autoAlarmEnabled` wird NICHT importiert. Es ist keine Konfiguration, sondern eine
            // echte, sofortige Pause (CLAUDE.md: `autoAlarmEnabled = false` raeumt die Alarme per
            // clearInternalAlarms() ab). Eine Datei, die waehrend einer solchen Pause entstand,
            // haette auf dem Zielgeraet den Wecker stummgeschaltet UND dessen Alarme geloescht -
            // dieselbe Fehlerklasse wie die Master-Pause, nur reist sie INNERHALB des
            // ShiftConfig-Objekts mit und wird deshalb von ConfigBackupFilter nicht erfasst
            // (der sieht nur Preferences-Schluessel).
            // Der Wert des ZIELGERAETS gewinnt; ist er nicht lesbar, gilt "Alarme an" - im Zweifel
            // wecken.
            // LEERE DEFINITIONSLISTE WIRD ABGELEHNT - dieselbe Ueberlegung wie
            // `structuralRejection` fuer die beiden JSON-Regelwerke, nur fuer den wertvollsten und
            // gefaehrlichsten Teil der Datei. kotlinx.serialization fuellt ein fehlendes
            // `definitions`-Feld stillschweigend mit dem Default `emptyList()`; aus "Datei
            // unvollstaendig oder von Hand verstuemmelt" wuerde damit lautlos "keine Schichten".
            // Und das ist in diesem Projekt der dokumentierte Weg zu NULL ALARMEN: der Save
            // invalidiert die Caches, `ShiftViewModel.observeExternalConfigChanges()` zieht nach,
            // `syncAlarms()` erkennt mit 0 Definitionen keine Schicht und raeumt die
            // kalenderbasierten Alarme ab - waehrend der Import "Erfolg: 0 Schichtdefinitionen"
            // meldet. Eine Konfiguration ohne jede Schichtdefinition ist ausserdem fuer sich
            // sinnlos: es gibt nichts zu importieren.
            if (fromFile.definitions.isEmpty()) {
                rejected += "shiftConfig (keine einzige Schichtdefinition in der Datei - " +
                    "unvollstaendig oder beschaedigt; bestehende Konfiguration bleibt unangetastet)"
                return@let
            }

            val localAutoAlarm = shiftUseCase.getCurrentShiftConfig().getOrNull()?.autoAlarmEnabled ?: true
            val config = fromFile.copy(autoAlarmEnabled = localAutoAlarm)
            if (fromFile.autoAlarmEnabled != localAutoAlarm) {
                rejected += "autoAlarmEnabled (Alarm-Pause des Quellgeraets, nicht uebernommen)"
            }
            shiftUseCase.saveShiftConfig(config).getOrThrow()
            definitions = config.definitions.size
        }

        val settingsWritten = writeValues(mainDataStore, backup.settings, rejected)
        val hueWritten = writeValues(hueDataStore, backup.hue, rejected)

        // "Jeder Setter, der einen DimOverlayPrefs-Wert schreibt, MUSS direkt danach
        // DimScheduleUseCase.enable() aufrufen" (CLAUDE.md). Der Import ist ein neuer, generischer
        // Schreiber genau dieser Schluessel - ohne diesen Schritt stehen importierte Dimm-/DND-
        // Einstellungen im Store, aber es laeuft keine Tick-Kette: in der Nacht nach dem Import
        // wuerde nicht gedimmt und kein "Nicht stoeren" gesetzt, bis die naechste 6h-Wartung die
        // Ketten nebenbei wieder armiert (bis zu 6 Stunden spaeter).
        // Best-effort und getrennt gefangen: enable() traegt seinen eigenen Master-Pause-Backstop,
        // ist also auch bei aktiver Pause gefahrlos, aber ein Fehlschlag hier darf den bereits
        // geschriebenen Import nicht als gescheitert melden.
        runCatching { dimSchedule.enable() }
            .onFailure { Logger.w(LogTags.DIMMER, "⚠️ IMPORT: Dimmer-Kette nicht armiert", it) }
        runCatching { dndSchedule.enable() }
            .onFailure { Logger.w(LogTags.DND, "⚠️ IMPORT: DND-Kette nicht armiert", it) }

        val unresolvedHueTargets = reconcileImportedHueTargets()

        Logger.business(
            LogTags.APP,
            "📥 IMPORT: $definitions Schichtdefinitionen, $settingsWritten Einstellungen, " +
                "$hueWritten Hue-Werte" +
                if (rejected.isEmpty()) "" else ", ${rejected.size} Schluessel abgelehnt: ${rejected.joinToString()}"
        )
        ImportSummary(definitions, settingsWritten, hueWritten, rejected, unresolvedHueTargets)
    }

    /**
     * Die importierten Hue-Regeln tragen die LAMPEN-IDS DES QUELLGERAETS - bridge-lokale Nummern,
     * die auf einer anderen Bridge ins Leere zeigen. Ist gerade eine Bridge erreichbar, wird das
     * hier sofort abgeglichen (ueber den gespeicherten Zielnamen) und der Rest gezaehlt.
     *
     * ZUGABE, KEIN ERSATZ. Der typische Import laeuft auf einem frisch eingerichteten Geraet, BEVOR
     * die Bridge gekoppelt ist - dann ist hier nichts zu holen, und der eigentliche Abgleich
     * passiert spaeter beim ersten Bridge-Kontakt (`HueViewModel.reconcileRuleTargets`). Deshalb
     * best-effort und `null` bei jedem Zweifel: ein "0 unbekannt" darf nur dastehen, wenn wirklich
     * geprueft wurde.
     */
    private suspend fun reconcileImportedHueTargets(): Int? = runCatching {
        val targets = hueLightUseCase.getAllLightTargets().getOrNull() ?: return@runCatching null
        hueRuleUseCase.reconcileTargets(targets).getOrNull()?.unresolved?.size
    }.getOrElse { error ->
        Logger.w(LogTags.HUE_USECASE, "⚠️ IMPORT: Hue-Ziele nicht abgeglichen", error)
        null
    }

    private suspend fun exportableValues(store: DataStore<Preferences>): Map<String, StoredValue> =
        store.data.first().asMap()
            .asSequence()
            .filter { (key, _) -> ConfigBackupFilter.isExportable(key.name) }
            .mapNotNull { (key, value) -> toStoredValue(value)?.let { key.name to it } }
            .toMap()

    private suspend fun writeValues(
        store: DataStore<Preferences>,
        values: Map<String, StoredValue>,
        rejected: MutableList<String>
    ): Int {
        var written = 0
        store.edit { prefs ->
            values.forEach { (name, stored) ->
                val reason = ConfigBackupFilter.exclusionReason(name)
                if (reason != null) {
                    rejected += "$name ($reason)"
                    return@forEach
                }
                // Zusaetzlich zum Schluessel-Filter: der WERT muss verwertbar sein. Eine
                // Exportdatei ist Text und kann von Hand veraendert worden sein.
                val outOfRange = stored.value?.toIntOrNull()
                    ?.let { ConfigBackupFilter.rangeRejection(name, it) }
                if (outOfRange != null) {
                    rejected += "$name ($outOfRange)"
                    return@forEach
                }
                val malformed = structuralRejection(name, stored.value)
                if (malformed != null) {
                    rejected += "$name ($malformed)"
                    return@forEach
                }
                val wrongType = typeMismatch(prefs, name, stored.type)
                if (wrongType != null) {
                    rejected += "$name ($wrongType)"
                    return@forEach
                }
                if (applyValue(prefs, name, stored)) written++ else rejected += "$name (unbekannter Typ '${stored.type}')"
            }
        }
        return written
    }

    companion object {
        /**
         * Die zwei Schluessel, deren Wert ein ganzes JSON-Dokument ist - die aufwendigsten und
         * wertvollsten Teile der Konfiguration (Dimmer-Regeln, Hue-Regeln).
         *
         * WARUM SIE GEPRUEFT WERDEN, obwohl beide Leser einen unlesbaren Wert bereits abfangen
         * (`DimRuleRepository` per `runCatching`, `HueConfigRepository` per `try/catch`, beide mit
         * Rueckfall auf eine leere Liste): genau dieser Rueckfall ist das Problem. Ein
         * beschaedigter Wert wuerde als "keine Regeln" durchgehen - der Import meldet Erfolg, und
         * der Nutzer sieht eine leere Regelliste, ohne zu wissen warum. Der Ort, an dem das noch
         * SAGBAR ist, ist der Import. Danach ist die Information weg.
         *
         * Bewusst nur strukturell: es wird geprueft, ob sich der Wert ueberhaupt in die erwarteten
         * Objekte lesen laesst - nicht, ob die Regeln fachlich sinnvoll sind.
         */
        private val STRUCTURED_JSON_KEYS = setOf("dim_rules", "hue_schedule_rules")

        /** Toleranter Leser, wie ihn die beiden Repositories selbst benutzen. */
        private val validationJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * @return `null`, wenn der Wert in Ordnung ist oder es kein JSON-Schluessel ist, sonst die
         *         Begruendung fuer die Rueckmeldung an den Nutzer.
         */
        fun structuralRejection(name: String, value: String?): String? {
            if (name !in STRUCTURED_JSON_KEYS) return null
            val raw = value ?: return "leerer Wert fuer ein Regelwerk"
            if (raw.isBlank()) return null // "keine Regeln" ist ein zulaessiger Zustand
            val result = runCatching {
                when (name) {
                    "dim_rules" -> validationJson.decodeFromString<List<DimRule>>(raw)
                    "hue_schedule_rules" -> validationJson.decodeFromString<List<HueSchedule>>(raw)
                    else -> Unit
                }
            }
            return if (result.isSuccess) null
            else "Regelwerk nicht lesbar - nicht uebernommen, damit es nicht als 'keine Regeln' durchgeht"
        }

        /**
         * Der ERWARTETE Typ kommt vom SCHLUESSEL, nicht aus der Datei.
         *
         * `applyValue` prueft nur, ob sich der Wert in den in der Datei BEHAUPTETEN Typ parsen
         * laesst - damit entschied eine fremde Datei ueber den DataStore-Typ. Ein falsch
         * typisierter Wert ist schlimmer als ein fehlender: er liegt reboot-fest in der
         * `preferences_pb`, und der naechste Lesezugriff scheitert mit einer ClassCastException,
         * BEVOR irgendein `?:`-Default oder `coerceIn` greifen kann. Bei `snooze_minutes` als
         * String hiesse das: `AlarmPrefs.snoozeMinutes` wirft bei jedem Alarm-Feuern, und der
         * `AlarmReceiver` verschluckt es in seinem try/catch - der Wecker bliebe stumm.
         *
         * Zwei Quellen fuer die Erwartung, in dieser Reihenfolge:
         *  1. Der Wert, der HEUTE im Store steht (dessen Typ ist die Wahrheit dieses Geraets).
         *  2. Eine kleine Liste bekannter Zahlen-Schluessel - sie kann auch dann urteilen, wenn der
         *     Schluessel lokal noch nie geschrieben wurde.
         * Ist der Schluessel lokal unbekannt und steht nicht in der Liste, wird der Typ der Datei
         * akzeptiert: fuer einen Schluessel aus einer neueren Version ist das die einzige
         * verfuegbare Information, und ein unbekannter Schluessel kann keinen bestehenden Leser
         * beschaedigen.
         *
         * @return `null`, wenn der Typ passt oder keine Erwartung existiert, sonst die Begruendung.
         */
        fun typeMismatch(prefs: Preferences, name: String, fileType: String): String? {
            val expected = prefs.asMap().entries
                .firstOrNull { it.key.name == name }
                ?.let { typeNameOf(it.value) }
                ?: KNOWN_INT_KEYS.takeIf { name in it }?.let { "int" }
                ?: return null

            return if (expected == fileType) null
            else "Typ '$fileType' in der Datei, erwartet wird '$expected' - nicht uebernommen, " +
                "ein falsch typisierter Wert laesst jeden Lesezugriff scheitern"
        }

        /** Schluessel, deren Typ auch ohne lokalen Bestand feststeht (beide zusaetzlich geklemmt). */
        private val KNOWN_INT_KEYS = setOf("snooze_minutes", "dnd_oncall_cutoff_min")

        private fun typeNameOf(value: Any?): String? = when (value) {
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Double -> "double"
            is String -> "string"
            is Set<*> -> "stringSet"
            else -> null
        }

        /**
         * REIN UND TESTBAR: DataStore-Wert -> Dateiformat. `null` fuer Typen, die es in
         * Preferences nicht gibt - dann fehlt der Wert in der Datei statt sie zu zerstoeren.
         */
        fun toStoredValue(value: Any?): StoredValue? = when (value) {
            is Boolean -> StoredValue("boolean", value.toString())
            is Int -> StoredValue("int", value.toString())
            is Long -> StoredValue("long", value.toString())
            is Float -> StoredValue("float", value.toString())
            is Double -> StoredValue("double", value.toString())
            is String -> StoredValue("string", value)
            // Reihenfolge irrelevant, aber stabil sortiert, damit zwei Exporte desselben Zustands
            // dieselbe Datei ergeben und ein Diff aussagekraeftig bleibt.
            is Set<*> -> StoredValue("stringSet", setValue = value.map { it.toString() }.sorted())
            else -> null
        }

        /**
         * REIN UND TESTBAR: Dateiformat -> DataStore-Wert, typerhaltend.
         *
         * @return false, wenn Typ oder Wert nicht verwertbar sind. Dann wird NICHTS geschrieben -
         *         ein falsch typisierter Wert waere schlimmer als ein fehlender, weil der naechste
         *         Lesezugriff mit einer ClassCastException scheitert.
         */
        fun applyValue(prefs: MutablePreferences, name: String, stored: StoredValue): Boolean {
            return when (stored.type) {
                "boolean" -> stored.value?.toBooleanStrictOrNull()
                    ?.let { prefs[booleanPreferencesKey(name)] = it; true } ?: false
                "int" -> stored.value?.toIntOrNull()
                    ?.let { prefs[intPreferencesKey(name)] = it; true } ?: false
                "long" -> stored.value?.toLongOrNull()
                    ?.let { prefs[longPreferencesKey(name)] = it; true } ?: false
                "float" -> stored.value?.toFloatOrNull()
                    ?.let { prefs[floatPreferencesKey(name)] = it; true } ?: false
                "double" -> stored.value?.toDoubleOrNull()
                    ?.let { prefs[doublePreferencesKey(name)] = it; true } ?: false
                "string" -> stored.value?.let { prefs[stringPreferencesKey(name)] = it; true } ?: false
                "stringSet" -> stored.setValue?.let { prefs[stringSetPreferencesKey(name)] = it.toSet(); true } ?: false
                else -> false
            }
        }
    }
}
