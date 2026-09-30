package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

/**
 * Rohgestalt eines Szenen-Eintrags, wie ihn `GET /api/<user>/scenes` liefert.
 *
 * Eine eigene Klasse, weil die Szenen-Id der SCHLUESSEL der Map ist und nicht im Rumpf steht -
 * `HueScene.id` waere beim direkten Deserialisieren `null`, obwohl es nicht-nullbar deklariert
 * ist (Gson erzwingt das nicht). Der Schluessel wird deshalb in [toDomain] von Hand gesetzt.
 * Abgebildet wird nur, was auch GELESEN wird - Gson ignoriert alle uebrigen Felder der Antwort
 * von sich aus. Siehe den Kommentar an [HueScene]: ein Feld ohne Leser sieht spaeter wie eine
 * vorhandene Faehigkeit aus.
 *
 * Sie liegt in `hue.data`, weil Gson sie reflexiv befuellt und R8 nur dieses Paket ganz haelt;
 * ausserhalb fehlt sie im Release-Build (`GsonZielklassenR8Test`).
 */
internal data class HueSceneDto(
    val name: String? = null,
    val group: String? = null,
    val recycle: Boolean? = null
) {
    fun toDomain(id: String): HueScene = HueScene(
        id = id,
        name = name,
        group = group,
        recycle = recycle
    )
}
