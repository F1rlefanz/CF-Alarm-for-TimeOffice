package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.istZeichenmuster

/**
 * Nutzertexte des Schicht-Editors und der Schicht-Karten - als Konstanten, damit ein Test
 * festhalten kann, dass jeder Text nur die Wirkungen nennt, die der Schalter WIRKLICH hat (beim
 * Rufbereitschaft-Schalter: stuendliche Kalender-Abfrage, DND-Cutoff), und nichts verspricht,
 * was es nicht gibt.
 */
object ShiftDefinitionTexte {
    // #142: Krankmeldung. "Eigene Wecker bleiben" ist tragend - die Freigabe nimmt manuelle Wecker
    // aus; ein Text ohne diesen Satz liesse fuerchten, dass auch sie entfallen.
    const val KRANKMELDUNG_HINWEIS =
        "Ein Termin dieses Typs gibt den ganzen Tag frei: kein Wecker aus dem Dienstplan, " +
            "nachts gilt, was unter „Ohne Kalendereintrag“ eingestellt ist. Eigene Wecker bleiben."
    const val KRANKMELDUNG_AKTIVIERT_HINWEIS =
        "Aus heißt: wird nicht erkannt – ein Termin dieses Typs gibt dann keinen Tag frei."
    const val KRANKMELDUNG_STATUS = "Krankmeldung – gibt den Tag frei"
    const val KRANKMELDUNG_AUSGESCHALTET = "Ausgeschaltet – wird nicht erkannt, gibt keinen Tag frei"

    /** Einbuchstabiges Muster bei einer Schicht: ein fremder Termin erzeugt einen Wecker zu viel. */
    const val EIN_BUCHSTABE_SCHICHT =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Kino mit F\") und weckt " +
            "dich dann an freien Tagen."

    /**
     * #142: dasselbe bei einer Krankmeldung - hier fehlt der Wecker. "K" ist der TimeOffice-Code
     * und damit der Hauptfall; ein fremder Termin mit "K" als eigenem Wort gibt einen DIENSTTAG
     * frei. Die Schicht-Warnung ("weckt dich an freien Tagen") nennte das Gegenteil des Risikos.
     */
    const val EIN_BUCHSTABE_KRANK =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Termin bei Dr. K\") und " +
            "gibt dann einen Diensttag frei – kein Wecker aus dem Dienstplan."

    /**
     * PURE, TESTBAR: die Warnung unter einem einbuchstabigen Muster - oder null. Ein einzelnes
     * ZEICHEN ("+") bekommt keine: es trifft seit #150 nur den ganzen Titel, nie fremde Termine.
     */
    fun einBuchstabeWarnung(muster: String, isSickDay: Boolean): String? {
        val m = muster.trim()
        if (m.length != 1 || istZeichenmuster(m)) return null
        return if (isSickDay) EIN_BUCHSTABE_KRANK else EIN_BUCHSTABE_SCHICHT
    }

    /**
     * Dieselbe Warnung, wo KEIN Wecker gestellt wird (Ignorieren, Wecker aus): "weckt dich an freien
     * Tagen" waere dort falsch (Pruefer #174). Gesagt wird nur, was bleibt: fremde Termine landen
     * beim Typ.
     */
    const val EIN_BUCHSTABE_OHNE_WECKER =
        "Ein einzelner Buchstabe trifft auch fremde Termine (z. B. \"Kino mit F\") und ordnet " +
            "sie diesem Typ zu."

    /**
     * Wie [einBuchstabeWarnung], aber nach Kategorie und Wecker-Schalter: Krank behaelt seinen Text,
     * Ignorieren und "Wecker aus" bekommen [EIN_BUCHSTABE_OHNE_WECKER], nur ein Typ, der wirklich
     * einen Wecker stellt, die Schicht-Warnung.
     */
    fun einBuchstabeWarnung(muster: String, kategorie: ShiftKategorie, weckerAn: Boolean): String? {
        val warnung = einBuchstabeWarnung(muster, kategorie == ShiftKategorie.KRANK) ?: return null
        val stelltWecker = ShiftEditFormular.weckzeitPflicht(kategorie, weckerAn)
        return if (kategorie == ShiftKategorie.KRANK || stelltWecker) warnung else EIN_BUCHSTABE_OHNE_WECKER
    }

