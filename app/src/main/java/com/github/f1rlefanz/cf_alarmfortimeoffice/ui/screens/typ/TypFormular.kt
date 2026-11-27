package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Der Formularstand des Typ-Editors als EIN Wert (Umbau #187). Vorher sieben einzelne
 * `remember`-Zustaende im Dialog; als Wert kann die Typ-Seite (Bearbeiten und Neuanlage) ihn als Entwurf
 * ueber Drehen, Dunkelmodus und Schriftgroesse retten ([alsSpeicherListe]). Die Weckzeit bleibt der eingetippte TEXT - ein halb getipptes
 * "06:" ist ein gueltiger Zwischenstand, kein Fehler.
 */
internal data class TypEntwurf(
    val name: String,
    val muster: List<String>,
    val weckzeitText: String,
    val isEnabled: Boolean,
    val isSilent: Boolean,
    val weckerAn: Boolean,
    val kategorie: ShiftKategorie,
    /** Der Dimm-Teil (#221). `null` = dieser Entwurf traegt keinen: beim Speichern gilt der gespeicherte Stand. */
    val dimmen: DimmenEntwurf? = null
) {
    val isSickDay: Boolean get() = kategorie == ShiftKategorie.KRANK

    /** Die eingetippte Weckzeit - `null`, solange sie kein gueltiges HH:mm ist. */
    val weckzeit: LocalTime? get() = runCatching { LocalTime.parse(weckzeitText, ZEIT_ANZEIGE) }.getOrNull()

    /**
     * Wird hier gerade ein BESTEHENDER Typ ([bestehenderName], `null` = Neuanlage) umbenannt?
     * Massstab ist der Vergleich, den die Regelsuche selbst anlegt (`equals(ignoreCase = true)`) -
     * eine reine Schreibweisenaenderung bricht dort nichts und braucht deshalb keinen Hinweis.
     * Getrimmt, weil genau das gespeichert wird.
     */
    fun istUmbenennungVon(bestehenderName: String?): Boolean =
        bestehenderName != null &&
            name.trim().isNotBlank() &&
            !name.trim().equals(bestehenderName, ignoreCase = true)

    /** Darf gespeichert werden? Die Weckzeit sperrt nur, wenn sie etwas bewirkt (#142). */
    val speicherbar: Boolean
        get() = name.isNotBlank() &&
            muster.any { it.isNotBlank() } &&
            (!ShiftEditFormular.weckzeitPflicht(kategorie, weckerAn) || weckzeit != null)

    /**
     * Der Typ, der gespeichert wird: [ausgang] (der bearbeitete oder der vorbelegte Typ) mit den
     * Formularwerten, oder `null`, wenn das Formular nicht speicherbar ist. Trimmen ist Pflicht
     * (Wortgrenzen-Regex), distinct gegen Doppel - schichterkennung.md.
     */
    fun alsDefinition(ausgang: ShiftDefinition?): ShiftDefinition? {
        val bereinigt = ShiftEditFormular.bereinigteMuster(muster)
        // #142: bei Krankmeldung ist die Weckzeit formal (Serialisierung), darf also nicht
        // blockieren - ebenso bei Wecker aus und Ignorieren (E2: der Wert bleibt).
        val zeit = ShiftEditFormular.zeitZumSpeichern(kategorie, weckerAn, weckzeit, ausgang)
        if (name.isBlank() || bereinigt.isEmpty() || zeit == null) return null
        return ShiftEditFormular.speichern(
            ausgang = ausgang,
            name = name,
            muster = bereinigt,
            alarmTime = zeit,
            isEnabled = isEnabled,
            weckerAn = weckerAn,
            isSilent = isSilent,
            kategorie = kategorie
        ).let { typ -> dimmen?.let { typ.copy(dimmen = it.anwenden(typ.dimmen)) } ?: typ }
    }

    /**
     * Fuer `rememberSaveable`: nur Typen, die ein Bundle kennt (Text, Wahrheitswert, Liste). Der Dimm-Teil
     * reist als JSON an achter Stelle (die Fenster sind keine Bundle-Typen); ohne ihn endet die Liste nach sieben.
     */
    fun alsSpeicherListe(): List<Any> =
        listOfNotNull(
            name, ArrayList(muster), weckzeitText, isEnabled, isSilent, weckerAn, kategorie.schluessel,
            dimmen?.let { ENTWURF_JSON.encodeToString(DimmenEntwurf.serializer(), it) }
        )

    companion object {
        /** Der Ausgangsstand des Formulars: der Typ, wie er ist, oder die Vorgaben einer Neuanlage. */
        fun aus(ausgang: ShiftDefinition?): TypEntwurf = TypEntwurf(
            name = ausgang?.name ?: "",
            // Ein Typ ohne Muster (frisch angelegter Ignorieren-Typ, #169) bekommt ein leeres Feld
            // statt einer leeren Liste - sonst gaebe es nichts zum Eintippen und das Speichern
            // bliebe gesperrt.
            muster = ausgang?.keywords?.ifEmpty { listOf("") } ?: listOf(""),
            // TIME_ONLY (ANZEIGE), NICHT PERSIST_TIME: diese beiden Stellen zeigen die vom Nutzer
            // eingetippte Weckzeit an und parsen sie zurueck. Persistiert wird sie danach ueber
            // LocalTimeSerializer, der bewusst PERSIST_TIME benutzt. Die Formate sind entkoppelt -
            // wer sie spaeter zusammenlegt, koppelt die Eingabe-Interpretation an ein
            // Persistenzformat.
            weckzeitText = ausgang?.alarmTime?.format(ZEIT_ANZEIGE)
                ?: String.format(Locale.ROOT, "%02d:%02d", AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE),
            isEnabled = ausgang?.isEnabled ?: true,
            isSilent = ausgang?.isSilent ?: false,
            weckerAn = ausgang?.weckerAn ?: true,
            // Die Kategorie ersetzt die Schalter „Krankmeldung“ und „Rufbereitschaft“ (Umbau E1).
            // Beim Speichern setzt `mitKategorie` beide Flags mit - die lesen alle heutigen Verbraucher.
            kategorie = ausgang?.kategorie ?: ShiftKategorie.DIENST,
            dimmen = DimmenEntwurf.aus(ausgang?.dimmen ?: TypDimmen())
        )

        /** Gegenstueck zu [alsSpeicherListe]; `null` bei einem Stand, den es nicht lesen kann. */
        fun ausSpeicherListe(liste: List<Any?>): TypEntwurf? = runCatching {
            TypEntwurf(
                name = liste[0] as String,
                muster = (liste[1] as List<*>).map { it as String },
                weckzeitText = liste[2] as String,
                isEnabled = liste[3] as Boolean,
                isSilent = liste[4] as Boolean,
                weckerAn = liste[5] as Boolean,
                kategorie = ShiftKategorie.vonSchluessel(liste[6] as String) ?: ShiftKategorie.DIENST,
                // Fehlt das Element, gilt der gespeicherte Stand. Ist es da, aber unlesbar, faellt der ganze Entwurf
                // weg (die Seite oeffnet wieder mit dem gespeicherten Typ) - nie ein Entwurf ohne Fenster.
                dimmen = liste.getOrNull(7)?.let { ENTWURF_JSON.decodeFromString(DimmenEntwurf.serializer(), it as String) }
            )
        }.getOrNull()
    }
}

private val ZEIT_ANZEIGE: DateTimeFormatter = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)

