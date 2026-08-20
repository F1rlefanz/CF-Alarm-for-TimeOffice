package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.IOException

/**
 * DER ZWEITE WEG IN DEN REINEN ARBEITSSPEICHER - und bis zu diesem Fix der stumme.
 *
 * `persistToDataStore()` meldet einen Fehlschlag in ZWEI Faellen: bei gesperrter Persistenz
 * (gescheiterter Init-Load) und wenn der Schreibweg selbst wirft - voller Speicher, IOException,
 * beschaedigte `preferences_pb`. Nur der erste Fall setzte `persistenceBlocked`. Wer danach
 * nach dem Anlegen des manuellen Weckers fragte, bekam "alles in Ordnung", obwohl `saveAlarm()`
 * unmittelbar davor "liegt NUR im Arbeitsspeicher" geloggt hatte. Dafuer gibt es jetzt
 * [AlarmRepository.istLetzterSchreibvorgangGescheitert].
 *
 * WARUM EIN EIGENES SIGNAL UND KEIN VERODERN MIT `isPersistenceBlocked()` (so lief der erste
 * Wurf): die beiden Lagen bedeuten Verschiedenes. "Bestand unlesbar" heisst, dass
 * `AlarmUseCase.clearInternalAlarms()` beim ausdruecklichen Abschalten die
 * `cancelSystemAlarm`-Schleife ueberspringen MUSS (sie liefe ins Leere); "Schreibfehler" heisst,
 * dass der Bestand vollstaendig lesbar ist und die Schleife laufen MUSS. Verodert liess die
 * Master-Pause armierte System-Alarme zurueck, die niemand mehr abbrechen kann - siehe
 * `Pruefrunde8SignaltrennungTest`.
 *
 * Diese Tests fahren den ECHTEN [AlarmRepository] gegen einen Speicher, der wirklich wirft. Sie
 * pruefen also den Schreibvorgang, nicht die Frage, die der Fix stellt.
 */
class Pruefrunde8SchreibfehlerSichtbarTest {

    private val now = System.currentTimeMillis()

    /**
     * In-Memory-DataStore, dessen erste [fehlerhafteSchreibversuche] Schreibvorgaenge werfen.
     *
     * Lesen bleibt intakt: genau so sieht ein voller Speicher aus - der Init-Load gelingt, es gibt
     * keine Sperre, und erst das Schreiben scheitert.
     */
    private class SchreibfehlerDataStore(
        private val fehlerhafteSchreibversuche: Int
    ) : DataStore<Preferences> {
        private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
        var schreibversuche = 0
            private set

        override val data: Flow<Preferences> = flow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            schreibversuche++
            if (schreibversuche <= fehlerhafteSchreibversuche) {
                throw IOException("kein Platz auf dem Geraet")
            }
            val neu = transform(flow.value)
            flow.value = neu
            return neu
        }
    }

    private fun zukunftsalarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "t$id"
    )

    private fun repository(store: DataStore<Preferences>) =
        AlarmRepository(store, mock<DirectBootAlarmStore>(), AlarmRepoTestContext.unlocked())

    @Test
    fun `ein geworfener Schreibvorgang macht den Bestand als nicht dauerhaft erkennbar`() = runTest {
        // OHNE DEN FIX: saveAlarm meldet Erfolg (bewusst - der Wecker klingelt trotzdem), und
        // isPersistenceBlocked() meldet false. Der manuelle Wecker gibt sich damit als dauerhaft
        // gespeichert aus, obwohl weder Preferences-Datei noch Direct-Boot-Spiegel ihn haben.
        val store = SchreibfehlerDataStore(fehlerhafteSchreibversuche = Int.MAX_VALUE)
        val repo = repository(store)

        val ergebnis = repo.saveAlarm(zukunftsalarm(7))

        assertTrue(
            "saveAlarm meldet weiterhin Erfolg - das ist Absicht, sonst bliebe der Alarm " +
                "unarmiert (siehe die Begruendung an saveAlarm)",
            ergebnis.isSuccess
        )
        assertTrue(
            "Der Schreibfehler MUSS nachfragbar sein - sonst ist der Wecker nach einem Neustart " +
                "weg, ohne dass es je jemand gesagt haette",
            repo.istLetzterSchreibvorgangGescheitert()
        )
        assertFalse(
            "Aber er ist KEINE Lesesperre: der Bestand liegt vollstaendig im Arbeitsspeicher. " +
                "Wer das verwechselt, laesst das Raeumen die Cancel-Schleife ueberspringen",
            repo.isPersistenceBlocked()
        )
    }

    @Test
    fun `ein gelungener Schreibvorgang hebt den Merker wieder auf`() = runTest {
        // KEINE Dauerwarnung: der Merker beschreibt den LETZTEN Versuch. Bliebe er stehen, wuerde
        // ein einmaliger, laengst behobener Speicherplatzmangel jeden weiteren manuellen Wecker
        // faelschlich als nicht dauerhaft melden - und eine Warnung, die immer steht, liest
        // niemand mehr.
        val store = SchreibfehlerDataStore(fehlerhafteSchreibversuche = 1)
        val repo = repository(store)

        repo.saveAlarm(zukunftsalarm(7))
        assertTrue(
            "Vorbedingung: der erste Schreibversuch ist gescheitert",
            repo.istLetzterSchreibvorgangGescheitert()
        )

        repo.saveAlarm(zukunftsalarm(8))

        assertFalse(
            "Nach einem gelungenen Schreibvorgang liegt der Bestand wieder dauerhaft vor",
            repo.istLetzterSchreibvorgangGescheitert()
        )
    }

    @Test
    fun `ohne Schreibfehler bleibt die Nachfrage negativ`() = runTest {
        // Gegenprobe: waere die Antwort immer "nicht dauerhaft", wuerde die Warnung bei JEDEM
        // manuellen Wecker erscheinen - und damit von niemandem mehr ernst genommen.
        val store = SchreibfehlerDataStore(fehlerhafteSchreibversuche = 0)
        val repo = repository(store)

        repo.saveAlarm(zukunftsalarm(7))

        assertFalse(repo.istLetzterSchreibvorgangGescheitert())
        assertFalse(repo.isPersistenceBlocked())
    }
}
