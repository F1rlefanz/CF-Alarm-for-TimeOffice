package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreigabeQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Wecker-Tagesliste (Issue #139, Spec P3) als reine Daten - ohne Compose, ohne ViewModel,
 * damit jede Zuordnung als JVM-Test pruefbar ist.
 *
 * DER EINE ANKER: Ein Wecker gehoert zu dem Kalendertag, auf den seine WECKZEIT (`triggerTime`)
 * faellt - nicht der Schichtbeginn. Derselbe Anker wie `FreieTageStore.tagVon`,
 * `TagFreigabeUseCase.gehoertZuTag`, `AlarmUseCase.istTagFreigegeben` und `berechneWeckerAnzeige`.
 * Wer hier den Schichtbeginn nimmt, zeigt eine Nachtschicht, die um 23:30 weckt und um 00:15
 * beginnt, an einem anderen Tag, als „Dienst fällt aus“ sie freigibt.
 *
 * ZWEI QUELLEN JE TAG (Fairphone-Rueckmeldung 14.10.2026): die Wecker aus dem Bestand UND die
 * Dienste aus dem `ShiftSpanStore`. Ein Wecker ist ein Weckzeitpunkt, eine Spanne ist ein Dienst -
 * und der Bestand verliert seinen Wecker in genau den Lagen, in denen der Tag gebraucht wird: nach
 * „Nicht wecken“ (geloescht), nach dem Klingeln (verstrichen) und nach „Dienst fällt aus“
 * (geloescht). Die Spanne bleibt in allen dreien. Ihr Tagesanker ist derselbe
 * ([FreieTageStore.tagVon], die Weckzeit).
 */
/**
 * #142: woher die Freigabe eines Tages kommt - nur fuer die ANZEIGE (Etikett, Text, ob
 * „Zurücknehmen“ etwas bewirkt). Die Wirkung ist fuer alle drei dieselbe: Gate und Backstop lesen
 * die Vereinigung beider Quellen.
 */
enum class FreigabeArt {
    /** Nur „Dienst fällt aus“ des Nutzers. */
    HAND,
    /** Nur ein Krank-Termin im Kalender - „Zurücknehmen“ kann daran nichts aendern. */
    KALENDER,
    /** Beides: „Zurücknehmen“ nimmt nur die eigene Freigabe, der Tag bleibt laut Kalender frei. */
    BEIDE
}

data class WeckerTag(
    val datum: LocalDate,
    /** #142: Quelle der Freigabe, `null` = Diensttag. */
    val freigabe: FreigabeArt?,
    /** Nach Weckzeit sortiert; bei einem freigegebenen Tag ohne eigene Wecker leer. */
    val eintraege: List<WeckerEintrag>,
    /**
     * Die Dienste des Tages in Reihenfolge ihrer Weckzeit, ohne Doppelungen - aus Weckern UND
     * Schichtspannen. Auch an einem freigegebenen Tag: dort nennt sie den Dienst, der AUSFAELLT.
     */
    val schichten: List<TagesSchicht>,
    /** Was die Dienste dieses Tages ausserhalb des Weckers ausloesen - siehe [kaertchenFuer]. */
    val kaertchen: List<Kaertchen>
) {
    /** Frei, gleich aus welcher Quelle - fuer alle Leser, die nur das wissen muessen. */
    val freigegeben: Boolean get() = freigabe != null
}

/**
 * Eine Schicht des Tages mit Dienstzeit als Epoch-Millis; 0 = unbekannt (wie in `AlarmInfo`).
 * Millis statt Uhrzeit: „ganztägig“ und „bis So 18.10.“ brauchen das DATUM des Endes.
 */
data class TagesSchicht(
    val name: String,
    val beginn: Long,
    val ende: Long
)

sealed interface WeckerEintrag {
    val triggerTime: Long

    /** Ein gestellter Wecker aus dem Bestand. */
    data class Wecker(
        val alarm: AlarmInfo,
        val uhrzeit: LocalTime,
        /** Stille Schicht: der Eintrag ist nur Zeitanker, er klingelt nicht („kein Ton“). */
        val still: Boolean,
        /** Von Hand gestellt (`AlarmSkipUseCase.isManualAlarm`) - „von dir gestellt“. */
        val manuell: Boolean,
        /** DER naechste klingelnde Wecker, dieselbe Auswahl wie das Ziel von `skipNextAlarm`. */
        val naechster: Boolean,
        /**
         * Darf diese Zeile „Nicht wecken“ anbieten? Nur [naechster], und nur solange KEIN Skip
         * WIRKT ([SkipLage.wirktNoch]) - es gibt genau einen Skip-Platz, und `skipNextAlarm`
         * traefe ohnehin immer den naechsten, nie die angetippte Zeile (Variante A).
         */
        val nichtWeckenMoeglich: Boolean,
        /**
         * Ein Dienstplan-Wecker an einem freigegebenen Tag. Gate und Backstop verhindern sein
         * Klingeln; er steht hier nur, bis der naechste Sync ihn raeumt. Die Anzeige darf ihn
         * nicht als „Weckt“ ausgeben.
         */
        val entfaelltDurchFreigabe: Boolean
    ) : WeckerEintrag {
        override val triggerTime: Long get() = alarm.triggerTime
    }

    /**
     * Der uebersprungene Wecker: „weckt diesmal nicht“ mit „Doch wecken“. Er ist im Bestand
     * GELOESCHT (`skipNextAlarm`), deshalb kommt sein Tag allein aus dem gemerkten Weckzeitpunkt -
     * auch wenn sonst kein Wecker an diesem Tag steht.
     */
    data class Uebersprungen(
        override val triggerTime: Long,
        val uhrzeit: LocalTime
    ) : WeckerEintrag

    /**
     * Ein geschlummerter Wecker: klingelt um [uhrzeit] wieder. Der Ursprungswecker hat schon
     * geklingelt und ist aus dem Bestand verschwunden; ohne diese Zeile stand der Tag ohne jeden
     * Wecker da, waehrend gleich einer klingelt (Emulator, 14.10.2026). Quelle ist der
     * Snooze-Merker ([Schlummern]).
     */
    data class Schlummert(
        override val triggerTime: Long,
        val uhrzeit: LocalTime,
        val manuell: Boolean
    ) : WeckerEintrag
}

/**
 * Ein schwebender Snooze, wie die Anzeige ihn braucht - aus dem Snooze-Merker
 * ([schwebendesSchlummern]). [weckzeit] ist die urspruengliche Weckzeit (null = unbekannt).
 */
data class Schlummern(
    val bis: Long,
    val weckzeit: Long?,
    val schichtName: String,
    val manuell: Boolean
)

/**
 * Die schwebenden Snoozes aus dem Merker. Liest Device-Protected SharedPreferences synchron; wirft
 * nie (leer bei Fehler - dann fehlt nur die Anzeige). Manuell erkennt der Merker am Anhaengsel,
 * dieselbe Regel wie `AlarmManagerService.gehoertSnoozeZuFreiemTag`.
 */
fun schwebendesSchlummern(context: Context, jetzt: Long): List<Schlummern> =
    AlarmManagerService.schwebendeSnoozes(context, jetzt).map {
        Schlummern(
            bis = it.triggerTime,
            weckzeit = it.weckzeit,
            schichtName = it.shiftName,
            manuell = it.shiftName.endsWith(MANUELLER_ALARM_SUFFIX)
        )
    }

/** Eine Wirkung eines Dienstes ausserhalb des Weckers - ohne Uhrzeit (Details im Bottom-Sheet). */
enum class Kaertchen { NICHT_STOEREN, DIMMEN, LICHT, RUFBEREITSCHAFT }

/**
 * Was die Kaertchen-Zuordnung je Schicht braucht: die [SchichtFolgen] aus derselben Funktion wie
 * die Statuszeile der Schichttypen (`ermittleSchichtFolgen`) und die [ShiftDefinition] fuer
 * `isOnCall`.
 */
data class SchichtWirkung(
    val definition: ShiftDefinition,
    val folgen: SchichtFolgen
)

/**
 * Der Skip-Zustand, wie die Liste ihn braucht.
 *
 * @param weckzeitpunkt `AlarmSkipState.skippedAlarmTriggerTime`. Ohne ihn hat „Doch wecken“
 *   keinen Tag, sobald der uebersprungene der EINZIGE Wecker war (Falle aus v1.26.2) - dann
 *   erscheint KEIN Eintrag; das zu beheben ist Sache des ViewModels, nicht dieser Funktion.
 */
data class SkipLage(
    val aktiv: Boolean,
    val alarmId: Int?,
    val weckzeitpunkt: Long?
) {
    /**
     * Belegt der Skip noch den einen Skip-Platz? Ja, solange sein Weckzeitpunkt aussteht - und
     * auch dann, wenn der Zeitpunkt UNBEKANNT ist (sonst ueberschriebe ein zweites „Nicht wecken“
     * still den ersten Merker).
     *
     * Ein ABGELAUFENER Merker belegt ihn nicht mehr: `clearExpiredSkip()` raeumt ihn erst beim
     * naechsten Sync (offline oder bei Kalenderfehler bis zu 6 h spaeter), der Systemalarm des
     * uebersprungenen Weckers feuert nie. Galt bis dahin weiter `aktiv`, zeigte die Liste weder
     * „Doch wecken“ (die Zeile entfaellt mit der Weckzeit) noch irgendwo „Nicht wecken“ - eine
     * Sackgasse. `skipNextAlarm` ueberschreibt den alten Merker ohnehin.
     */
    fun wirktNoch(jetzt: Long): Boolean = aktiv && (weckzeitpunkt == null || weckzeitpunkt > jetzt)

    companion object {
        val KEIN = SkipLage(aktiv = false, alarmId = null, weckzeitpunkt = null)
    }
}

/**
 * Baut die Tagesliste. Rein: dieselbe Eingabe ergibt dieselbe Liste.
 *
 * - Verstrichene Wecker (`triggerTime <= jetzt`) fallen raus - dieselbe Grenze wie
 *   [AlarmSkipUseCase.naechsterKlingelnderWecker].
 * - Freigegebene Tage VOR heute fallen raus; heute bleibt (eine Freigabe fuer heute wirkt noch).
 *   Der Store behaelt gestern bewusst (`FreieTageStore.RUECKSCHAU_TAGE`, Nacht ueber Mitternacht) -
 *   als „Dienst fällt aus“ waere das aber eine Zeile ohne Handlungsmoeglichkeit.
 * - Der naechste klingelnde Wecker kommt aus [AlarmSkipUseCase.naechsterKlingelnderWecker], auf
 *   DERSELBEN Grundmenge wie `berechneWeckerAnzeige` (anstehend, ohne den uebersprungenen). Eine
 *   eigene Auswahl hier waere die zweite Wahrheit, vor der jene Funktion warnt: der Knopf naennte
 *   einen anderen Wecker, als er abschaltet. Freigegebene Tage filtert diese Grundmenge bewusst
 *   NICHT vor - `skipNextAlarm` kennt sie auch nicht.
 *
 * - Schichtspannen ([spannen]) zaehlen nur, solange ihr Dienst nicht zu Ende ist und ihr Tag nicht
 *   vor heute liegt. Sie ERGAENZEN einen Tag, den es ohnehin gibt (Schichtzeile, Kaertchen), und
 *   sie halten genau einen Tag zusaetzlich in der Liste: HEUTE, solange der Dienst laeuft oder
 *   bevorsteht - auch wenn sein Wecker schon geklingelt hat. Am Krankheitsmorgen muss man den Tag
 *   noch antippen und „Dienst fällt aus“ waehlen koennen; vorher verschwand er mit der Weckzeit.
 *   Kuenftige Tage NUR aus einer Spanne entstehen bewusst nicht: ein Dienst ohne Wecker an einem
 *   kommenden Tag (abgewiesener Sync, Horizont) saehe aus wie ein Fehler der Liste.
 * - Der verstrichene Wecker von heute erscheint NICHT als Zeile („hat um 05:30 geweckt“): ob er
 *   wirklich geklingelt hat, weiss die Anzeige nicht - er kann uebersprungen oder still gewesen
 *   sein, und der Bestand hat ihn verworfen. Eine Zeile, die das behauptet, waere eine Anzeige, die
 *   es nicht gibt (Skill cfalarm-ui-und-navigation). Der Tag zeigt Dienst, Dienstzeit, Kaertchen
 *   - ohne „Licht“, das kommt nach der Weckzeit nicht mehr ([lichtMoeglich]).
 * - Die Spannen kommen ROH, nicht durch `FreieTageStore.filtereSpannen`: die Liste ist Anzeige und
 *   will gerade an einem freigegebenen Tag nennen, WELCHER Dienst ausfaellt. Dessen Wirkungen
 *   (Kaertchen) zeigt sie dort trotzdem nicht - siehe unten.
 *
 * - Ein schwebender Snooze ([schlummern]) steht als „schlummert“ an dem Tag, an dem er KLINGELT
 *   (nicht am Tag seiner Weckzeit: um 23:50 geweckt, um 00:20 wieder - die Zeile gehoert zu dem
 *   Tag, den man vor sich hat). Gehoert er zu einem freigegebenen Tag und ist nicht manuell, fehlt
 *   er: der Backstop im `AlarmReceiver` laesst ihn ohnehin nicht klingeln.
 *
 * - #142: ZWEI Quellen der Freigabe ([freigaben]: Hand und Kalender-Krank). Fuer alles, was
 *   WIRKT (Zeile in der Liste, „Entfällt“, Snooze, Kaertchen), zaehlt die Vereinigung - dieselbe wie
 *   bei Gate und Backstop. Die Quelle selbst ([FreigabeArt]) traegt nur die Anzeige.
 *
 * @param spannen der zuletzt geschriebene Stand des `ShiftSpanStore` (`ShiftUiState.letzterSchichtStand`).
 * @param wirkungen Schichtname (rein, ohne „(Manuell)“) -> [SchichtWirkung]. Fehlt ein Name,
 *   bekommt seine Schicht kein Kaertchen.
 */
internal fun baueWeckerTage(
    alarme: List<AlarmInfo>,
    // Bewusst OHNE Default, wie [wirkungen]: vergessen hiesse still „keine Dienste“.
    spannen: List<ShiftSpan>,
    // Bewusst OHNE Default: vergessen hiesse still „kein Tag frei“ - und die Liste zeigte Wecker
    // als „Weckt“, die Gate und Backstop nie klingeln lassen.
    freigaben: FreigabeQuellen,
    skip: SkipLage,
    // Bewusst OHNE Default, wie [wirkungen]: vergessen hiesse still „nichts schlummert“.
    schlummern: List<Schlummern>,
    jetzt: Long,
    zone: ZoneId,
    // Bewusst OHNE Default: ein vergessenes Argument liefe still als "keine Wirkungen" durch.
    wirkungen: Map<String, SchichtWirkung>
): List<WeckerTag> {
    val heute = tagVon(jetzt, zone)
    val skipZeit = skip.weckzeitpunkt?.takeIf { skip.aktiv && it > jetzt }

    // Der uebersprungene Eintrag ist normalerweise geloescht; liegt er doch noch da (id) oder kam
    // er unter neuer Kennung zurueck (gleicher Weckzeitpunkt - derselbe Anker wie Gate und
    // Backstop), darf er nicht zusaetzlich als „Weckt“ erscheinen.
    val anstehend = alarme.filter { alarm ->
        alarm.triggerTime > jetzt &&
            !(skip.aktiv && (alarm.id == skip.alarmId || alarm.triggerTime == skipZeit))
    }
    val naechster = AlarmSkipUseCase.naechsterKlingelnderWecker(anstehend, jetzt)
    val freiAbHeute = freigaben.alle.filterTo(HashSet()) { !it.isBefore(heute) }

    val eintraegeJeTag = HashMap<LocalDate, MutableList<WeckerEintrag>>()
    for (alarm in anstehend) {
        val tag = tagVon(alarm.triggerTime, zone)
        val manuell = AlarmSkipUseCase.isManualAlarm(alarm)
        val istNaechster = naechster != null && alarm.id == naechster.id &&
            alarm.triggerTime == naechster.triggerTime
        eintraegeJeTag.getOrPut(tag) { mutableListOf() } += WeckerEintrag.Wecker(
            alarm = alarm,
            uhrzeit = uhrzeitVon(alarm.triggerTime, zone),
            still = alarm.isSilent,
            manuell = manuell,
            naechster = istNaechster,
            nichtWeckenMoeglich = istNaechster && !skip.wirktNoch(jetzt),
            entfaelltDurchFreigabe = tag in freiAbHeute && !manuell
        )
    }
    if (skipZeit != null) {
        eintraegeJeTag.getOrPut(tagVon(skipZeit, zone)) { mutableListOf() } +=
            WeckerEintrag.Uebersprungen(skipZeit, uhrzeitVon(skipZeit, zone))
    }

    val freieTageAlle = freigaben.alle
    for (s in schlummern) {
        if (s.bis <= jetzt) continue
        val ankerTag = s.weckzeit?.let { tagVon(it, zone) }
        if (!s.manuell && ankerTag != null && ankerTag in freieTageAlle) continue
        eintraegeJeTag.getOrPut(tagVon(s.bis, zone)) { mutableListOf() } +=
            WeckerEintrag.Schlummert(s.bis, uhrzeitVon(s.bis, zone), s.manuell)
    }

    val spannenJeTag = spannen
        .filter { it.endTime > jetzt }
        .groupBy { FreieTageStore.tagVon(it, zone) }
        .filterKeys { !it.isBefore(heute) }
    val heuteMitDienst = listOfNotNull(heute.takeIf { spannenJeTag.containsKey(it) })

    return (eintraegeJeTag.keys + freiAbHeute + heuteMitDienst).sorted().map { datum ->
        val eintraege = eintraegeJeTag[datum].orEmpty().sortedBy { it.triggerTime }
        val freigabe = if (datum in freiAbHeute) artVon(datum, freigaben) else null
        val freigegeben = freigabe != null
        // Ein Wecker „wie <Typ>“ an einem Tag ohne Kalendereintrag ist kein Dienst: er hat keine
        // Spanne (#175), Dimmen, „Nicht stören“ und die Rufbereitschafts-Abfrage des Typs treten
        // nicht ein. Nur sein Licht haengt am Wecker und bleibt ([lichtOhneEintrag]).
        val (ohneEintrag, mitDienst) = eintraege.partition {
            it is WeckerEintrag.Wecker && AlarmUseCase.istOhneEintragWecker(it.alarm)
        }
        val schichten = schichtenDesTages(mitDienst, spannenJeTag[datum].orEmpty())
        WeckerTag(
            datum = datum,
            freigabe = freigabe,
            eintraege = eintraege,
            schichten = schichten,
            // Freigegeben: `FreieTageStore.filtereSpannen` nimmt die Spannen dieses Tages aus
            // Dimmer, „Nicht stören“ und der Rufbereitschafts-Abfrage - es wirkt also nichts davon.
            kaertchen = if (freigegeben) emptyList() else kaertchenFuerSchichten(
                schichten.map { reinerSchichtname(it.name) }.distinct(),
                wirkungen
            ).let { alle ->
                if (lichtOhneEintrag(ohneEintrag, wirkungen)) Kaertchen.entries.filter { it in alle || it == Kaertchen.LICHT } else alle
            }.let { alle -> if (lichtMoeglich(eintraege)) alle else alle - Kaertchen.LICHT }
        )
    }
}

/** #142: die Quelle der Freigabe eines Tages, der in mindestens einer der beiden Mengen steht. */
private fun artVon(datum: LocalDate, freigaben: FreigabeQuellen): FreigabeArt {
    val hand = datum in freigaben.hand
    val kalender = datum in freigaben.kalender
    return when {
        hand && kalender -> FreigabeArt.BEIDE
        kalender -> FreigabeArt.KALENDER
        else -> FreigabeArt.HAND
    }
}

/**
 * Die Kaertchen einer Schicht - nur fuer Wirkungen, die an diesem Tag WIRKLICH eintreten. Liest
 * ausschliesslich die Status aus [SchichtFolgen], rechnet nichts nach.
 *
 * - „Nicht stören“: Dienstzeit-Quelle AN oder „folgt dem Dimmer“. NICHT `!dnd.aus`: bei
 *   AUSGENOMMEN ohne Dimmer-Quelle ist `aus` false, fuer diese Schicht wirkt aber keine Quelle -
 *   das Kaertchen versprache ein „Nicht stören“, das nie kommt.
 * - „Dimmen“: eine wirksame Regel, die nicht UNTERDRUECKT (leere Fensterliste = kein Dimmen).
 * - „Licht“: mindestens eine Hue-Regel; nicht lesbar / keine Bridge / stille Schicht = keins.
 *   Auf Tagesebene zusaetzlich nur mit klingelndem Wecker ([lichtMoeglich]).
 * - „Rufbereitschaft“: [ShiftDefinition.isOnCall] DIREKT. NICHT `DndStatus.rufbereitschaft` -
 *   das ist der DND-Cutoff und ist `false`, sobald beide DND-Quellen aus sind; die stuendliche
 *   Kalender-Abfrage laeuft trotzdem.
 */
internal fun kaertchenFuer(wirkung: SchichtWirkung): Set<Kaertchen> = buildSet {
    val folgen = wirkung.folgen
    if (folgen.dnd.dienstzeit == DienstzeitDnd.AN || folgen.dnd.nachDimmer) add(Kaertchen.NICHT_STOEREN)
    val dimmen = folgen.dimmen
    if (dimmen is DimmStatus.Wirksam && dimmen.art != DimmArt.UNTERDRUECKT) add(Kaertchen.DIMMEN)
    val licht = folgen.licht
    if (licht is LichtStatus.Regeln && licht.eigene + licht.allgemeine > 0) add(Kaertchen.LICHT)
    if (wirkung.definition.isOnCall) add(Kaertchen.RUFBEREITSCHAFT)
}

/**
 * Geht an diesem Tag noch ein Licht an? Nur mit einem Wecker, der klingelt: Hue laeuft
 * ausschliesslich ueber den Weckzeit-Pfad im `AlarmReceiver` und den Vorab-Sonnenaufgang, und
 * beide haengen am Wecker. „Nicht wecken“ und „Dienst fällt aus“ loeschen ihn, der Sonnenaufgang
 * prueft das beim Start (`HueSmartScheduler.sonnenaufgangNochGedeckt`) - die Schicht kommt dann
 * nur noch aus dem `ShiftSpanStore`, das Licht nicht.
 *
 * Gilt genauso fuer heute nach der Weckzeit: das Licht des verstrichenen Weckers ist gewesen, es
 * kommt keins mehr. Dimmen, „Nicht stören“ und die Rufbereitschaft folgen dagegen der Spanne und
 * bleiben.
 */
private fun lichtMoeglich(eintraege: List<WeckerEintrag>): Boolean =
    eintraege.any { it is WeckerEintrag.Wecker && !it.still }

/** Hat der Typ eines klingelnden Weckers an einem Tag ohne Eintrag Licht? (siehe [lichtMoeglich]) */
private fun lichtOhneEintrag(ohneEintrag: List<WeckerEintrag>, wirkungen: Map<String, SchichtWirkung>): Boolean =
    ohneEintrag.filterIsInstance<WeckerEintrag.Wecker>()
        .filter { !it.still }
        .mapNotNull { wirkungen[reinerSchichtname(it.alarm.shiftName)] }
        .any { Kaertchen.LICHT in kaertchenFuer(it) }

/** Vereinigung ueber alle bekannten Schichten des Tages, in fester Reihenfolge der Enum. */
internal fun kaertchenFuerSchichten(
    reineNamen: List<String>,
    wirkungen: Map<String, SchichtWirkung>
): List<Kaertchen> {
    val alle = reineNamen.mapNotNull { wirkungen[it] }.flatMapTo(HashSet()) { kaertchenFuer(it) }
    return Kaertchen.entries.filter { it in alle }
}

/**
 * Eine Zeile je Schicht, zusammengefasst ueber den REINEN Namen - aus den Weckern UND den
 * Schichtspannen des Tages. Ein manueller „Frühdienst (Manuell)“ neben dem Dienstplan-Frühdienst
 * ist dieselbe Schicht, und die Spanne zum Wecker ist derselbe Dienst: der Kopf des Tages nennt
 * ihn einmal. Bevorzugt wird der Eintrag MIT bekannter Dienstzeit (ein manueller hat 0/0) und
 * dessen Anzeigename. Reihenfolge: nach Weckzeit.
 */
private fun schichtenDesTages(eintraege: List<WeckerEintrag>, spannen: List<ShiftSpan>): List<TagesSchicht> {
    val ausWeckern = eintraege.filterIsInstance<WeckerEintrag.Wecker>().map { it.alarm }.map {
        it.triggerTime to TagesSchicht(it.shiftName, it.shiftStartTime, it.shiftEndTime)
    }
    val ausSpannen = spannen.map { it.alarmTriggerTime to TagesSchicht(it.shiftName, it.startTime, it.endTime) }
    return (ausWeckern + ausSpannen)
        .sortedBy { it.first }
        .map { it.second }
        .filter { it.name.isNotBlank() }
        .groupBy { reinerSchichtname(it.name) }
        .values
        .map { gruppe -> gruppe.firstOrNull { it.beginn != 0L || it.ende != 0L } ?: gruppe.first() }
}

private fun tagVon(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun uhrzeitVon(millis: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().withSecond(0).withNano(0)