    /** #142: Loesch-Rueckfrage fuer einen Krankmeldungs-Typ - er hat keine Weckzeit, die entfiele,
     * wohl aber Tage, die er freigegeben hat und die ihre Wecker zurueckbekommen. */
    fun krankmeldungLoeschenText(name: String): String =
        "\"$name\" wird mit allen Mustern gelöscht. Tage, die ein solcher Termin freigegeben " +
            "hat, bekommen ihre Wecker aus dem Dienstplan zurück. Das lässt sich nicht " +
            "rückgängig machen."

    const val RUFBEREITSCHAFT_HINWEIS =
        "An Tagen mit dieser Schicht kannst du kurzfristig zu einem Dienst abgerufen werden. " +
            "CF-Alarm fragt den Kalender dann stündlich ab statt alle 6 Stunden, damit ein " +
            "nachgetragener Dienst noch rechtzeitig einen Wecker bekommt – Google Kalender und " +
            "TimeOffice melden Änderungen nicht von selbst. Außerdem endet „Nicht stören“ an " +
            "diesen Tagen spätestens zu der Uhrzeit, die bei „Nicht stören“ unter " +
            "„Endet spätestens um“ steht."

    // Umbau E1: die Kurzbeschreibungen der Kategorien (Prototyp, `KAT` in Main.dc.html). Ohne `else`:
    // eine achte Kategorie muss hier bewusst beschrieben werden. Der Name der Kategorie kommt aus
    // `ShiftKategorie.titel()`. Abweichung vom Prototyp: bei Urlaub fehlt „und Dimmer“ - der Dimmer
    // haengt bis Etappe 3 an Regeln, nicht am Typ, und „Wecker aus“ nimmt ihn nicht weg.
    fun kategorieBeschreibung(kategorie: ShiftKategorie): String = when (kategorie) {
        ShiftKategorie.DIENST -> "Arbeitstage nach Dienstplan."
        ShiftKategorie.RUFBEREITSCHAFT ->
            "Bereitschaftsdienst. Wird ein Einsatz als Dienst nachgetragen, gilt dessen Wecker."
        ShiftKategorie.FREI -> "Dienstfreie Tage, z. B. „+“."
        ShiftKategorie.URLAUB -> "Urlaubstage – auf Wunsch ganz ohne Wecker."
        ShiftKategorie.FORTBILDUNG -> "Intern oder extern, Kongress, Uni."
        ShiftKategorie.KRANK -> "Hebt einen Dienst am selben Tag auf."
        ShiftKategorie.IGNORIEREN -> "Steht im Kalender, ist für die App bedeutungslos."
    }

    // Umbau E2: der Schalter heisst im Prototyp (BILDSCHIRME.md B3) „Wecker an diesem Tag“. Die
    // Hinweise sagen nur, was der Typ TUT: einen Wecker stellen oder nicht. Ob er klingelt, regelt
    // „Stummer Wecker“ darunter.
    const val WECKER_ABSCHNITT_TITEL = "Wecker"
    const val WECKER_AN_TITEL = "Wecker an diesem Tag"
    const val WECKER_AN_HINWEIS = "Stellt an jedem Tag dieses Typs einen Wecker."
    const val WECKER_AUS_HINWEIS = "Aus – an diesen Tagen stellt dieser Typ keinen Wecker."
    const val WECKZEIT_TITEL = "Weckzeit"

    // Typ-Seite (#189): die Zeile „Weckzeit“ hat ein schmales Feld rechts; was der fruehere Dialog als Beschriftung
    // und Format-Hinweis neben das Feld stellt, steht hier unter dem Titel bzw. als Fehlertext.
    const val WECKZEIT_HINWEIS = "Als Uhrzeit eingeben, z. B. 06:30."
    const val WECKZEIT_FEHLER = "Das ist keine Uhrzeit – bitte als HH:mm eingeben, z. B. 06:30."

    // „Stummer Wecker“ (B3, SPEC 3.3 Punkt 5). Seit #251 folgt Hue auch dem stillen Wecker
    // (AlarmReceiver.fuehreWeckvorgangAus, Vorab-Sonnenaufgang im HueSmartScheduler). „keine
    // Vibration“ steht ueber den Prototyp hinaus, weil es stimmt: die Vibration gehoert dem
    // AlarmSoundService, und der startet beim stillen Wecker nicht.
    const val STUMMER_WECKER_TITEL = "Stummer Wecker"
    const val STUMMER_WECKER_HINWEIS =
        "Kein Ton, keine Vibration, kein Vollbild. Licht, Dimmer und „Nicht stören“ richten sich " +
            "trotzdem nach der Weckzeit – ein Lichtwecker."

