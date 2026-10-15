package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.kurzesDatum
import java.time.LocalDate

/**
 * #142: was nach einer gescheiterten Freigabe aus den Weckern des GESCHEITERTEN Tages geworden
 * ist. Das ViewModel ermittelt es (`stelleNachAbgebrochenerFreigabeWiederHer`), [freigabeHinweis]
 * macht daraus den Text.
 *
 * Getrennt, damit jeder Text rein pruefbar ist: welche Zusage ein Fehlertext macht ("klingeln wie
 * geplant" oder nicht), haengt genau an diesem Zustand, und genau dort lag die Falle aus Review S6.
 */
internal sealed interface Wiederherstellung {
    /** Der Tag scheiterte ohne [com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreigabeZurueckgenommenException] - nichts angefasst. */
    data object NichtsGeaendert : Wiederherstellung

    /** Markierung zurueckgenommen, alle Wecker des Tages wieder gestellt. */
    data object KlingelnWieGeplant : Wiederherstellung

    /** [anzahl] Wecker des Tages liessen sich nicht wieder stellen. */
    data class Misslungen(val anzahl: Int) : Wiederherstellung

    /** Die Markierung liess sich nicht raeumen - jedes Wiederstellen weist der Backstop ab. */
    data object MarkierungStehtNoch : Wiederherstellung

    /**
     * Review S6: der Tag ist zugleich laut Kalender krankgemeldet. Der Backstop weist das
     * Wiederstellen ab, also darf der Text NICHT "klingeln" versprechen.
     */
    data object KalenderKrank : Wiederherstellung
}

/**
 * PURE, TESTBAR: die Rueckmeldung zu „Dienst fällt aus“ fuer einen Tag oder einen Zeitraum, `null`
 * = nichts zu sagen.
 *
 * @param wiederherstellung `null` = ERFOLG fuer den ganzen Zeitraum; sonst der Zustand des
 *   gescheiterten Tages.
 * @param freigegebenBis letzter erfolgreich freigegebener Tag, `null` = keiner.
 * @param fehlerTag der gescheiterte Tag (nur im Fehlerfall von Belang).
 *
 * #142: ein Zeitraum bekommt IMMER eine Erfolgsmeldung - seine Wirkung liegt ausserhalb des
 * Bildschirms (Tage, die gerade nicht in der Liste stehen). Der Einzeltag bleibt wie bisher still,
 * ausser ein „Nicht wecken“ wurde mit aufgehoben.
 */
