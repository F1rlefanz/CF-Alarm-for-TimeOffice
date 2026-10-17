package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

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
            "nachts gilt die Regel für freie Tage. Eigene Wecker bleiben."
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
            "diesen Tagen zur Rufbereitschafts-Uhrzeit (einstellbar unter „Nicht stören“)."

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

    // „Stummer Wecker“ (B3). Der Prototyp verspricht hier auch „Licht … richtet sich trotzdem nach
    // der Weckzeit – ein Lichtwecker“. Heute stimmt das NICHT: der AlarmReceiver und der
    // HueSmartScheduler ueberspringen bei einer stillen Schicht jede Hue-Regel. Der Satz kommt mit
    // Etappe 4 (E8), wenn Hue stillen Weckern folgt.
    const val STUMMER_WECKER_TITEL = "Stummer Wecker"
    const val STUMMER_WECKER_HINWEIS =
        "Kein Ton, keine Vibration, kein Vollbild. Dimmer und „Nicht stören“ richten sich " +
            "trotzdem nach der Weckzeit. Eine Hue-Lichtregel startet dann nicht."

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