    /** Ein Ignorieren-Typ ergibt keine Schicht (#167); ein Dienst im selben Termin gewinnt. */
    const val IGNORIEREN_STATT_WECKER =
        "Dieser Eintrag wird übergangen. Er stellt keinen Wecker; steht im selben Termin ein " +
            "Dienst-Code, gilt der Dienst."

    // Umbau #189: die Sonderfall-Kaesten der Typ-Seite (SPEC E3; Prototyp `besonders` und `banner` in
    // `zeigeTyp`). TEXTWAHRHEIT statt Prototyp-Wortlaut, gegen den Code geprueft:
    // - Rufbereitschaft: uebernimmt ein nachgetragener Dienst den Tag, entfaellt nur der WECKER des Rufs
    //   (`uebernommeneRufTermine`, #172). Seine Spanne bleibt, und aus ihr lesen die stuendliche
    //   Ruf-Abfrage und das Ende von „Nicht stoeren“. Der Prototyp sagt „dessen Wecker und Einstellungen -
    //   nicht die von hier“; Dimmer und „Nicht stoeren“ des Ruf-Typs wirken heute weiter.
    // - Krank: „der Dienst-Wecker entfaellt“ stimmt; „es gilt das hier Eingestellte“ nicht, Krank hat
    //   bis Etappe 6 keinen Wecker-Block.
    // - Ignorieren: ein ignorierter Termin zaehlt an einem sonst leeren Tag nicht als Eintrag
    //   (`tageOhneEintrag`, #173), und die App schlaegt seinen Code nicht vor (#167).
    const val SONDERFALL_RUF_TITEL = "Sonderfall Rufbereitschaft"
    const val SONDERFALL_RUF_PRUEFUNG =
        "Die App prüft an diesen Tagen stündlich den Kalender, weil Google Kalender und TimeOffice " +
            "einen nachgetragenen Dienst nicht von selbst melden."
    const val SONDERFALL_RUF_EINSATZ =
        "Wird ein Einsatz als Dienst nachgetragen, gilt für den Tag die Wecker-Einstellung dieses " +
            "Dienstes – nicht die von hier. Die stündliche Prüfung und das Ende von „Nicht stören“ bleiben."
    const val SONDERFALL_RUF_ALLEIN =
        "Steht nur die Rufbereitschaft im Kalender, gilt die Wecker-Einstellung von hier."

    const val SONDERFALL_KRANK_TITEL = "Sonderfall Krank"
    const val SONDERFALL_KRANK =
        "Steht am selben Tag auch ein Dienst im Kalender, gewinnt Krank: der Dienst-Wecker entfällt."

    const val IGNORIEREN_OHNE_EINTRAG = "Steht an dem Tag sonst nichts im Kalender, gilt „Ohne Kalendereintrag“."
    fun ignorierenVorschlag(code: String): String = "Die App schlägt $code nicht mehr vor."

    /** Ein Sonderfall-Kasten: [titel] fett (`null` = ohne Titel), darunter die [absaetze]. */
    data class SonderfallText(val titel: String?, val absaetze: List<String>)

    /**
     * Der Kasten zu [kategorie] - oder `null`, wenn es keinen Sonderfall gibt. [code] ist das erste Muster
     * des Typs (`null` = keins). [erkannt] ist der Schalter „Schichtdefinition aktiviert“: ein
     * ausgeschalteter Typ wird nicht erkannt, dann gilt keiner dieser Saetze, und der Schalter darueber
     * sagt es schon („Aus heißt: wird nicht mehr übergangen“). Ohne `else`: eine achte Kategorie muss
     * bewusst entscheiden, ob sie einen Sonderfall hat.
     */
    fun sonderfall(kategorie: ShiftKategorie, code: String?, erkannt: Boolean): SonderfallText? {
        if (!erkannt) return null
        return when (kategorie) {
            ShiftKategorie.RUFBEREITSCHAFT -> SonderfallText(
                SONDERFALL_RUF_TITEL,
                listOf(SONDERFALL_RUF_PRUEFUNG, SONDERFALL_RUF_EINSATZ, SONDERFALL_RUF_ALLEIN)
            )
            ShiftKategorie.KRANK -> SonderfallText(
                SONDERFALL_KRANK_TITEL,
                listOf(SONDERFALL_KRANK, KRANKMELDUNG_HINWEIS)
            )
            // Ein Absatz wie im Prototyp (`banner`): drei Saetze, die zusammengehoeren.
            ShiftKategorie.IGNORIEREN -> SonderfallText(
                titel = null,
                absaetze = listOf(
                    listOfNotNull(
                        IGNORIEREN_STATT_WECKER,
                        IGNORIEREN_OHNE_EINTRAG,
                        code?.takeIf { it.isNotBlank() }?.let(::ignorierenVorschlag)
                    ).joinToString(" ")
                )
            )
            ShiftKategorie.DIENST, ShiftKategorie.FREI, ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG -> null
        }
    }

