package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Ausfall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Der Merker darf erst stehen, wenn die Warnung wirklich beim Nutzer angekommen ist.
 *
 * DER BEFUND (Pruefrunde 18.08.2026): `onFetchOutcome()` schrieb den "bereits gemeldet"-Merker,
 * BEVOR feststand, dass sich die Warnung ueberhaupt zustellen laesst - `zeige()` postete ohne
 * jede Pruefung, ohne try/catch und ohne Rueckmeldung. Waren Benachrichtigungen blockiert (App
 * ODER dieser einzelne Kanal), verschluckte `notify()` die Warnung lautlos, der Merker stand -
 * und weil die ID per Schnittmengenbildung im Merker bleibt, solange der Kalender scheitert, kam
 * die Warnung auch nach dem Wiedereinschalten NIE.
 *
 * Das ist genau die Meldung, die den Nutzer erreichen soll, OHNE dass er die App oeffnet: dass
 * seine Wecker versiegen. Faellt sie aus, faellt sie dauerhaft aus.
 *
 * Der zweite, unabhaengige Merker (`zuletztGescheitert`, die Entprellung) muss dabei bei JEDEM
 * Lauf fortgeschrieben werden - er unterscheidet "dauerhaft" von "Aussetzer" und darf sich durch
 * die Zustell-Frage nicht mitaendern.
 */
class CalendarUnavailableNotifierZustellungTest {

    /** Minimaler, echter In-Memory-DataStore<Preferences> - deckt data/updateData (und edit{}) ab. */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    /**
     * Zaehlt die Meldeversuche und bestimmt ueber [zustellbar], ob sie gelingen - genau die
     * Unterscheidung, die `zeige()` seit v1.26.3 zurueckgibt.
     */
    private class TestNotifier(
        context: Context,
        prefs: CalendarUnavailablePrefs,
        var zustellbar: Boolean
    ) : CalendarUnavailableNotifier(context, prefs) {
        var versuche = 0
            private set

        var letzterTitel: String? = null
            private set

        override fun zeige(title: String, text: String): Boolean {
            versuche++
            letzterTitel = title
            return zustellbar
        }

        var zurueckgenommen = 0
            private set

        override fun nimmZurueck() {
            zurueckgenommen++
        }
    }

    private fun notifier(zustellbar: Boolean): Pair<TestNotifier, CalendarUnavailablePrefs> {
        val prefs = CalendarUnavailablePrefs(FakePreferencesDataStore())
        return TestNotifier(mock<Context>(), prefs, zustellbar) to prefs
    }

    @Test
    fun `eine nicht zugestellte Warnung wird beim naechsten Anlass erneut versucht`() = runTest {
        val (notifier, prefs) = notifier(zustellbar = false)

        // Erster Ausfall: Entprellung, noch keine Meldung.
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals("Ein einzelner Aussetzer meldet nicht", 0, notifier.versuche)

        // Zweiter Ausfall in Folge: Meldung faellig - aber nicht zustellbar.
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals(1, notifier.versuche)
        assertTrue(
            "Ohne bestaetigte Zustellung darf nichts als gemeldet gelten - genau hier stand der Merker vorher",
            prefs.zustandNow().bereitsGemeldet.isEmpty()
        )

        // Der Nutzer schaltet die Benachrichtigungen wieder ein: der naechste Lauf muss es
        // erneut versuchen. Vor dem Fix kam die Warnung hier NIE mehr.
        notifier.zustellbar = true
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals("Der erneute Versuch fehlt", 2, notifier.versuche)
        assertEquals(setOf("dienstplan"), prefs.zustandNow().bereitsGemeldet)
    }

    @Test
    fun `eine zugestellte Warnung wiederholt sich nicht`() = runTest {
        val (notifier, prefs) = notifier(zustellbar = true)

        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals(1, notifier.versuche)
        assertEquals(setOf("dienstplan"), prefs.zustandNow().bereitsGemeldet)

        // Dritter Lauf: dieselbe Stoerung darf nicht alle sechs Stunden erneut klingeln.
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals(1, notifier.versuche)
    }

