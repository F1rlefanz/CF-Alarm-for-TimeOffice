package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Eine Szene der Bridge - das, was der Nutzer in der Hue-App als Licht-"Profil" anlegt
 * ("Nachtlicht", "Entspannen"). Sie bringt Helligkeit und Farbe je Lampe selbst mit; die App
 * waehlt sie nur aus und wendet sie an.
 *
 * ALLE Felder ausser [id] sind NULLABLE, und das ist Absicht: Gson erzwingt Kotlins
 * Non-Null-Deklarationen NICHT. `fromJson("{}", HueScene::class.java)` liefert ein Objekt voller
 * `null` und wirft dabei nicht - dieselbe Falle, die `getBridgeConfig` dazu gebracht hat, die
 * Antwort zu PRUEFEN statt sie nur zu deserialisieren. Gefiltert wird deshalb im Repository,
 * nicht im Konsumenten.
 *
 * [id] ist der Schluessel der Map aus `GET /api/<user>/scenes`, nicht ein Feld des Rumpfes, und
 * er ist **bridge-lokal** - genau dieselbe Falle wie `HueLightAction.targetId`. Der Anker ueber
 * Geraete hinweg ist das Paar (Szenenname, Gruppenname); siehe `HueTargetReconciler`.
 *
 * ABSICHTLICH NICHT ABGEBILDET sind die uebrigen Felder der Antwort (`type`, `lights`, `owner`,
 * `locked`, `appdata`, `picture`, `lastupdated`, `version`). Sie stehen im JSON, aber niemand
 * liest sie - und ein Feld, das nur befuellt und nie gelesen wird, sieht bei der naechsten
 * Aenderung wie eine vorhandene Faehigkeit aus. `type` waere zudem eine zweite Wahrheit neben
 * [isGroupScene]: massgeblich ist, ob eine `group` da ist, denn genau die braucht der PUT und
 * der Namensanker. Wer eines davon spaeter braucht, holt es zurueck - dann mit Leser.
 *
 * Gemessen an der Bridge des Nutzers (BSB002, apiversion 1.78.0, 25.08.2026): 73 Szenen,
 * davon 67 GroupScene und 6 LightScene; die Listen-Antwort traegt **kein** `lightstates`
 * (das gibt es nur bei `GET /scenes/<id>`), deshalb bleibt sie mit ~21 kB handhabbar.
 */
@Immutable
data class HueScene(
    val id: String,
    val name: String? = null,
    val group: String? = null,         // nur GroupScene; die Gruppen-Id dieser Bridge
    val recycle: Boolean? = null       // von der Hue-App automatisch angelegt/aufgeraeumt
) {
    /**
     * Nur eine Szene MIT Gruppe ist fuer uns brauchbar: die Gruppe ist der zweite Teil des
     * Namensankers und zugleich das Ziel des Auto-Aus. Eine LightScene haette beides nicht.
     */
    val isGroupScene: Boolean get() = !group.isNullOrBlank()
}

/**
 * Welche Szenen der Bridge fuer eine Regel ueberhaupt in Frage kommen - und wie viele aus
 * welchem Grund wegfallen.
 *
 * REIN und ohne Android, damit pruefbar (Vorbild: `HueTargetReconciler`, `DimWindowResolver`).
 * Vorher stand dieselbe Entscheidung inline im Repository und war damit nur am Geraet zu sehen -
 * ausgerechnet die Entscheidung, die dem Nutzer Szenen aus der Liste NIMMT.
 *
 * Die drei Gruende sind einzeln gezaehlt, weil sie einzeln ins Log gehoeren: "meine Szene fehlt"
 * ist sonst nicht diagnostizierbar, der Nutzer sieht nur eine kuerzere Liste.
 */
data class SzenenAuswahlErgebnis(
    val nutzbar: List<HueScene>,
    val ohneNamen: Int,
    val automatischVerwaltet: Int,
    val ohneRaum: Int
) {
    val gesamt: Int get() = nutzbar.size + ohneNamen + automatischVerwaltet + ohneRaum
}

/**
 * Filtert die Rohliste der Bridge.
 *
 * Drei Ausschlussgruende, in dieser Reihenfolge gezaehlt (ein Eintrag kann mehrere erfuellen -
 * gezaehlt wird er beim ERSTEN, damit die Summe der Zahlen die Rohmenge ergibt):
 *  1. **ohne Namen** - Gson erzwingt Kotlins Non-Null NICHT, ein Eintrag ohne `name` ist real
 *     moeglich und in der Auswahl unbenennbar.
 *  2. **`recycle: true`** - von der Hue-App selbst angelegt und wieder aufgeraeumt; auf der
 *     Bridge des Nutzers real vorhanden (2 von 73, gemessen 25.08.2026).
 *  3. **ohne Raum/Zone** (LightScene) - ihr fehlt der Gruppen-Namensanker UND das Ziel fuers
 *     Auto-Aus. Bewusster Produktschnitt, kein API-Hindernis; die Oberflaeche sagt es auch.
 */
fun waehleNutzbareSzenen(roh: List<HueScene>): SzenenAuswahlErgebnis {
    var ohneNamen = 0
    var automatisch = 0
    var ohneRaum = 0
    val nutzbar = mutableListOf<HueScene>()

    roh.forEach { szene ->
        when {
            szene.name.isNullOrBlank() -> ohneNamen++
            szene.recycle == true -> automatisch++
            !szene.isGroupScene -> ohneRaum++
            else -> nutzbar += szene
        }
    }

    return SzenenAuswahlErgebnis(nutzbar, ohneNamen, automatisch, ohneRaum)
}