    /** Wie `SchichttypTexte.IGNORIEREN_AUSGESCHALTET` der Typ-Liste: aus heisst „wird nicht mehr uebergangen“. */
    const val IGNORIEREN_AKTIVIERT_HINWEIS =
        "Aus heißt: wird nicht mehr übergangen – ein Termin mit diesem Muster zählt dann wie jeder andere Termin."

    // Der Schalter „Schichtdefinition aktiviert“ verwies auf „Stille Schicht“ - die heisst jetzt
    // „Stummer Wecker“, und fuer „kein Wecker, Zeitfenster trotzdem“ ist seit #168 „Wecker an diesem
    // Tag“ aus der richtige Schalter (der Typ wird erkannt, die Spanne bleibt).
    const val AKTIVIERT_HINWEIS =
        "Aus heißt: wird gar nicht erkannt — kein Wecker, aber auch kein Dimmer- und kein " +
            "DND-Fenster. Für „kein Wecker, Zeitfenster trotzdem“ schaltest du „$WECKER_AN_TITEL“ aus."

    /**
     * Der Hinweis unter „Schichtdefinition aktiviert“, je nach Kategorie.
     * #142: der Wecker-aus-Verweis passt nicht auf eine Krankmeldung - dort kostet „aus“ die
     * Tagesfreigabe, nicht ein Zeitfenster. Ignorieren ergibt nie einen Wecker, und der Schalter
     * „Wecker an diesem Tag“ ist dort ausgeblendet: der allgemeine Hinweis verwiese ins Leere.
     */
    fun aktiviertHinweis(kategorie: ShiftKategorie): String = when (kategorie) {
        ShiftKategorie.KRANK -> KRANKMELDUNG_AKTIVIERT_HINWEIS
        ShiftKategorie.IGNORIEREN -> IGNORIEREN_AKTIVIERT_HINWEIS
        else -> AKTIVIERT_HINWEIS
    }

    /**
     * Wie ein Muster im Kalender trifft. [namensfeld] ist die Beschriftung
     * des Namensfelds an der jeweiligen Stelle: „Name“ in der Karte der
     * Typ-Seite - ein Hinweis nennt die Beschriftung wortgleich.
     */
    fun musterHinweis(namensfeld: String): String =
        "Ein Muster trifft, wenn es im Titel des Kalendertermins als eigenes Wort vorkommt " +
            "(\"FD\" trifft \"FD Station 3\", nicht \"FD2\"). Der $namensfeld oben zählt ab zwei " +
            "Zeichen ebenfalls als Muster."

    /**
     * Beim Umbenennen eines BESTEHENDEN Typs. Keine unbedingte Zusage: `planeSchichtUmbenennungen()`
     * kann den Nachzug blockieren, das Ergebnis meldet `regelNachzugHinweis`.
     */
    fun umbenennungHinweis(alterName: String): String =
        "Dimmer- und Hue-Regeln, die auf \"$alterName\" zeigen, werden beim Speichern nach " +
            "Möglichkeit auf den neuen Namen umgestellt. Klappt das nicht – etwa weil der neue " +
            "Name schon vergeben ist –, sagt die App es dir; dann wählst du die Schicht in der " +
            "betroffenen Regel neu aus."
}

/**
 * Nutzertexte der Karte „Bildschirm dimmen“ (#221, Prototyp `zeigeTyp`). „Wann“ samt Erklaerung und „Bis“-Hinweis
 * stehen an [DimWann]. „Dimmen an diesem Tag“ und seine Hinweise stehen nicht im Prototyp (Festlegung in #221).
 */