/** `encodeDefaults`, damit ein Fenster nach dem Zurueckholen Feld fuer Feld dasselbe ist. */
private val ENTWURF_JSON = Json { encodeDefaults = true }

/**
 * Der Dimm-Teil des Entwurfs: genau das, was die Typ-Seite zeigt und aendert - „Dimmen an diesem Tag“
 * ([TypDimmen.an]) und die deutbaren Fenster. Farbwaerme und undeutbare Fenster gehoeren nicht dazu; sie bleiben
 * am gespeicherten Typ ([anwenden]).
 */
@Serializable
internal data class DimmenEntwurf(val an: Boolean, val fenster: List<TypDimmFenster>) {

    /**
     * [gespeichert] mit diesem Stand. Neu kodiert werden die Fenster nur, wenn sie sich unterscheiden
     * ([TypDimmen.mitFenster] haengt undeutbare wieder an): ein Speichern, das nur Name oder Weckzeit aendert,
     * laesst das gespeicherte Dimmen Zeichen fuer Zeichen stehen.
     */
    fun anwenden(gespeichert: TypDimmen): TypDimmen {
        val mitSchalter = gespeichert.copy(an = an)
        return if (fenster == gespeichert.fenster) mitSchalter else mitSchalter.mitFenster(fenster)
    }

    /**
     * Dieser Stand, nachdem sich das Gespeicherte von [vorher] auf [neu] geaendert hat: was gegenueber [vorher]
     * unberuehrt ist (Schalter und Fenster je fuer sich), folgt [neu]; selbst Geaendertes bleibt.
     */
    fun nachgezogen(vorher: DimmenEntwurf, neu: DimmenEntwurf): DimmenEntwurf = DimmenEntwurf(
        an = if (an == vorher.an) neu.an else an,
        fenster = if (fenster == vorher.fenster) neu.fenster else fenster
    )

    companion object {
        fun aus(dimmen: TypDimmen): DimmenEntwurf = DimmenEntwurf(dimmen.an, dimmen.fenster)
    }
}