internal fun freigabeHinweis(
    von: LocalDate,
    bis: LocalDate,
    freigegebenBis: LocalDate?,
    geloeschteWecker: Int,
    ueberspringenAufgehoben: Boolean,
    fehlerTag: LocalDate?,
    wiederherstellung: Wiederherstellung?
): String? {
    val zeitraum = von.isBefore(bis)

    if (wiederherstellung == null) {
        if (!zeitraum) {
            return if (ueberspringenAufgehoben) {
                "„Nicht wecken“ für diesen Tag ist damit zurückgenommen – es wird nicht mehr gebraucht."
            } else {
                null
            }
        }
        val basis = when (geloeschteWecker) {
            // kurzesDatum endet schon auf "." ("Fr 16.10.") - kein zweiter Punkt dahinter.
            0 -> "Dienst fällt aus bis ${kurzesDatum(bis)} Eigene Wecker bleiben."
            1 -> "Dienst fällt aus bis ${kurzesDatum(bis)} – 1 Wecker entfällt, eigene Wecker bleiben."
            else -> "Dienst fällt aus bis ${kurzesDatum(bis)} – $geloeschteWecker Wecker entfallen, eigene Wecker bleiben."
        }
        return if (ueberspringenAufgehoben) {
            "$basis „Nicht wecken“ ist damit zurückgenommen – es wird nicht mehr gebraucht."
        } else {
            basis
        }
    }

    val tag = fehlerTag ?: von

    if (freigegebenBis == null) {
        // Kein einziger Tag freigegeben: die bestehenden Einzeltag-Texte. Ein Zeitraum sagt
        // zusaetzlich, dass die Tage danach unangetastet sind - ausser bei "nichts geaendert",
        // das gilt ohnehin fuer alles.
        val text = when (wiederherstellung) {
            Wiederherstellung.NichtsGeaendert ->
                return "„Dienst fällt aus“ hat nicht geklappt – an den Weckern hat sich nichts geändert. Bitte noch einmal versuchen."
            Wiederherstellung.KlingelnWieGeplant ->
                "„Dienst fällt aus“ hat nicht geklappt. Die Wecker dieses Tages klingeln wie geplant."
            is Wiederherstellung.Misslungen ->
                "„Dienst fällt aus“ hat nicht geklappt, und ${wiederherstellung.anzahl} Wecker dieses Tages stehen jetzt nicht mehr – bitte in der Liste im Bereich „Wecker“ prüfen."
            Wiederherstellung.MarkierungStehtNoch ->
                "„Dienst fällt aus“ hat nicht geklappt und ließ sich nicht sauber zurücknehmen. Bitte in der Liste im Bereich „Wecker“ prüfen, ob die Wecker dieses Tages noch stehen."
            Wiederherstellung.KalenderKrank ->
                "„Dienst fällt aus“ hat nicht geklappt. Der Tag ist laut Kalender krankgemeldet – es klingelt trotzdem kein Wecker aus dem Dienstplan."
        }
        return if (zeitraum) "$text Für die Tage danach hat sich nichts geändert." else text
    }

    // Teilerfolg: die Tage bis [freigegebenBis] bleiben frei (Eigentuemer-Entscheidung #142).
    val praefix = "Nur bis ${kurzesDatum(freigegebenBis)} freigegeben – ab ${kurzesDatum(tag)} hat es nicht geklappt"
    val danachGibtEsTage = tag.isBefore(bis)
    return when (wiederherstellung) {
        Wiederherstellung.NichtsGeaendert, Wiederherstellung.KlingelnWieGeplant ->
            "$praefix, diese Wecker klingeln. Bitte noch einmal versuchen."
        is Wiederherstellung.Misslungen -> {
            val satz = "$praefix. ${wiederherstellung.anzahl} Wecker vom ${kurzesDatum(tag)} stehen jetzt nicht mehr – bitte in der Liste im Bereich „Wecker“ prüfen."
            if (danachGibtEsTage) "$satz Die Wecker danach klingeln." else satz
        }
        Wiederherstellung.MarkierungStehtNoch ->
            "$praefix. Bitte in der Liste im Bereich „Wecker“ prüfen, ob die Wecker ab ${kurzesDatum(tag)} noch stehen."
        Wiederherstellung.KalenderKrank -> {
            // Review S6: der Backstop weist das Wiederstellen ab - "diese Wecker klingeln" waere
            // fuer den gescheiterten Tag gelogen.
            val satz = "$praefix. Am ${kurzesDatum(tag)} klingelt trotzdem kein Wecker aus dem Dienstplan, der Tag ist laut Kalender krankgemeldet."
            if (danachGibtEsTage) "$satz Ab dem Tag danach klingeln die Wecker. Bitte noch einmal versuchen." else satz
        }
    }
}

/**
 * PURE, TESTBAR (#142): welcher Freigabe-Hinweis wo steht.
 *
 * Ein FEHLERtext steht ueberall, bis der naechste Vorgang ihn raeumt - der schlechteste Fall darf
 * nicht stumm sein. Eine ERFOLGSmeldung ([erfolgBis] gesetzt) dagegen:
 *  - nie auf der Uebersicht: dort sagt die Ausfall-Karte dasselbe, und zwar nur, solange es gilt.
 *    Der Text lebt im ViewModel oft tagelang weiter; als Warnung unter der Karte behauptete er am
 *    20.10. noch einen Ausfall "bis 17.10.".
 *  - im Wecker-Tab nur, bis ihr letzter Tag vorbei ist.
 */
internal fun sichtbarerFreigabeHinweis(
    hinweis: String?,
    erfolgBis: LocalDate?,
    heute: LocalDate,
    aufUebersicht: Boolean
): String? = when {
    hinweis == null -> null
    erfolgBis == null -> hinweis
    aufUebersicht -> null
    erfolgBis.isBefore(heute) -> null
    else -> hinweis
}