internal object DimmenTexte {
    const val TITEL = "Bildschirm dimmen"
    const val AN_TITEL = "Dimmen an diesem Tag"
    const val AN_HINWEIS = "Die App dimmt nur in den Fenstern dieses Typs."
    const val AUS_HINWEIS = "Aus – kein Fenster dieses Typs dimmt."
    const val KEIN_FENSTER = "An diesen Tagen wird nicht gedimmt."
    const val WANN = "Wann"
    const val VON = "Von"
    const val BIS = "Bis"
    const val MIT = "Mit %"

    /** Die Grenze steht da, weil das Feld eine groessere Zahl auf sie zuruecksetzt. */
    val MIT_HINWEIS = "0 bis ${DimOverlayPrefs.STRENGTH_MAX}"
    const val DIENSTENDE = "Dienstende"
    const val BEIM_WECKER = "Schon beim Wecker enden"
    const val BLOCKTAGE = "Gilt an diesen Tagen eines Blocks"
    const val FENSTER_DAZU = "+ Weiteres Fenster"

    /** Statt „Vom Vorabend …“, wenn „Nacht davor“ mit „Von“ vor „Bis“ am Tag selbst liegt (E14). */
    const val NACHT_DAVOR_AM_TAG_SELBST =
        "Am Tag selbst, weil „$VON“ vor „$BIS“ liegt. Liegt die Weckzeit vor „$VON“, entfällt das Fenster."
    const val NICHT_OHNE_EINTRAG = "An Tagen ohne Kalendereintrag gibt es dieses Fenster nicht."

    fun entfernen(nummer: Int): String = "Fenster $nummer entfernen"

    /** Unter einem Fenster ohne „Wann“ - nur, wo die Zeile „Weitere Optionen“ darunter steht. */
    const val EIGENE_ZEITEN_HINWEIS = "Start und Ende stellst du unter „${WeitereOptionenTexte.TITEL}“ ein."

    /**
     * „bis zu“: die Uhrzeit-Grenze und „bis es hell ist“ kuerzen den Nachlauf. Ein Typ ohne Wecker ([weckerAn]
     * `false`) braucht dafuer einen anderen Wecker im Fenster - dasselbe sagt das Blatt.
     */
    fun nachlauf(minuten: Int, weckerAn: Boolean): String {
        val dauer = "bis zu $minuten ${if (minuten == 1) "Minute" else "Minuten"}"
        return if (weckerAn) {
            "Nach dem Wecker dimmt es noch $dauer weiter."
        } else {
            "Klingelt in diesem Fenster ein anderer Wecker, dimmt es danach noch $dauer weiter."
        }
    }

    /** Der Chip eines Blocktags; `null` = alle. */
    fun blockTag(position: Blockposition?): String = when (position) {
        null -> "Alle"
        Blockposition.ERSTER -> "Erster"
        Blockposition.MITTLERER -> "Mittlere"
        Blockposition.LETZTER -> "Letzter"
        Blockposition.EINZELNER -> "Einzeln"
    }
}

/**
 * Nutzertexte des Blatts „Weitere Optionen“ der Karte „Bildschirm dimmen“ (#224). Aus dem Prototyp (`zeigeDimmen`)
 * stehen nur Titel, Unterzeile und „Nachlauf bis es hell ist“; die Texte des Anker-Editors und der Beispielzeile
 * teilt sich das Blatt mit dem Regel-Editor (`R.string.dimmer_*`, `R.string.tageslicht_*`).
 */
internal object WeitereOptionenTexte {
    const val TITEL = "Weitere Optionen"
    const val UNTERZEILE = "Nachlauf bis es hell ist, Dimmen ein paar Minuten nach dem Wecker"

    /** Das Blatt speichert nicht selbst; [knopf] ist der Knopf der Typ-Seite, der es tut. */
    fun speicherHinweis(knopf: String): String = "Gespeichert wird erst mit „$knopf“ auf der Typ-Seite."

    const val WAERME_TITEL = "Farbwärme"
    const val WAERME_WIE_EINSTELLUNGEN = "Wie in den Einstellungen"
    const val WAERME_EIGENE = "Eigener Wert"
    fun prozent(wert: Int): String = "$wert %"
    fun waermeZurzeit(wert: Int): String = "Zurzeit ${prozent(wert)}"

    const val NACH_DEM_WECKER = "Dimmen nach dem Wecker"

