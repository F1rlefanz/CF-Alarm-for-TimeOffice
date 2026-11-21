package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.runtime.Immutable
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import java.util.Locale

/*
 * Umbau Etappe 3 (#215, SPEC E6/E9/E14): ein Dimm-Fenster in der Sprache der Typ-Seite - „Wann / Von /
 * Bis / Mit % / Schon beim Wecker enden“ - und zurueck, dazu die Texte fuer Fenster-Titel und
 * Listen-Kurzform (Prototyp `Main.dc.html`: `fensterText`, `dimText`, `BEZ`, `bisHinweis`,
 * `zeigeBeimWecker`, die `zeile`-Kurzform).
 *
 * REINE FUNKTION, KEINE ZWEITE FENSTERLOGIK: Die Form ist nur eine ANDERE SCHREIBWEISE desselben
 * `DimWindow`, das `DimWindowResolver` heute aufloest. Welche Anker zu welchem „Wann“ gehoeren, steht
 * in [kodiere] (EINE Stelle) und ist in `DimmFensterFormTest` gegen `buildRuleSpans` gemessen - nicht
 * aus dem Prototyp abgeschrieben, denn der Prototyp rechnet selbst.
 *
 * DIE ABBILDUNG IST TOTAL ([deute]): jedes `DimWindow` ergibt eine Form oder [DimFensterAnsicht.NichtDarstellbar].
 * Was die Form NICHT ausdrueckt (Nachlauf, „Hell ab“, Blockpositionen, unbenutzte Versaetze, die
 * Kennung `startNurAmWecktag` ausserhalb von „Nacht davor“), bleibt beim Schreiben ueber [zuFenster] mit
 * `basis` erhalten - die Form besitzt nur die Felder, die sie zeigt.
 */

/** Wann ein Fenster liegt (Prototyp `bezug`). */
enum class DimWann(val label: String, val erklaerung: String, val bisHinweis: String) {
    /** Prototyp `vorher`. */
    NACHT_DAVOR("Nacht davor", "Vom Vorabend bis zum Aufstehen an diesem Tag", "am Tag selbst"),

    /** Prototyp `danach`. */
    ABEND_DANACH("Abend danach", "Ab dem Abend dieses Tages", "am Folgetag, wenn früher als „Von“"),

    /** Prototyp `nachher`. */
    NACH_DEM_DIENST("Nach dem Dienst", "Ab Dienstende laut Kalender – z. B. Tagschlaf", "nach Dienstende");

    /** „Von“ ist eine Uhrzeit - nur „Nach dem Dienst“ beginnt am Dienstende laut Kalender. */
    val hatVon: Boolean get() = this != NACH_DEM_DIENST
}

/**
 * Ein Fenster, wie die Typ-Seite es zeigt. Alle Zeiten in Minuten seit Mitternacht.
 *
 * @property vonMinuten `null` genau bei [DimWann.NACH_DEM_DIENST] („Von: Dienstende“).
 * @property beimWecker „Schon beim Wecker enden“: das Ende ist „Weckzeit, spaetestens [bisMinuten]“.
 *   Bei [DimWann.NACH_DEM_DIENST] gibt es das nicht (Prototyp `zeigeBeimWecker`).
 */
@Immutable
data class DimFensterForm(
    val wann: DimWann,
    val vonMinuten: Int?,
    val bisMinuten: Int,
    val prozent: Int,
    val beimWecker: Boolean
) {
    /** Die Form mit den Pflichtangaben, die zu [wann] passen: nach dem Dienst kein „Von“, kein Wecker-Ende. */
    fun normalisiert(): DimFensterForm = when (wann) {
        DimWann.NACH_DEM_DIENST -> copy(vonMinuten = null, beimWecker = false)
        DimWann.NACHT_DAVOR, DimWann.ABEND_DANACH -> copy(vonMinuten = vonMinuten ?: STANDARD_VON)
    }

    companion object {
        /** Der Vorgabewert, wenn ein Wechsel auf ein „Wann“ mit „Von“ keinen mitbringt (Prototyp: 22:00). */
        const val STANDARD_VON = 22 * 60
    }
}