/**
 * Was der Schicht-Editor beim Speichern aus seinem Formular macht - ohne Compose, damit ein Test
 * es festhalten kann (Umbau Etappe 1, #174).
 *
 * Der Editor geht immer vom BESTEHENDEN Typ aus ([speichern]: `copy` statt neuem Konstruktoraufruf).
 * Ein neu gebautes `ShiftDefinition(...)` liesse jedes Feld, das der Editor nicht kennt (Kategorie
 * Frei/Urlaub, Wecker aus, spaeter Dimm-Fenster und DND), still auf seinen Default fallen - ein Typ
 * "Urlaub, Wecker aus" aus einem Import wuerde beim Umbenennen zu "Dienst, Wecker an".
 */
internal object ShiftEditFormular {

    /**
     * Die Vorlage fuer das „+“ der Typ-Liste (#190): ein leerer, noch NICHT gespeicherter Dienst-Typ mit
     * frischer `id`. Die Typ-Seite haengt an einer `id` (auch bei der Neuanlage); [TypEntwurf.aus] ergibt
     * daraus dieselben Vorgaben wie `aus(null)` - der Dialog davor hatte keine Vorlage.
     */
    fun leererTyp(neueId: () -> String = { UUID.randomUUID().toString() }): ShiftDefinition =
        ShiftDefinition(
            id = neueId(),
            name = "",
            keywords = emptyList(),
            alarmTime = LocalTime.of(AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE)
        )

    /** Getrimmt (Pflicht fuer die Wortgrenzen-Regex), ohne Leere, ohne Doppelte. */
    fun bereinigteMuster(muster: List<String>): List<String> =
        muster.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Zeigt der Editor Wecker, Weckzeit und stummen Wecker? Nein bei Krank (gibt den Tag frei, #142)
     * und Ignorieren (ergibt keine Schicht, #167): dort wuerde jedes dieser Felder einen Ablauf
     * zeigen, den es nicht gibt. Die Werte bleiben im Formular und im Typ stehen (E2).
     */
    fun zeigtWeckerBlock(kategorie: ShiftKategorie): Boolean =
        kategorie != ShiftKategorie.KRANK && kategorie != ShiftKategorie.IGNORIEREN

    /** Muss die Weckzeit gueltig sein, damit gespeichert werden darf? Nur wenn sie etwas bewirkt. */
    fun weckzeitPflicht(kategorie: ShiftKategorie, weckerAn: Boolean): Boolean =
        zeigtWeckerBlock(kategorie) && weckerAn

    /**
     * Die Weckzeit, die gespeichert wird - oder `null`, wenn das Formular nicht speicherbar ist.
     * Wirkt sie nicht (Wecker aus, Krank, Ignorieren), sperrt ein kaputter Eintrag im verborgenen
     * oder ausgegrauten Feld das Speichern nicht: es bleibt die bisherige Zeit, sonst die Vorgabe
     * (06:00, nicht 00:00 - schaltet der Nutzer den Wecker spaeter ein, soll er nicht um Mitternacht klingeln).
     */
    fun zeitZumSpeichern(
        kategorie: ShiftKategorie,
        weckerAn: Boolean,
        eingegeben: LocalTime?,
        ausgang: ShiftDefinition?
    ): LocalTime? =
        if (weckzeitPflicht(kategorie, weckerAn)) {
            eingegeben
        } else {
            eingegeben ?: ausgang?.alarmTime
                ?: LocalTime.of(AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE)
        }

    /**
     * Der Typ, den der Editor speichert: [ausgang] (der bearbeitete oder der vorbelegte, noch nicht
     * gespeicherte Typ) mit den Formularwerten, oder bei `null` ein frischer Typ. Die Kategorie
     * kommt ueber [ShiftDefinition.mitKategorie] - sie setzt `isSickDay` und `isOnCall` mit, die
     * alle heutigen Leser (Erkennung, Ruf-Abfrage, DND) lesen.
     */
    fun speichern(
        ausgang: ShiftDefinition?,
        name: String,
        muster: List<String>,
        alarmTime: LocalTime,
        isEnabled: Boolean,
        weckerAn: Boolean,
        isSilent: Boolean,
        kategorie: ShiftKategorie,
        neueId: () -> String = { UUID.randomUUID().toString() }
    ): ShiftDefinition =
        (ausgang ?: ShiftDefinition(id = neueId(), name = name, keywords = muster, alarmTime = alarmTime))
            .copy(
                name = name.trim(),
                keywords = muster,
                alarmTime = alarmTime,
                isEnabled = isEnabled,
                isSilent = isSilent,
                weckerAn = weckerAn
            )
            .mitKategorie(kategorie)
}