    /** Die Grenze steht da, weil das Feld eine groessere Zahl auf sie zuruecksetzt. */
    val NACH_DEM_WECKER_HINWEIS =
        "Minuten, 0 bis ${DimWindowResolver.NACHLAUF_MAX_MINUTEN} – höchstens bis zur Uhrzeit, zu der das Fenster " +
            "spätestens endet. „${NichtStoerenTexte.TITEL}“ endet trotzdem an der Weckzeit."

    const val NACHLAUF_HELL_TITEL = "Nachlauf bis es hell ist"

    /**
     * Ohne Ort: der Satz des Regel-Editors ([ohneOrtSatz]) und der Weg zum Ort. [kartenTitel] ist die Ueberschrift
     * der Karte in den Einstellungen (dieselbe String-Ressource, nie ein Abschreiben).
     */
    fun ohneOrt(ohneOrtSatz: String, kartenTitel: String): String =
        "$ohneOrtSatz Den Ort stellst du unter „Einstellungen“ → „$kartenTitel“ ein."

    const val BEIM_WECKER_AUS =
        "„$NACH_DEM_WECKER“ gibt es, wenn das Fenster schon beim Wecker endet. Schalte dafür auf der Typ-Seite " +
            "„${DimmenTexte.BEIM_WECKER}“ ein."
    const val KEIN_NACHLAUF = "Dieses Fenster endet nicht am Wecker – hier gibt es kein „$NACH_DEM_WECKER“."
    const val OHNE_EIGENEN_WECKER =
        "Dieser Typ stellt keinen Wecker. Nach dem Wecker dimmt dieses Fenster nur, wenn währenddessen ein anderer " +
            "Wecker klingelt."

    /** Ueber dem Anker-Editor eines Fensters, das nicht in „Wann“ passt. */
    const val OHNE_WANN = "Dieses Fenster passt nicht in „${DimmenTexte.WANN}“. Start und Ende stellst du hier ein."

    const val KEIN_FENSTER =
        "Dieser Typ hat kein Fenster. „$NACH_DEM_WECKER“ gibt es je Fenster – lege auf der Typ-Seite mit " +
            "„${DimmenTexte.FENSTER_DAZU}“ eines an."
}

/**
 * Nutzertexte der Karte „Nicht stören“ (#222, SPEC E7, Prototyp `zeigeTyp`). Titel und Untertitel stehen wie
 * im Prototyp (Festlegung in #222). Die Texte zum fehlenden Zugriff teilt sich die Karte mit der DND-Seite
 * (`R.string.dnd_*`); die zwei Saetze, die sagen, dass hier etwas nicht wirkt, stehen nicht im Prototyp.
 */
internal object NichtStoerenTexte {
    const val TITEL = "Nicht stören"
    const val GEDIMMT_TITEL = "Solange gedimmt wird"
    const val GEDIMMT_HINWEIS = "In jedem Dimm-Fenster oben – endet mit dem Wecker"
    const val EINTRAG_TITEL = "Während des Kalendereintrags"
    const val CUTOFF_TITEL = "Endet spätestens um"
    const val CUTOFF_HINWEIS = "Damit du für einen Einsatz erreichbar bist."

    /** Unter den Texten zum fehlenden Zugriff: ein Schalter ohne Wirkung steht nicht still an. */
    const val OHNE_ZUGRIFF = "Bis dahin wirkt nichts auf dieser Karte."

    /** Unter „Solange gedimmt wird“, wenn der Schalter an ist, der Typ aber nie dimmt. */
    const val GEDIMMT_OHNE_FENSTER =
        "Dieser Typ hat kein eingeschaltetes Dimm-Fenster – so bewirkt „$GEDIMMT_TITEL“ nichts."

    /**
     * Der Text unter „Während des Kalendereintrags“ je Kategorie (Prototyp `dndEintragText`); `null` = die
     * feste Zeile „Ohne Kalendereintrag“. Ohne `else`: eine achte Kategorie muss hier bewusst eingeordnet werden.
     */
    fun eintragText(kategorie: ShiftKategorie?): String = when (kategorie) {
        null -> "Gibt es hier nicht – an diesen Tagen steht nichts im Kalender."
        ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.IGNORIEREN ->
            "Von Beginn bis Ende laut Kalender – z. B. während des Nachtdienstes."
        ShiftKategorie.FREI, ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG, ShiftKategorie.KRANK ->
            "Bei ganztägigen Einträgen: den ganzen Tag."
    }
}

