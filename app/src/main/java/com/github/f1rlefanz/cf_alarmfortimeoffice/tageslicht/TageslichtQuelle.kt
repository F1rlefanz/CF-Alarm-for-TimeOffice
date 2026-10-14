package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

/**
 * Der EINZIGE Weg, auf dem Hintergrund-Code (Dimmer-Tick, AlarmReceiver, Hue-Planung) an einen
 * [TageslichtStand] kommt (#140). Die Implementierung fragt die Entsperrung VOR jedem Lesen: ein
 * CE-DataStore-Read im gesperrten Prozess liefert still "leer" und vergiftet den ganzen Store
 * fuer den Prozess (Skill cfalarm-persistenz-und-auth).
 */
interface TageslichtQuelle {

    /** NIE werfend. Gesperrter Nutzer, Lesefehler, kein Ort -> [TageslichtStand.ohneOrt]. */
    suspend fun stand(): TageslichtStand

    companion object {
        /** Keine Einschraenkung - fuer Tests und als Konstruktor-Default. */
        val OHNE: TageslichtQuelle = object : TageslichtQuelle {
            override suspend fun stand() = TageslichtStand.ohneOrt()
        }
    }
}
