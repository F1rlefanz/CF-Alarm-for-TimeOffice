package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmInfoData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

enum class SkipProcessResult {
    ALARM_SKIPPED,    // Alarm wurde übersprungen
    ALARM_EXECUTED    // Alarm normal ausgeführt
}

data class AlarmSkipResult(
    val alarmId: Int,
    val alarmName: String,
    val formattedTime: String
)

/**
 * Sicherung eines uebersprungenen MANUELLEN Weckers.
 *
 * WARUM ES SIE GIBT: Ueberspringen loescht den Alarm hart - erst `cancelSystemAlarm()`, dann
 * `deleteAlarm()`. Das ist keine Umstaendlichkeit, sondern eine gehaltene Invariante: mehrere
 * voneinander unabhaengige Stellen der App verlassen sich darauf, dass ein uebersprungener Alarm
 * WEG ist (der Direct-Boot-Spiegel, den der `BootReceiver` ungefiltert wieder armiert; die
 * Hue-Tagesplanung, die ihren Bestand ungefiltert aus `getAllAlarms()` liest). Ein
 * kalenderbasierter Wecker entsteht beim "Aufheben" aus dem Kalenderstand neu - ein manueller hat
 * keine solche Quelle, und ohne diesen Schnappschuss waere "Ueberspringen" fuer ihn endgueltig,
 * obwohl die Oberflaeche eine Umkehr verspricht.
 *
 * FORMAT: bewusst [AlarmInfoData] - dasselbe Serialisierungsmuster wie im Alarm-Bestand
 * (`AlarmRepository`), kein zweites Format. `isActive` fuehrt auch der Bestand nicht mit; ein
 * wiederhergestellter Wecker ist damit aktiv, was er als armierter Wecker ohnehin ist.
 */
object ManualAlarmSnapshot {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(alarmInfo: AlarmInfo): String = json.encodeToString(
        AlarmInfoData.serializer(),
        AlarmInfoData(
            id = alarmInfo.id,
            shiftId = alarmInfo.shiftId,
            shiftName = alarmInfo.shiftName,
            triggerTime = alarmInfo.triggerTime,
            formattedTime = alarmInfo.formattedTime,
            eventId = alarmInfo.eventId,
            eventChecksum = alarmInfo.eventChecksum,
            shiftEndTime = alarmInfo.shiftEndTime,
            shiftStartTime = alarmInfo.shiftStartTime,
            isSilent = alarmInfo.isSilent
        )
    )

    /**
     * `null` (kein Schnappschuss vorhanden) ist ein regulaeres Ergebnis - ein unlesbarer
     * Schnappschuss dagegen ein FEHLER und kein "gibt es halt keinen": der Aufrufer muss den
     * Unterschied sehen koennen, sonst verschwindet ein manueller Wecker stillschweigend.
     */
    fun decode(raw: String?): Result<AlarmInfo?> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val data = json.decodeFromString(AlarmInfoData.serializer(), raw)
        AlarmInfo(
            id = data.id,
            shiftId = data.shiftId,
            shiftName = data.shiftName,
            triggerTime = data.triggerTime,
            formattedTime = data.formattedTime,
            eventId = data.eventId,
            eventChecksum = data.eventChecksum,
            shiftEndTime = data.shiftEndTime,
            shiftStartTime = data.shiftStartTime,
            isSilent = data.isSilent
        )
    }
}

/**
 * Interface for alarm skip use case operations.
 * Defines the contract for alarm skip business logic.
 */
interface IAlarmSkipUseCase {
    suspend fun skipNextAlarm(): Result<AlarmSkipResult>
    suspend fun cancelSkip(): Result<Unit>
    suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult>
    suspend fun getSkipStatus(): Result<AlarmSkipState>

    /**
     * Loescht ein abgelaufenes Skip-Flag: der urspruenglich uebersprungene Alarm-Zeitpunkt liegt
     * in der Vergangenheit, der Skip hat seinen Zweck also erfuellt. Faengt den Fall ab, dass der
     * eigentliche Rueckmeldepfad (checkAndProcessSkip via AlarmReceiver) nie erreicht wird, weil
     * der System-Alarm beim Ueberspringen sofort geloescht wurde und darum nie mehr feuert.
     * Gibt true zurueck, wenn ein abgelaufener Skip tatsaechlich zurueckgesetzt wurde.
     */
    suspend fun clearExpiredSkip(): Result<Boolean>

    val skipStatusFlow: Flow<AlarmSkipState>
}