/** Warum ein Fenster keine Form hat. */
enum class NichtDarstellbarGrund {
    /** Die Anker-Kombination hat kein „Wann“ (z. B. Ende AM Wecker ohne Uhrzeit-Grenze, Anker relativ zum Wecker). */
    ANKER,

    /** „Nach dem Dienst“ mit Versatz ≠ 0: die Seite sagte „ab Dienstende“, das Fenster beginnt aber spaeter/frueher. */
    VERSATZ,

    /** Start „Weckzeit-Uhrzeit“ mit einer Kennung, die „Von“ und „Bis“ nicht vorhersagen (siehe [kodiere]). */
    KENNUNG
}

/** Ergebnis von [deute]: eine Form - oder die ehrliche Auskunft, dass die Typ-Seite dieses Fenster nicht ausdrueckt. */
sealed interface DimFensterAnsicht {
    @Immutable
    data class Darstellbar(val form: DimFensterForm) : DimFensterAnsicht

    /** Bearbeitet wird so ein Fenster unter „Weitere Optionen“; die Seite zeigt es mit Kurzform. */
    @Immutable
    data class NichtDarstellbar(val grund: NichtDarstellbarGrund, val prozent: Int) : DimFensterAnsicht
}

object DimFensterAbbildung {

    // ---------------------------------------------------------------------------------------------
    // Fenster -> Form
    // ---------------------------------------------------------------------------------------------

