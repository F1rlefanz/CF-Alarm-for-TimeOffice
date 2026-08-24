package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalFlagsGuard
import kotlinx.serialization.Serializable

/**
 * Ein einzelner Einstellungswert, typerhaltend serialisiert.
 *
 * WARUM NICHT EINFACH `Map<String, String>`: DataStore-Preferences sind typisiert. Ein
 * `Int`-Schluessel mit einem String-Wert zurueckgeschrieben wirft beim naechsten Lesen eine
 * ClassCastException - und das ausgerechnet in den Einstellungen, die den Wecker steuern. Der Typ
 * muss also mit.
 *
 * Bewusst eine flache, diskriminierte Datenklasse und keine polymorphe Serialisierung: das
 * Dateiformat bleibt fuer einen Menschen lesbar und ohne Serializer-Registrierung stabil.
 */
@Serializable
data class StoredValue(
    /** "boolean" | "int" | "long" | "float" | "double" | "string" | "stringSet" */
    val type: String,
    val value: String? = null,
    val setValue: List<String>? = null
)

/**
 * Der Inhalt einer exportierten Konfigurationsdatei.
 *
 * @param formatVersion wird beim Import geprueft. Eine Datei aus einer neueren App-Version mit
 *        hoeherer Version wird abgelehnt statt halb eingelesen.
 * @param appVersion nur zur Nachvollziehbarkeit fuer Menschen, keine Logik daran.
 * @param shiftConfig die Schichtdefinitionen - der aufwendigste Teil und der Grund fuer das
 *        ganze Feature. Geht bewusst durch das typisierte Modell und nicht als Rohwert.
 * @param settings Schluessel/Wert aus dem "settings"-Store, gefiltert (siehe [ConfigBackupFilter]).
 * @param hue Schluessel/Wert aus dem "hue_settings"-Store, gefiltert.
 */
