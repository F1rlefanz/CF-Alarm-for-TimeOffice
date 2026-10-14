package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
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
 */
data class WeckerTag(
    val datum: LocalDate,
    /** `true`: der Nutzer hat diesen Tag freigegeben („Dienst fällt aus“). */
    val freigegeben: Boolean,
    /** Nach Weckzeit sortiert; bei einem freigegebenen Tag ohne eigene Wecker leer. */
    val eintraege: List<WeckerEintrag>,
    /** Die Schichten des Tages in Reihenfolge ihrer Wecker, ohne Doppelungen. */
    val schichten: List<TagesSchicht>,
    /** Was die Dienste dieses Tages ausserhalb des Weckers ausloesen - siehe [kaertchenFuer]. */
    val kaertchen: List<Kaertchen>
)

/** Eine Schicht des Tages mit Dienstzeit; `null` = unbekannt (Feld war 0). */
data class TagesSchicht(
    val name: String,
    val beginn: LocalTime?,
    val ende: LocalTime?
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
         * aktiv ist - es gibt genau einen Skip-Platz, und `skipNextAlarm` traefe ohnehin immer
         * den naechsten, nie die angetippte Zeile (Variante A).
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
 * @param wirkungen Schichtname (rein, ohne „(Manuell)“) -> [SchichtWirkung]. Fehlt ein Name,
 *   bekommt seine Schicht kein Kaertchen.
 */
internal fun baueWeckerTage(
    alarme: List<AlarmInfo>,
    freieTage: Collection<LocalDate>,
    skip: SkipLage,
    jetzt: Long,
    zone: ZoneId,
    wirkungen: Map<String, SchichtWirkung> = emptyMap()
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
    val freiAbHeute = freieTage.filterTo(HashSet()) { !it.isBefore(heute) }

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
            nichtWeckenMoeglich = istNaechster && !skip.aktiv,
            entfaelltDurchFreigabe = tag in freiAbHeute && !manuell
        )
    }
    if (skipZeit != null) {
        eintraegeJeTag.getOrPut(tagVon(skipZeit, zone)) { mutableListOf() } +=
            WeckerEintrag.Uebersprungen(skipZeit, uhrzeitVon(skipZeit, zone))
    }

    return (eintraegeJeTag.keys + freiAbHeute).sorted().map { datum ->
        val eintraege = eintraegeJeTag[datum].orEmpty().sortedBy { it.triggerTime }
        val freigegeben = datum in freiAbHeute
        val schichten = schichtenDesTages(eintraege, zone)
        WeckerTag(
            datum = datum,
            freigegeben = freigegeben,
            eintraege = eintraege,
            schichten = schichten,
            // Freigegeben: `FreieTageStore.filtereSpannen` nimmt die Spannen dieses Tages aus
            // Dimmer, „Nicht stören“ und der Rufbereitschafts-Abfrage - es wirkt also nichts davon.
            kaertchen = if (freigegeben) emptyList() else kaertchenFuerSchichten(
                eintraege.filterIsInstance<WeckerEintrag.Wecker>()
                    .map { reinerSchichtname(it.alarm.shiftName) }
                    .distinct(),
                wirkungen
            )
        )
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

/** Vereinigung ueber alle bekannten Schichten des Tages, in fester Reihenfolge der Enum. */
internal fun kaertchenFuerSchichten(
    reineNamen: List<String>,
    wirkungen: Map<String, SchichtWirkung>
): List<Kaertchen> {
    val alle = reineNamen.mapNotNull { wirkungen[it] }.flatMapTo(HashSet()) { kaertchenFuer(it) }
    return Kaertchen.entries.filter { it in alle }
}

private fun schichtenDesTages(eintraege: List<WeckerEintrag>, zone: ZoneId): List<TagesSchicht> =
    eintraege.filterIsInstance<WeckerEintrag.Wecker>()
        .filter { it.alarm.shiftName.isNotBlank() }
        .distinctBy { it.alarm.shiftName }
        .map {
            TagesSchicht(
                name = it.alarm.shiftName,
                beginn = it.alarm.shiftStartTime.takeIf { t -> t != 0L }?.let { t -> uhrzeitVon(t, zone) },
                ende = it.alarm.shiftEndTime.takeIf { t -> t != 0L }?.let { t -> uhrzeitVon(t, zone) }
            )
        }

private fun tagVon(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun uhrzeitVon(millis: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().withSecond(0).withNano(0)