    /**
     * Das Fenster in der Sprache der Typ-Seite. TOTAL: der aeussere `when` ueber die Start-, der innere
     * ueber die Ende-Anker ist ohne `else`, ein neuer [DimAnchor] bricht den Bau statt still durchzufallen.
     *
     * Eine Form ist nur dann zurueckgegeben, wenn [zuFenster] das Fenster mit dieser Form unveraendert
     * wieder erzeugt (Test `fensterFormFenster_istIdentisch`).
     */
    fun deute(f: TypDimmFenster): DimFensterAnsicht {
        val w = f.fenster
        fun form(wann: DimWann, beimWecker: Boolean, von: Int?) = DimFensterAnsicht.Darstellbar(
            DimFensterForm(wann, von, w.endClockMinutes, f.staerke, beimWecker)
        )

        fun nicht(grund: NichtDarstellbarGrund) = DimFensterAnsicht.NichtDarstellbar(grund, f.staerke)
        return when (w.startAnchor) {
            DimAnchor.CLOCK -> when (w.endAnchor) {
                DimAnchor.CLOCK -> form(DimWann.ABEND_DANACH, beimWecker = false, von = w.startClockMinutes)
                DimAnchor.ALARM_SONST_CLOCK -> form(DimWann.ABEND_DANACH, beimWecker = true, von = w.startClockMinutes)
                DimAnchor.ALARM, DimAnchor.SHIFT_END -> nicht(NichtDarstellbarGrund.ANKER)
            }

            DimAnchor.ALARM_SONST_CLOCK -> when (w.endAnchor) {
                DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK ->
                    if (w.startNurAmWecktag != gleicherTag(w.startClockMinutes, w.endClockMinutes)) {
                        nicht(NichtDarstellbarGrund.KENNUNG)
                    } else {
                        form(DimWann.NACHT_DAVOR, beimWecker = w.endAnchor == DimAnchor.ALARM_SONST_CLOCK, von = w.startClockMinutes)
                    }

                DimAnchor.ALARM, DimAnchor.SHIFT_END -> nicht(NichtDarstellbarGrund.ANKER)
            }

            DimAnchor.SHIFT_END -> when (w.endAnchor) {
                DimAnchor.CLOCK ->
                    if (w.startOffsetMinutes != 0) nicht(NichtDarstellbarGrund.VERSATZ)
                    else form(DimWann.NACH_DEM_DIENST, beimWecker = false, von = null)

                DimAnchor.ALARM_SONST_CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END -> nicht(NichtDarstellbarGrund.ANKER)
            }

            DimAnchor.ALARM -> when (w.endAnchor) {
                DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END ->
                    nicht(NichtDarstellbarGrund.ANKER)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Form -> Fenster
    // ---------------------------------------------------------------------------------------------

    /**
     * Das [DimWindow] zur [form], geschrieben ueber [basis] (bei einem neuen Fenster: die Vorgabe).
     * Ueberschrieben werden NUR die Felder der Form; Nachlauf, „Hell ab“, Blockpositionen und die
     * Versaetze unbenutzter Anker bleiben, wie [basis] sie hat.
     *
     * Die Kodierung haengt NICHT davon ab, ob der Typ einen Wecker hat - sonst schriebe das Umschalten
     * des Weckers gespeicherte Fenster um. Nur die Anzeige ([zeigeBeimWecker], [fensterText]) fragt danach.
     */
    fun zuFenster(form: DimFensterForm, basis: DimWindow = DimWindow()): DimWindow = kodiere(form.normalisiert(), basis)

    /** [f] mit der bearbeiteten [form]: das Fenster ueber [zuFenster], die Staerke aus „Mit %“. */
    fun mitForm(f: TypDimmFenster, form: DimFensterForm): TypDimmFenster =
        f.copy(fenster = zuFenster(form, f.fenster), staerke = form.prozent)

    /**
     * DIE EINE STELLE, an der „Wann“ zu Ankern wird - gemessen gegen `DimWindowResolver.buildRuleSpans`
     * (Test `DimmFensterFormTest`, je Wann an Dienst-, Frei-, Ruf- und Tag ohne Eintrag):
     *
     * - **Abend danach** = Start CLOCK, Ende CLOCK (oder `ALARM_SONST_CLOCK` mit „beim Wecker“). Beide Paare
     *   loest der Resolver als „die Nacht DIESES Kalendertags“ auf: Von am Tag selbst, Bis am Folgetag, wenn
     *   es nicht nach Von liegt. Braucht keinen Kalendereintrag - auch „Ohne Kalendereintrag“ hat sie.
     * - **Nach dem Dienst** = Start `SHIFT_END` (Versatz 0), Ende CLOCK. Braucht ein Dienstende.
     * - **Nacht davor** = Start `ALARM_SONST_CLOCK`, Ende CLOCK (oder `ALARM_SONST_CLOCK` mit „beim Wecker“),
     *   dazu `startNurAmWecktag = Von < Bis`. Gemessen die EINZIGE Kodierung, die zugleich: (1) bei Von >= Bis
     *   am Vorabend beginnt - NUR, WENN DIE WECKZEIT VOR Von LIEGT (offene Grenze, siehe unten), (2) „spaetestens Bis“ haelt (Wecker 10:00, Bis 07:00 -> Ende 07:00), (3) bei
     *   Von < Bis (z. B. Vorschlaf 16:00 bis 19:30) am Tag SELBST beginnt und bei einem Wecker davor entfaellt
     *   statt 21,5 h ab dem Vortag zu dimmen (E14, #151), und (4) vom „Abend danach“ unterscheidbar bleibt
     *   (Rundreise). `CLOCK -> ALARM` ignoriert Bis; `CLOCK -> CLOCK/ALARM_SONST_CLOCK` beginnt immer am Tag
     *   selbst. Braucht einen Kalendereintrag mit Weckzeit - ohne gibt es kein „Aufstehen“.
     *
     *   OFFENE GRENZE (Befund des unabhaengigen Pruefers, Betreuer-Frage in #215): der Resolver rollt den
     *   Start nur dann auf den Vortag zurueck, wenn Von NACH der Weckzeit liegt (`clockAtOrBefore`). Bei
     *   Von >= Bis und einer Weckzeit NACH Von (Nachtdienst, Wecker 19:30, Von 16:00, Bis 07:00) beginnt das
     *   Fenster am Diensttag und laeuft ueber die ganze Nacht (960..1860 statt -480..420); mit „beim Wecker“
     *   dimmt es nur den Nachmittag. Mit den heutigen Ankern nicht ausdrueckbar, Test
     *   `nachtDavor_vorabendGiltNurWennDieWeckzeitVorVonLiegt_bekannteGrenze`.
     *
     *   ACHTUNG, bewusst: `ALARM_SONST_CLOCK` ist laut `DimAnchor` „kein START-Anker“; die Aufloesung
     *   behandelt ihn am Start wie CLOCK, aber ueber den Schichtpfad (`loeseSchichtFenster`) statt ueber die
     *   Kalendernacht. Genau dieser Unterschied traegt hier. Wer die Aufloesung dort aendert, bricht „Nacht
     *   davor“ - die Messungen in `DimmFensterFormTest` zeigen es.
     *
     * `startNurAmWecktag` ist die Kennung, die [deute] zur Gegenprobe braucht: bei Start `ALARM_SONST_CLOCK`
     * ist sie durch Von und Bis festgelegt, ein Fenster mit abweichender Kennung bleibt [NichtDarstellbarGrund.KENNUNG].
     */
    private fun kodiere(form: DimFensterForm, basis: DimWindow): DimWindow {
        val ende = if (form.beimWecker) DimAnchor.ALARM_SONST_CLOCK else DimAnchor.CLOCK
        return when (form.wann) {
            DimWann.ABEND_DANACH -> basis.copy(
                startAnchor = DimAnchor.CLOCK,
                startClockMinutes = form.vonMinuten!!,
                endAnchor = ende,
                endClockMinutes = form.bisMinuten
            )

            DimWann.NACHT_DAVOR -> basis.copy(
                startAnchor = DimAnchor.ALARM_SONST_CLOCK,
                startClockMinutes = form.vonMinuten!!,
                endAnchor = ende,
                endClockMinutes = form.bisMinuten,
                startNurAmWecktag = gleicherTag(form.vonMinuten, form.bisMinuten)
            )

            DimWann.NACH_DEM_DIENST -> basis.copy(
                startAnchor = DimAnchor.SHIFT_END,
                // Die Seite sagt „ab Dienstende“; ein Versatz aus [basis] (Vorgabe -120!) machte das falsch.
                startOffsetMinutes = 0,
                endAnchor = DimAnchor.CLOCK,
                endClockMinutes = form.bisMinuten
            )
        }
    }

    /** „Von“ liegt vor „Bis“: das Fenster bleibt an EINEM Tag (statt ueber Mitternacht zu laufen). */
    private fun gleicherTag(vonMinuten: Int, bisMinuten: Int): Boolean = vonMinuten < bisMinuten

    // ---------------------------------------------------------------------------------------------
    // Texte (Prototyp: fensterText, dimText, BEZ, bisHinweis, zeigeBeimWecker, zeile)
    // ---------------------------------------------------------------------------------------------

    const val KEIN_FENSTER = "Nicht dimmen"

    /** Der Text fuer ein Fenster, das die Typ-Seite nicht ausdrueckt. */
    const val NICHT_DARSTELLBAR = "Eigene Zeiten"

    /** Kurzform eines einzelnen nicht darstellbaren Fensters in der Typ-Liste. */
    const val KURZFORM_NICHT_DARSTELLBAR = "dimmt (eigene Zeiten)"

    /** Ob die Seite „Schon beim Wecker enden“ zeigt: nicht nach dem Dienst, und nur bei einem Typ mit Wecker. */
    fun zeigeBeimWecker(wann: DimWann, weckerAn: Boolean): Boolean = wann != DimWann.NACH_DEM_DIENST && weckerAn

    /** „HH:mm“ aus Minuten seit Mitternacht; ausserhalb des Tages (Import) auf den Tag gefaltet statt zu werfen. */
    fun uhrzeit(minuten: Int): String {
        val m = Math.floorMod(minuten, MINUTEN_PRO_TAG)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    /** Prototyp `fensterText`: „Abends 20:00 bis Wecker, spätestens 07:00 · 55 %“. */
    fun fensterText(ansicht: DimFensterAnsicht, weckerAn: Boolean): String = when (ansicht) {
        is DimFensterAnsicht.NichtDarstellbar -> "$NICHT_DARSTELLBAR · ${ansicht.prozent} %"
        is DimFensterAnsicht.Darstellbar -> {
            val f = ansicht.form
            val prozent = " · ${f.prozent} %"
            when (f.wann) {
                DimWann.NACH_DEM_DIENST -> "Nach dem Dienst bis ${uhrzeit(f.bisMinuten)}$prozent"
                DimWann.NACHT_DAVOR, DimWann.ABEND_DANACH -> {
                    val bis = if (f.beimWecker && weckerAn) "Wecker, spätestens ${uhrzeit(f.bisMinuten)}" else uhrzeit(f.bisMinuten)
                    val abends = if (f.wann == DimWann.ABEND_DANACH) "Abends " else ""
                    "$abends${uhrzeit(f.vonMinuten ?: FALLBACK_VON)} bis $bis$prozent"
                }
            }
        }
    }

    /** Prototyp: der Titel eines Fensters auf der Typ-Seite, „Fenster 1: 20:00 bis Wecker, spätestens 07:00 · 55 %“. */
    fun titel(nummer: Int, f: TypDimmFenster, weckerAn: Boolean): String =
        "Fenster $nummer: ${fensterText(deute(f), weckerAn)}"

    /**
     * Prototyp `dimText`: alle Fenster mit „ + “ verbunden, sonst [KEIN_FENSTER]. Ein Typ mit
     * ausgeschaltetem Dimmen ([TypDimmen.an] `false`) dimmt nicht - egal, was gespeichert bleibt
     * („Ausgeschaltet bleibt ausgeschaltet“, SPEC 4); die Seite sagt dann dasselbe wie ohne Fenster.
     * Fenster, die diese Version nicht deuten kann ([TypDimmen.unlesbareFenster]), dimmen nicht und
     * stehen hier nicht.
     */
    fun dimText(dimmen: TypDimmen, weckerAn: Boolean): String {
        val fenster = wirksameFenster(dimmen)
        if (fenster.isEmpty()) return KEIN_FENSTER
        return fenster.joinToString(" + ") { fensterText(deute(it), weckerAn) }
    }

    /**
     * Die Kurzform einer Zeile der Typ-Liste (E9, Prototyp-`zeile`): „dimmt 20:00–Wecker“,
     * „dimmt nach Dienst“, „3 Dimm-Fenster“ - `null`, wenn der Typ nicht dimmt (die Zeile sagt dann
     * nichts zum Dimmen).
     */
    fun kurzform(dimmen: TypDimmen, weckerAn: Boolean): String? {
        val fenster = wirksameFenster(dimmen)
        if (fenster.isEmpty()) return null
        if (fenster.size > 1) return "${fenster.size} Dimm-Fenster"
        return when (val a = deute(fenster.single())) {
            is DimFensterAnsicht.NichtDarstellbar -> KURZFORM_NICHT_DARSTELLBAR
            is DimFensterAnsicht.Darstellbar -> when (a.form.wann) {
                DimWann.NACH_DEM_DIENST -> "dimmt nach Dienst"
                DimWann.NACHT_DAVOR, DimWann.ABEND_DANACH -> {
                    val bis = if (a.form.beimWecker && weckerAn) "Wecker" else uhrzeit(a.form.bisMinuten)
                    "dimmt ${uhrzeit(a.form.vonMinuten ?: FALLBACK_VON)}–$bis"
                }
            }
        }
    }

    /** Die Fenster, die wirken koennen: leer bei ausgeschaltetem Dimmen. */
    private fun wirksameFenster(dimmen: TypDimmen): List<TypDimmFenster> = if (dimmen.an) dimmen.fenster else emptyList()

    private const val MINUTEN_PRO_TAG = 24 * 60
    private const val FALLBACK_VON = DimFensterForm.STANDARD_VON
}
