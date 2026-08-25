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
 * Gemessen an der Bridge des Nutzers (BSB002, apiversion 1.78.0, 25.08.2026): 73 Szenen,
 * davon 67 GroupScene und 6 LightScene; die Listen-Antwort traegt **kein** `lightstates`
 * (das gibt es nur bei `GET /scenes/<id>`), deshalb bleibt sie mit ~21 kB handhabbar.
 */
@Immutable
data class HueScene(
    val id: String,
    val name: String? = null,
    val type: String? = null,          // "GroupScene" | "LightScene"
    val group: String? = null,         // nur GroupScene; die Gruppen-Id dieser Bridge
    val lights: List<String>? = null,
    val recycle: Boolean? = null       // von der Hue-App automatisch angelegt/aufgeraeumt
) {
    /**
     * Nur eine Szene MIT Gruppe ist fuer uns brauchbar: die Gruppe ist der zweite Teil des
     * Namensankers und zugleich das Ziel des Auto-Aus. Eine LightScene haette beides nicht.
     */
    val isGroupScene: Boolean get() = !group.isNullOrBlank()
}
