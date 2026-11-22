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
 * `basis` erhalten - die Form besitzt nur die Felder, die sie zeigt. Das Feld `startAmVorabend` (#227)
 * besitzt sie ganz: jedes „Wann“ schreibt es, nur „Nacht davor“ mit Von >= Bis setzt es.
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

    /**
     * `startNurAmWecktag` und `startAmVorabend` stehen in einer Kombination mit Anker und Von/Bis, die [kodiere]
     * nie schriebe (z. B. das Vorabend-Feld bei Von < Bis, wo der Resolver es ignoriert, oder ein Weckzeit-Start
     * bei Von >= Bis) - siehe [kodiere]. Nie „Abend danach“, denn der haette das Feld beim Schreiben verloren.
     */
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
        val vonNachBis = vonNichtVorBis(w.startClockMinutes, w.endClockMinutes)
        return when (w.startAnchor) {
            // Ohne das Feld ist es die Nacht NACH dem Tag. Mit Feld nur genau die Kodierung der Nacht davor
            // (Von >= Bis, Kennung aus); jede andere Kombination mit Feld - auch das wirkungslose Feld bei
            // Von < Bis - ist nie „Abend danach“, denn diese Seite haette es beim Schreiben wieder abgeraeumt.
            DimAnchor.CLOCK -> when (w.endAnchor) {
                DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK -> {
                    val beimWecker = w.endAnchor == DimAnchor.ALARM_SONST_CLOCK
                    when {
                        !w.startAmVorabend -> form(DimWann.ABEND_DANACH, beimWecker, von = w.startClockMinutes)
                        vonNachBis && !w.startNurAmWecktag -> form(DimWann.NACHT_DAVOR, beimWecker, von = w.startClockMinutes)
                        else -> nicht(NichtDarstellbarGrund.KENNUNG)
                    }
                }

                DimAnchor.ALARM, DimAnchor.SHIFT_END -> nicht(NichtDarstellbarGrund.ANKER)
            }

            // Der Weckzeit-Start traegt nur noch „Nacht davor“ mit Von < Bis (E14); bei Von >= Bis ist er
            // kein „Wann“ der Seite mehr.
            DimAnchor.ALARM_SONST_CLOCK -> when (w.endAnchor) {
                DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK ->
                    if (!vonNachBis && w.startNurAmWecktag && !w.startAmVorabend) {
                        form(DimWann.NACHT_DAVOR, beimWecker = w.endAnchor == DimAnchor.ALARM_SONST_CLOCK, von = w.startClockMinutes)
                    } else {
                        nicht(NichtDarstellbarGrund.KENNUNG)
                    }

                DimAnchor.ALARM, DimAnchor.SHIFT_END -> nicht(NichtDarstellbarGrund.ANKER)
            }

            // Erst der Versatz, dann das Feld: ein Fenster mit beidem sagt dem Nutzer zuerst, dass es
            // nicht „ab Dienstende“ beginnt.
            DimAnchor.SHIFT_END -> when (w.endAnchor) {
                DimAnchor.CLOCK -> when {
                    w.startOffsetMinutes != 0 -> nicht(NichtDarstellbarGrund.VERSATZ)
                    w.startAmVorabend -> nicht(NichtDarstellbarGrund.KENNUNG)
                    else -> form(DimWann.NACH_DEM_DIENST, beimWecker = false, von = null)
                }

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
     * - **Abend danach** = Start CLOCK, Ende CLOCK (oder `ALARM_SONST_CLOCK` mit „beim Wecker“), Feld
     *   `startAmVorabend` aus. Beide Paare loest der Resolver als „die Nacht DIESES Kalendertags“ auf: Von am
     *   Tag selbst, Bis am Folgetag, wenn es nicht nach Von liegt. Braucht keinen Kalendereintrag.
     * - **Nach dem Dienst** = Start `SHIFT_END` (Versatz 0), Ende CLOCK. Braucht ein Dienstende.
     * - **Nacht davor** hat ZWEI Kodierungen, je nachdem, ob „Von“ vor „Bis“ liegt:
     *   - **Von >= Bis** (auch Von == Bis, wie `st >= en` im Prototyp): Start CLOCK, Ende CLOCK (oder
     *     `ALARM_SONST_CLOCK` mit „beim Wecker“), `startAmVorabend = true` (Resolver-Feld aus #227),
     *     `startNurAmWecktag = false`. Das Fenster des Tages D laeuft von Von an D-1 bis Bis an D - rein nach
     *     dem Kalender, unabhaengig von der Weckzeit und auch ohne Kalendereintrag (Betreuer-Entscheidung #215).
     *     Mit „beim Wecker“ endet es an der fruehesten Weckzeit dazwischen, spaetestens um Bis.
     *   - **Von < Bis** (Vorschlaf der Nachtdienst-Vorlage, z. B. 16:00 bis 19:30; E14, #151): Start
     *     `ALARM_SONST_CLOCK` mit `startNurAmWecktag = true`, Feld aus. Es beginnt am Tag SELBST und entfaellt bei
     *     einem Wecker davor, statt 21,5 h ab dem Vortag zu dimmen. Braucht einen Kalendereintrag mit Weckzeit -
     *     ohne gibt es kein „Aufstehen“.
     *
     *   ACHTUNG zur zweiten Kodierung, bewusst: `ALARM_SONST_CLOCK` ist laut `DimAnchor` „kein START-Anker“;
     *   die Aufloesung behandelt ihn am Start wie CLOCK, aber ueber den Schichtpfad (`loeseSchichtFenster`)
     *   statt ueber die Kalendernacht. Genau dieser Unterschied traegt hier. Wer die Aufloesung dort
     *   aendert, bricht „Nacht davor“ mit Von < Bis - die Messungen in `DimmFensterFormTest` zeigen es.
     *
     * Das Feld `startAmVorabend` gehoert der Form GANZ: jedes „Wann“ schreibt es ausdruecklich (nur „Nacht
     * davor“ mit Von >= Bis setzt es), sonst nahme ein Wechsel auf „Abend danach“ es aus der [basis] mit und
     * dimmte in der Nacht davor. `startNurAmWecktag` gehoert ihr nur bei „Nacht davor“. [deute] liest beides
     * als Gegenprobe: jede Kombination, die [kodiere] nicht schriebe, ist [NichtDarstellbarGrund.KENNUNG].
     */
    private fun kodiere(form: DimFensterForm, basis: DimWindow): DimWindow {
        val ende = if (form.beimWecker) DimAnchor.ALARM_SONST_CLOCK else DimAnchor.CLOCK
        return when (form.wann) {
            DimWann.ABEND_DANACH -> basis.copy(
                startAnchor = DimAnchor.CLOCK,
                startClockMinutes = form.vonMinuten!!,
                endAnchor = ende,
                endClockMinutes = form.bisMinuten,
                startAmVorabend = false
            )

            DimWann.NACHT_DAVOR ->
                if (vonNichtVorBis(form.vonMinuten!!, form.bisMinuten)) {
                    basis.copy(
                        startAnchor = DimAnchor.CLOCK,
                        startClockMinutes = form.vonMinuten,
                        endAnchor = ende,
                        endClockMinutes = form.bisMinuten,
                        startNurAmWecktag = false,
                        startAmVorabend = true
                    )
                } else {
                    basis.copy(
                        startAnchor = DimAnchor.ALARM_SONST_CLOCK,
                        startClockMinutes = form.vonMinuten,
                        endAnchor = ende,
                        endClockMinutes = form.bisMinuten,
                        startNurAmWecktag = true,
                        startAmVorabend = false
                    )
                }

            DimWann.NACH_DEM_DIENST -> basis.copy(
                startAnchor = DimAnchor.SHIFT_END,
                // Die Seite sagt „ab Dienstende“; ein Versatz aus [basis] (Vorgabe -120!) machte das falsch.
                startOffsetMinutes = 0,
                endAnchor = DimAnchor.CLOCK,
                endClockMinutes = form.bisMinuten,
                startAmVorabend = false
            )
        }
    }

    /** „Von“ liegt NICHT vor „Bis“ (auch gleich): das Fenster reicht ueber Mitternacht - wie `st >= en` im Prototyp und `istNachtDavor` im Resolver. */
    private fun vonNichtVorBis(vonMinuten: Int, bisMinuten: Int): Boolean = vonMinuten >= bisMinuten

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