@Serializable
data class ConfigBackup(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersion: String = "",
    val createdAt: String = "",
    val shiftConfig: ShiftConfig? = null,
    val settings: Map<String, StoredValue> = emptyMap(),
    val hue: Map<String, StoredValue> = emptyMap()
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/**
 * Entscheidet, WELCHE Einstellungen in eine Exportdatei gehoeren - und vor allem, welche nicht.
 *
 * Rein und ohne Android, damit die Entscheidung testbar ist. Sie ist der sicherheitskritische Teil
 * des ganzen Features: was hier faelschlich durchgelassen wird, landet beim Import auf einem
 * fremden Geraet.
 *
 * DREI GRUENDE FUER EINEN AUSSCHLUSS, alle sachlich und nicht Geschmackssache:
 *
 * 1. LAUFZEITZUSTAND. Ein Dimm-Overlay, das gerade an ist, eine Korrektur, die an ein konkretes
 *    Zeitfenster gebunden ist, eine laufende Master-Pause - das beschreibt einen Moment, keine
 *    Einstellung. Der gefaehrlichste davon ist `master_pause_enabled`: importiert man eine aktive
 *    Pause, bleibt der Wecker auf dem neuen Geraet STUMM, und niemand kommt darauf, in einer
 *    Importdatei nach der Ursache zu suchen.
 * 2. ZUGANGSDATEN UND GERAETEBEZUG. Der Hue-Bridge-Username ist ein Zugangstoken zur Bridge im
 *    Heimnetz, die Bridge-IP gilt nur in genau diesem Netz, und die Zen-Regel-ID zeigt auf eine
 *    Regel, die dieses Geraet bei Android registriert hat. Nichts davon ist auf einem anderen
 *    Geraet gueltig, und die Zugangsdaten gehoeren nicht in eine Datei, die der Nutzer per
 *    Messenger weitergibt.
 * 3. GERAETELOKALE ONBOARDING-MARKIERUNGEN. Dieselbe Begruendung wie in
 *    [DeviceLocalFlagsGuard]: "Akku-Hinweis schon weggetippt" gilt fuer EIN Geraet. Kaeme das mit,
 *    wuerde auf dem neuen Geraet nie nach der Akku-Ausnahme gefragt - einer der zwei nachgewiesenen
 *    Wecker-Killer.
 *
 * Alles andere wird exportiert. Diese Richtung ist Absicht: eine neue Einstellung ist automatisch
 * dabei, statt beim naechsten Feature stillschweigend zu fehlen. Wer eine neue LAUFZEIT-Groesse
 * einfuehrt, muss sie hier eintragen - darauf weist der Test hin.
 */
object ConfigBackupFilter {

    /**
     * Laufzeitzustand: beschreibt einen Moment, keine Einstellung.
     *
     * WIE DIESE LISTE ENTSTANDEN IST - und warum sie vollstaendig ist: Der erste Wurf entstand aus
     * den Schluesseln von vier Paketen (dimmer/dnd/alarm/masterpause) und war damit LUECKENHAFT. Der
     * erste echte Export am Geraet enthielt genau drei Schluessel, und ALLE DREI gehoerten nicht
     * hinein - darunter `active_alarms`, die persistierte Alarmliste. Seither ist die Liste aus einer
     * vollstaendigen Inventur ALLER `*PreferencesKey("…")` im ganzen Baum abgeleitet.
     */
    private val RUNTIME_KEYS = setOf(
        // Die persistierte Alarmliste. Sie leitet sich aus DIESEM Kalender ab; importiert wuerde sie
        // fremde Alarme mit fremden IDs in das Repository und in den Direct-Boot-Spiegel schreiben.
        "active_alarms",
        // Die erkannten Schichtspannen (ShiftSpanStore). Wie active_alarms aus DIESEM Kalender
        // abgeleitet und jederzeit vom naechsten Sync neu erzeugt. Importiert wuerden sie fremde
        // Dienstzeiten mitbringen und "Nicht stoeren" sowie den Dimmer auf einem anderen Geraet zu
        // falschen Zeiten schalten - bis der erste eigene Sync sie ueberschreibt.
        ShiftSpanStore.KEY_SHIFT_SPANS_NAME,
        // Die vom Nutzer freigegebenen Tage (FreieTageStore). Eine Nutzerentscheidung, ja - aber
        // eine ueber KONKRETE Kalendertage dieses Dienstplans, die binnen Tagen verfaellt.
        // Importiert wuerde sie auf dem neuen Geraet Wecker unterdruecken, die dort zu einem ganz
        // anderen Dienstplan gehoeren - und still, denn ein unterdrueckter Wecker meldet sich
        // nicht. Dieselbe Abwaegung wie beim Skip-Merker.
        FreieTageStore.KEY_FREIE_TAGE_NAME,
        // Der offene Raeumauftrag nach einer Kalender-Abwahl. Er beschreibt einen unfertigen
        // Vorgang auf DIESEM Geraet ("hier stehen noch Wecker eines abgewaehlten Dienstplans"),
        // keine Einstellung - und importiert wuerde er auf dem neuen Geraet einen Raeumlauf
        // anstossen, den dort nie jemand ausgeloest hat.
        PendingDeselectionCleanupStore.KEY_PENDING_SINCE_NAME,
        // Der Bezugspunkt fuer "war diese Schicht beim letzten Sync schon sichtbar?"
        // (SyncHorizonStore). Er beschreibt, wann auf DIESEM Geraet zuletzt ein vollstaendiger
        // Sync lief - reiner Laufzeitzustand. Die gefaehrliche Richtung ist ein ALTER importierter
        // Zeitpunkt (ein Geraet, das lange nicht synchronisiert hat): er setzt den Vergleichspunkt
        // zurueck, und das neue Geraet verschweigt dann echte Dienstplan-Aenderungen als blosse
        // Horizont-Eintritte. Ein frischerer Wert waere umgekehrt nur zu gespraechig.
        SyncHorizonStore.KEY_LAST_SYNC_NAME,
        // Wann DIESES Geraet zuletzt gesehen hat, dass sein Dienstplan-Feed neu eingelesen wurde,
        // und wie viele Wecker es dabei wiedererkannt hat (FeedNeueinlesenStore). Eine Beobachtung
        // dieses Geraets an DIESEM Kalenderabonnement, keine Einstellung - jeder eigene Sync
        // erzeugt sie bei Bedarf neu. Die gefaehrliche Richtung ist der importierte FREMDE
        // Zeitpunkt: das neue Geraet zeigte dann eine ruhige Auskunft ("zuletzt neu eingelesen:
        // 22.08., 11 Wecker neu zugeordnet") ueber einen Vorgang, den es nie beobachtet hat, und
        // zwar so lange, bis dort zufaellig selbst einmal ein Kennungswechsel auftritt. Eine
        // erfundene Beobachtung ist genau das, was diese Zeile nicht sein darf - sie soll ja die
        // Frage "woran erkenne ich das?" ehrlich beantworten.
        FeedNeueinlesenStore.KEY_ZEITPUNKT_NAME,
        FeedNeueinlesenStore.KEY_ANZAHL_NAME,
        // Zeitstempel der Hintergrundarbeit
        "last_maintenance_time",
        "last_event_load_time",
        "last_success_timestamp",
        // Was der Overlay-Dienst gerade rendert
        "dim_overlay_on",
        "dim_render_strength",
        "dim_render_warmth",
        // Ablaufzeitpunkt einer laufenden Dimm-VORSCHAU (Wanduhr-Millis dieses Geraets). Ohne
        // diesen Eintrag ging folgender Ablauf kaputt: Geraet A exportiert waehrend einer
        // Vorschau, Geraet B importiert den laengst vergangenen Zeitpunkt. Laeuft auf B gerade
        // ein regulaeres Dimm-Fenster (dim_overlay_on wird nicht importiert, steht dort also
        // eigenstaendig auf true), liest DimOverlayPrefs.renderState die Kombination als
        // "abgelaufene Vorschau" und schickt RenderState(false) an den Overlay-Dienst - der
        // Bildschirm bleibt hell, bis das Fenster wechselt.
        "dim_overlay_preview_until",
        // Die Heller/Dunkler-Korrektur haengt an EINEM konkreten Fenster (windowEnd + windowStrength)
        "dim_override_strength_delta",
        "dim_override_paused",
        "dim_override_window_end",
        "dim_override_window_strength",
        // Der Stand der einmaligen Dimmer-Modellmigration (DimmerModellMigration). Er beschreibt,
        // was auf DIESEM Geraet schon umgestellt wurde - keine Einstellung. Die gefaehrliche
        // Richtung ist der IMPORTIERTE Marker: er liesse ein noch nicht migriertes Geraet die
        // Migration ueberspringen, und dessen alte Dimmer-Konfiguration (Wellness/Nacht-Standard)
        // waere damit dauerhaft unlesbar - der Dimmer bliebe dort lautlos aus.
        DimmerModellMigration.KEY_MARKER_NAME,
        // Eine laufende Pause. Importiert wuerde sie den Wecker stumm schalten.
        "master_pause_enabled",
        // "Naechsten Alarm ueberspringen" gilt fuer EINEN konkreten, bereits geplanten Alarm.
        "is_next_alarm_skipped",
        "skip_activated_at",
        "skip_reason",
        "skipped_alarm_id",
        "skipped_alarm_trigger_time",
        // Der gesicherte Stand des uebersprungenen MANUELLEN Weckers - derselbe Vorgang wie die
        // fuenf Zeilen darueber, nur spaeter dazugekommen und beim Nachtragen vergessen. Er
        // beschreibt genau einen Wecker DIESES Geraets; importiert wuerde "Aufheben" auf dem
        // neuen Geraet einen fremden Wecker stellen.
        "skipped_manual_alarm"
    )

    /**
     * Praefixe/Endungen, die unabhaengig von der genauen Schreibweise nie exportiert werden.
     * Faengt auch Schluessel, die es heute noch nicht gibt.
     */
    private val DENIED_SUFFIXES = listOf(
        // Sicherungen unlesbarer Rohdaten (shift_config_broken, active_alarms_broken). Sie gehoeren
        // zu einem Defekt auf DIESEM Geraet und sind fuer ein anderes wertlos.
        "_broken"
    )

    /** Geraetebezug oder Zugangsdaten - auf einem anderen Geraet ungueltig oder gefaehrlich. */
    private val DEVICE_OR_SECRET_KEYS = setOf(
        // Von dieser App bei Android registrierte Zen-Regel
        "dnd_zen_rule_id",
        // Die Geraete-Kennung des Waechters. Sie steht BEWUSST nicht in
        // DeviceLocalFlagsGuard.isDeviceLocalKey - der Waechter darf seinen eigenen Marker nicht
        // loeschen. Exportiert werden darf sie aber genauso wenig: mit der Kennung eines fremden
        // Geraets im Store haelt der Waechter das neue Geraet fuer dasselbe und setzt die
        // Onboarding-Flags nie zurueck. Zwei verschiedene Regeln fuer denselben Schluessel, beide
        // richtig - ein Test haelt jede davon fest.
        "device_local_flags_marker",
        // Hue: Zugangstoken zur Bridge und netzspezifische Adresse, in zwei Schreibweisen
        "hue_username",
        "username",
        "hue_bridge_ip",
        "bridge_ip",
        // Ergebnis einer Pruefung gegen DIESE Bridge in DIESEM Netz
        "connection_validated",
        // Anmeldung und Tokens. Liegen in anderen Stores, die diese Klasse nicht liest - hier
        // trotzdem benannt, damit ein kuenftiges Zusammenlegen von Stores nicht still ein Leck oeffnet.
        "access_token",
        "refresh_token",
        "token_data_v2",
        "token_expiry_long",
        "login_status",
        "user_email",
        "user_id",
        // Die Kalenderauswahl ist an das Google-Konto gebunden: die IDs einer Kollegin zeigen auf
        // Kalender, die es auf diesem Geraet nicht gibt. Der Nutzer waehlt seinen Dienstplan selbst -
        // dafuer gibt es ein eigenes Gate im Onboarding.
        "selected_calendar_ids",
        "calendar_id",
        // Die Schichtkonfiguration geht bewusst ueber das typisierte Repository (validiert, mit
        // Defekt-Schutz) und nicht als Rohwert. Explizit ausgeschlossen, damit ein spaeteres
        // Verschieben dieses Schluessels in den settings-Store keinen zweiten, ungeprueften Weg oeffnet.
        "shift_config"
    )

    /**
     * @param keyName der Preferences-Schluesselname
     * @return true, wenn der Wert in eine Exportdatei gehoert
     */
    fun isExportable(keyName: String): Boolean = exclusionReason(keyName) == null

    /**
     * Der EINE Ort, an dem entschieden wird. [isExportable] leitet sich davon ab, damit Entscheidung
     * und Begruendung nicht auseinanderlaufen koennen - die Begruendung erscheint nach einem Import
     * auch in der Rueckmeldung an den Nutzer.
     */
    /**
     * PLAUSIBLE WERTEBEREICHE fuer Zahlen, deren Leser einen sinnvollen Bereich VORAUSSETZEN.
     *
     * Der Filter oben entscheidet, WELCHE Schluessel durchkommen - er sagt nichts darueber, ob der
     * WERT verwertbar ist. Eine Datei ist aber eine Textdatei: sie kann von Hand bearbeitet, von
     * einer aelteren Version geschrieben oder unterwegs beschaedigt worden sein. Zwei Leser hatten
     * bis v1.23.0 keine eigene Klemme (der Dimmer hat ueberall eine, siehe die `coerceIn`-Aufrufe
     * in `DimOverlayPrefs`):
     *
     *   - `snooze_minutes`: `0` oder negativ heisst, der Schlummer-Alarm liegt in der Vergangenheit
     *     und feuert SOFORT wieder - ein Wecker, den man nicht mehr wegdrueckt.
     *   - `dnd_oncall_cutoff_min`: `DndOnCallCutoffResolver` rechnet
     *     `LocalTime.ofSecondOfDay(cutoffMinutes * 60L)`. Negativ oder >= 1440 wirft eine
     *     `DateTimeException` - der DND-Tick stirbt dann bei jedem Lauf.
     *
     * Bewusst NUR diese beiden: fuer alles andere ist eine Klemme im Lesepfad die richtige Ebene,
     * und die ist dort vorhanden. Dieser Katalog ist keine zweite Validierungsschicht fuer alles,
     * sondern der Schutz an der Stelle, an der FREMDE Daten hereinkommen.
     */
    private val PLAUSIBLE_INT_RANGES: Map<String, IntRange> = mapOf(
        "snooze_minutes" to 1..120,
        "dnd_oncall_cutoff_min" to 0..(24 * 60 - 1)
    )

    /**
     * REINE FUNKTION: liegt dieser Zahlenwert im plausiblen Bereich?
     *
     * @return `null`, wenn der Wert in Ordnung ist (oder kein Bereich hinterlegt ist), sonst die
     *         Begruendung fuer die Rueckmeldung an den Nutzer.
     */
    fun rangeRejection(keyName: String, value: Int): String? {
        val range = PLAUSIBLE_INT_RANGES[keyName] ?: return null
        return if (value in range) null
        else "Wert $value ausserhalb des sinnvollen Bereichs ${range.first}-${range.last}"
    }

    fun exclusionReason(keyName: String): String? = when {
        keyName in RUNTIME_KEYS -> "Laufzeitzustand"
        keyName in DEVICE_OR_SECRET_KEYS -> "Geraetebezug oder Zugangsdaten"
        DENIED_SUFFIXES.any { keyName.endsWith(it) } -> "Sicherung unlesbarer Rohdaten"
        // Dieselbe Liste wie beim Geraetewechsel-Waechter - eine Quelle, kein zweiter Katalog.
        DeviceLocalFlagsGuard.isDeviceLocalKey(keyName) -> "geraetelokale Onboarding-Markierung"
        else -> null
    }
}