    @Test
    fun `der Beharrlichkeits-Merker wird auch ohne Zustellung fortgeschrieben`() = runTest {
        // Er beantwortet eine ANDERE Frage ("dauerhaft oder Aussetzer?") und darf sich von der
        // Zustellbarkeit nicht anstecken lassen - sonst braeuchte jede Stoerung wieder zwei
        // Laeufe, oder eine Erholung wuerde nie bemerkt.
        val (notifier, prefs) = notifier(zustellbar = false)

        notifier.onFetchOutcome(setOf("dienstplan", "bereitschaft"), Ausfall.EINZELNE)
        assertEquals(
            setOf("dienstplan", "bereitschaft"),
            prefs.zustandNow().zuletztGescheitert
        )

        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        assertEquals(
            "Der erholte Kalender muss aus dem Merker fallen",
            setOf("dienstplan"),
            prefs.zustandNow().zuletztGescheitert
        )

        notifier.onFetchOutcome(emptySet(), Ausfall.EINZELNE)
        assertTrue(prefs.zustandNow().zuletztGescheitert.isEmpty())
    }

    @Test
    fun `bei abgeschalteter Meldung wird gar nicht erst gepostet`() = runTest {
        val (notifier, prefs) = notifier(zustellbar = true)
        prefs.setEnabled(false)

        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)

        assertEquals("Der Toggle des Nutzers gilt vor der Zustellbarkeit", 0, notifier.versuche)
        assertTrue(prefs.zustandNow().bereitsGemeldet.isEmpty())
        assertEquals(setOf("dienstplan"), prefs.zustandNow().zuletztGescheitert)
    }

    /**
     * Den Text waehlt der Anlass des MELDENDEN Laufs - die Entprellung zaehlt davon unabhaengig
     * je Kennung. Fehlt der Kalender beim zweiten Lauf ganz, heisst die Meldung so wie die Karte in
     * der Uebersicht.
     */
    @Test
    fun `die Meldung traegt den Text ihres Anlasses`() = runTest {
        val (notifier, _) = notifier(zustellbar = true)

        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.ALLE_NICHT_GEFUNDEN)

        assertEquals(1, notifier.versuche)
        assertEquals("Kalender nicht gefunden", notifier.letzterTitel)
    }

    // ------------------------------------------------ Die Meldung nach der Erholung zuruecknehmen

    /**
     * Eine Stoerungsmeldung ueber einer wieder funktionierenden App ist dieselbe Sorte Unwahrheit
     * wie eine falsche - dieselbe Regel, nach der die Wartung ihre "Kalender-Synchronisation
     * gestoert" wieder einsammelt (quittiereTokenErfolg). Besonders beim Totalausfall: wer den
     * Kalender in Google wieder freigibt, statt die App zu oeffnen, behielte sonst "Kalender nicht
     * gefunden" in der Leiste.
     */
    @Test
    fun `nach der Erholung wird die Meldung zurueckgenommen`() = runTest {
        val (notifier, _) = notifier(zustellbar = true)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.ALLE_NICHT_GEFUNDEN)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.ALLE_NICHT_GEFUNDEN)
        assertEquals(1, notifier.versuche)

        notifier.onFetchOutcome(emptySet(), Ausfall.EINZELNE)

        assertEquals(1, notifier.zurueckgenommen)
    }

    @Test
    fun `solange ein gemeldeter Kalender weiter scheitert, bleibt die Meldung stehen`() = runTest {
        val (notifier, _) = notifier(zustellbar = true)
        notifier.onFetchOutcome(setOf("dienstplan", "bereitschaft"), Ausfall.EINZELNE)
        notifier.onFetchOutcome(setOf("dienstplan", "bereitschaft"), Ausfall.EINZELNE)

        notifier.onFetchOutcome(setOf("bereitschaft"), Ausfall.EINZELNE)
        assertEquals("Einer scheitert noch - die Meldung hat noch etwas zu sagen", 0, notifier.zurueckgenommen)

        notifier.onFetchOutcome(emptySet(), Ausfall.EINZELNE)
        assertEquals(1, notifier.zurueckgenommen)
    }

    @Test
    fun `was nie gemeldet wurde, wird auch nicht zurueckgenommen`() = runTest {
        // Ein einzelner Aussetzer meldet nichts - seine Erholung darf dann auch keine fremde
        // Meldung abraeumen (2203 gehoert nur dieser Warnung, aber nichts stand da).
        val (notifier, _) = notifier(zustellbar = true)
        notifier.onFetchOutcome(setOf("dienstplan"), Ausfall.EINZELNE)
        notifier.onFetchOutcome(emptySet(), Ausfall.EINZELNE)

        assertEquals(0, notifier.zurueckgenommen)
    }
}