/**
 * Nutzertexte der Karte „Licht (Hue)“ (#254, SPEC E8 und 3.3 Punkt 8, Prototyp `zeigeTyp`). Titel, „+“ und der
 * Hinweis ohne Wecker stehen wie im Prototyp; der Rest beschreibt Zustaende, die es dort nicht gibt.
 */
internal object LichtTexte {
    const val TITEL = "Licht (Hue)"
    const val HINZU = "+ Lichtregel hinzufügen"
    const val OHNE_WECKER =
        "Licht startet mit dem Wecker. Ohne Weckzeit gibt es an diesem Tag kein Licht – schalte oben den " +
            "Wecker ein, gern stumm."

    const val OHNE_BRIDGE =
        "Es ist noch keine Hue-Bridge eingerichtet. Erst mit einer Bridge kannst du Lichtregeln anlegen."
    const val ZU_HUE = "Zum Bereich „Philips Hue“"
    const val BRIDGE_NICHT_LESBAR =
        "Gerade nicht lesbar, ob eine Hue-Bridge eingerichtet ist. Bis dahin lässt sich hier keine Regel anlegen."

    /** Eine Neuanlage steht noch nicht in der Konfiguration - der Editor kehrte ohne Speichern zurueck. */
    fun neuanlage(knopf: String): String =
        "Lichtregeln kannst du hinzufügen, sobald der Typ angelegt ist („$knopf“)."

    /** Ein frueherer Typ traegt denselben Namen: geschrieben und ausgefuehrt wird nur am ersten (#261). */
    fun verdeckt(name: String): String =
        "Ein früherer Typ heißt genauso („$name“). Lichtregeln gelten nur für den ersten Typ dieses Namens – " +
            "gib diesem Typ einen eigenen Namen."

    /** Derselbe Wortlaut wie die Ablehnung beim Speichern (`TypLichtSchreiben`). */
    fun hoechstzahl(anzahl: Int): String = "Für einen Schichttyp sind höchstens $anzahl Lichtregeln möglich."

    // Unterzeile einer Regel: Ziele und Modus.
    const val KEIN_ZIEL = "kein Ziel"
    const val EINSCHALTEN = "Einschalten"
    const val AUSSCHALTEN = "Ausschalten"
    const val SZENE_OHNE_NAMEN = "Szene"
    fun licht(id: String): String = "Licht $id"
    fun gruppe(id: String): String = "Gruppe $id"
    fun sonnenaufgang(minuten: Int): String = "Sonnenaufgang $minuten min"
    fun szenen(namen: List<String>): String = when {
        namen.isEmpty() -> SZENE_OHNE_NAMEN
        namen.size == 1 -> "Szene «${namen.single()}»"
        else -> "Szenen ${aufzaehlung(namen.map { "«$it»" })}"
    }

    /** „A“, „A und B“, „A, B und C“. */
    fun aufzaehlung(teile: List<String>): String = when {
        teile.size <= 1 -> teile.joinToString("")
        else -> teile.dropLast(1).joinToString(", ") + " und " + teile.last()
    }

    /** Ziele, die auf der verbundenen Bridge ins Leere zeigen; [antippbar] = die Zeile laesst sich oeffnen. */
    fun unbekannteZiele(namen: List<String>, antippbar: Boolean): String =
        "Auf dieser Bridge unbekannt: ${namen.joinToString()}. Die Regel schaltet dafür nichts" +
            if (antippbar) " – tippe auf die Regel und wähle neu." else "."

    const val ENTFERNEN_TITEL = "Lichtregel entfernen?"
    const val ENTFERNEN = "Entfernen"
    const val ABBRECHEN = "Abbrechen"
    const val FEHLER_SCHLIESSEN = "Schließen"

    /** Die Regel gehoert nicht zum Entwurf der Seite: „Verwerfen“ holt sie nicht zurueck. */
    fun entfernenText(name: String): String =
        "„$name“ wird mit allen Einstellungen gelöscht. Das gilt sofort, auch ohne „Speichern“, und lässt " +
            "sich nicht rückgängig machen."

    fun entfernenBeschreibung(name: String): String = "Lichtregel „$name“ entfernen"
    /** Ohne Zustandswort: „an“ oder „aus“ sagt der Schalter selbst (TalkBack). */
    fun schalterBeschreibung(name: String): String = "Lichtregel „$name“"
}
