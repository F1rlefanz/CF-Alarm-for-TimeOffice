package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie

/**
 * Die Schichttypen-Liste als Bloecke (Umbau „Schichttyp als Zentrale“, Etappe 1, SPEC 3.1): eine
 * Gruppe je Kategorie in der Reihenfolge der SPEC (E1), danach die feste Zeile „Ohne Kalendereintrag“.
 *
 * GRUPPIERT WIRD NUR IN DER ANZEIGE. Die gespeicherte Reihenfolge der Typen entscheidet die Erkennung
 * (der erste Treffer gewinnt, `ShiftConfig.findDefinitionFor`); diese Datei liest sie nur, sortiert
 * sie nie um und schreibt nichts zurueck. Innerhalb einer Gruppe bleibt sie, wie gespeichert.
 *
 * Eine leere Kategorie bleibt als Gruppe stehen - auch Ignorieren. (Der Prototyp blendet die leere
 * Ignorieren-Gruppe aus, SPEC 3.1 und der Auftrag zeigen jede leere Gruppe; Entscheidung im Issue #170.)
 */
sealed interface SchichttypBlock {

    /** Alle Typen EINER Kategorie, in gespeicherter Reihenfolge. */
    data class Gruppe(
        val kategorie: ShiftKategorie,
        val titel: String,
        /** „Sonderfall“ (E3): Rufbereitschaft und Krank verhalten sich bei zwei Eintraegen am Tag besonders. */
        val sonderfall: Boolean,
        val typen: List<ShiftDefinition>
    ) : SchichttypBlock {
        val leer: Boolean get() = typen.isEmpty()
    }

    /**
     * „Ohne Kalendereintrag“ (E5): keine Definition, sondern eine feste Zeile fuer Tage, an denen im
     * Kalender gar nichts steht. Nicht antippbar und nicht loeschbar, bis die Wahl „Wie <Typ>“ gebaut
     * ist - bis dahin verspricht sie nur, was heute stimmt: an solchen Tagen klingelt nichts.
     */
    data object FesteZeile : SchichttypBlock {
        const val titel: String = SchichttypTexte.FESTE_ZEILE
        const val code: String = SchichttypTexte.OHNE_CODE
        const val kurzform: String = SchichttypTexte.KEIN_WECKER
    }
}

/** Die Texte der gruppierten Liste - eine Stelle, damit Anzeige und Test nicht auseinanderlaufen. */
object SchichttypTexte {
    const val LEERE_GRUPPE = "Noch kein Typ in dieser Kategorie."
    const val SONDERFALL = "Sonderfall"
    const val FESTE_ZEILE = "Ohne Kalendereintrag"

    /** Anstelle eines Codes: die feste Zeile und ein Typ ganz ohne Muster. */
    const val OHNE_CODE = "–"

    /** Kurzform eines Typs mit `weckerAn = false` und der festen Zeile (siehe [kurzform]). */
    const val KEIN_WECKER = "kein Wecker"

    /** Kurzform eines aktiven Ignorieren-Typs (siehe [kurzform]). */
    const val WIRD_UEBERGANGEN = "wird übergangen"

    /** Statuszeile eines AUSGESCHALTETEN Ignorieren-Typs: er uebergeht seine Codes nicht mehr. */
    const val IGNORIEREN_AUSGESCHALTET = "Ausgeschaltet — wird nicht mehr übergangen"
}

/** Die sieben Gruppen in der Reihenfolge der SPEC (E1), danach die feste Zeile. */
internal fun schichttypBloecke(definitions: List<ShiftDefinition>): List<SchichttypBlock> {
    val nachKategorie = definitions.groupBy { it.kategorie }
    return ShiftKategorie.entries.map { kategorie ->
        SchichttypBlock.Gruppe(
            kategorie = kategorie,
            titel = kategorie.titel(),
            sonderfall = kategorie == ShiftKategorie.RUFBEREITSCHAFT || kategorie == ShiftKategorie.KRANK,
            typen = nachKategorie[kategorie].orEmpty()
        )
    } + SchichttypBlock.FesteZeile
}

/**
 * Der Code einer Zeile: das erste Muster, das etwas treffen kann. Ohne eines (ein frisch angelegter
 * Ignorieren-Typ startet ohne Muster, #169) steht ein Strich.
 */
internal fun ShiftDefinition.listenCode(): String =
    keywords.firstOrNull { it.isNotBlank() }?.trim() ?: SchichttypTexte.OHNE_CODE

/** Ohne `else`: eine achte Kategorie muss hier bewusst benannt werden. */
internal fun ShiftKategorie.titel(): String = when (this) {
    ShiftKategorie.DIENST -> "Dienst"
    ShiftKategorie.RUFBEREITSCHAFT -> "Rufbereitschaft"
    ShiftKategorie.FREI -> "Frei"
    ShiftKategorie.URLAUB -> "Urlaub"
    ShiftKategorie.FORTBILDUNG -> "Fortbildung"
    ShiftKategorie.KRANK -> "Krank"
    ShiftKategorie.IGNORIEREN -> "Ignorieren"
}
