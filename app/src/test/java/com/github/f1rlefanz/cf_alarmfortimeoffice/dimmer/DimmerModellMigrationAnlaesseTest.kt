package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * WANN die Dimmer-Modellmigration laufen darf - und wann ausdrücklich NICHT.
 *
 * Die Übersetzung selbst prüft `DimmerModellMigrationTest` (Zeitleiste gegen Zeitleiste). Hier
 * geht es um die zwei Fragen daneben, die beide einen lautlos ausgeschalteten Dimmer erzeugen
 * können:
 *
 *  - **Erreicht sie den Nutzer überhaupt?** `dim_enabled` ist ein NEUER Schlüssel mit Default
 *    `false`; bis die Migration lief, liefert `computeWindows()` eine leere Fensterliste. Wer die
 *    App nach einem Play-Auto-Update tagelang nicht öffnet, wurde deshalb ab der ersten Nacht nach
 *    dem Update nicht mehr gedimmt — und bei DND-Modus 1 fiel „Nicht stören" gleich mit weg.
 *  - **Läuft sie zu früh?** Vor der ersten Entsperrung liefert der CE-Store still LEERE
 *    Preferences. Eine Migration darüber sähe eine leere Alt-Konfiguration, schriebe
 *    `dim_enabled = false` und setzte den Marker — der Dimmer bliebe für immer aus.
 */
class DimmerModellMigrationAnlaesseTest {

    private val KEY_MARKER = intPreferencesKey(DimmerModellMigration.KEY_MARKER_NAME)
    private val KEY_DIM_ON = booleanPreferencesKey("dim_enabled")
    private val KEY_WELLNESS = booleanPreferencesKey("dim_wellness_enabled")
    private val KEY_WINDDOWN = intPreferencesKey("dim_winddown_min")

    /** Minimaler, echter In-Memory-DataStore - wie in [DimRuleRepositoryTest]. */
    private class FakeStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    /** Ein Bestandsgerät mit eingeschalteter Wellness-Quelle und 90 Minuten Wind-down. */
    private fun altGeraet() = FakeStore(
        mutablePreferencesOf().apply {
            this[KEY_WELLNESS] = true
            this[KEY_WINDDOWN] = 90
        }
    )

    private fun migration(store: DataStore<Preferences>, entsperrt: Boolean): DimmerModellMigration {
        val userManager = mock<UserManager>()
        whenever(userManager.isUserUnlocked).thenReturn(entsperrt)
        val context = mock<Context>()
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)
        return DimmerModellMigration(
            context = context,
            dataStore = store,
            dimRuleUseCase = DimRuleUseCase(DimRuleRepository(store)),
            dimSchedule = mock()
        )
    }

    @Test
    fun `auf einem entsperrten Bestandsgeraet uebersetzt sie und setzt den Marker`() = runTest {
        val store = altGeraet()

        assertTrue(migration(store, entsperrt = true).migriereEinmalig())

        val prefs = store.data.first()
        assertEquals(true, prefs[KEY_DIM_ON])
        assertEquals(DimmerModellMigration.STAND, prefs[KEY_MARKER])
        val regel = DimRuleUseCase(DimRuleRepository(store)).getAllRules().single()
        assertEquals(DimAnchor.ALARM, regel.windows.single().startAnchor)
        assertEquals(-90, regel.windows.single().startOffsetMinutes)
    }

    /**
     * DAS ENTSPERRUNGS-GATE. Ohne es hätte ein Aufrufer aus einer Hintergrundkette (Wartung, Boot)
     * die Alt-Konfiguration eines gesperrten Geräts als „nichts eingestellt" gelesen, den Dimmer
     * abgeschaltet und den Marker gesetzt - unumkehrbar, weil danach niemand mehr nachsieht.
     */
    @Test
    fun `vor der ersten Entsperrung wird NICHTS geschrieben`() = runTest {
        val store = altGeraet()

        assertFalse(migration(store, entsperrt = false).migriereEinmalig())

        val prefs = store.data.first()
        assertNull("Kein Marker - der naechste Lauf muss es erneut versuchen", prefs[KEY_MARKER])
        assertNull("Und schon gar kein abgeschalteter Dimmer", prefs[KEY_DIM_ON])
    }

    // --- Der dritte Anlass: eine importierte ALTE Konfiguration ---------------------------------

    /**
     * DER GERÄTEWECHSEL. Auf dem Zielgerät steht der Marker längst — schon der erste Start einer
     * frischen Installation setzt ihn (leere Prefs → nichts zu übersetzen). Der Import schreibt
     * danach die alten Schlüssel roh in den Store, und ohne diesen Weg liest sie niemand mehr:
     * `dim_enabled` gibt es in einer Datei von vor dem Umbau nicht, es bliebe auf `false`. Es
     * dimmt nichts, die eingestellte Nachtruhe ist unsichtbar verloren — und legt der Nutzer den
     * Hauptschalter um, werden die auf dem Altgerät INERTEN Regeln scharf.
     */
    @Test
    fun `eine importierte Alt-Konfiguration wird nachtraeglich uebersetzt`() = runTest {
        // Frische Installation: erster Start setzt Marker und dim_enabled=false.
        val store = FakeStore(mutablePreferencesOf())
        migration(store, entsperrt = true).migriereEinmalig()
        assertEquals(false, store.data.first()[KEY_DIM_ON])

        // Danach der Import einer Datei aus der Zeit vor dem Umbau.
        store.updateData {
            it.toMutablePreferences().apply {
                this[KEY_WELLNESS] = true
                this[KEY_WINDDOWN] = 90
            }
        }

        assertTrue(
            migration(store, entsperrt = true)
                .migriereNachImport(setOf(KEY_WELLNESS.name, KEY_WINDDOWN.name))
        )

        assertEquals(
            "Die importierte Nachtruhe war sonst unsichtbar verloren",
            true, store.data.first()[KEY_DIM_ON]
        )
        assertEquals(
            -90,
            DimRuleUseCase(DimRuleRepository(store)).getAllRules().single()
                .windows.single().startOffsetMinutes
        )
    }

    /**
     * Die Gegenrichtung, und sie ist die gefährlichere: Eine Datei aus dem EIN-Modell trägt
     * `dim_enabled`. Ein Übersetzungslauf darüber läse die (fehlenden) alten Schlüssel als „alles
     * aus" und schaltete den frisch importierten Dimmer sofort wieder ab.
     */
    @Test
    fun `eine Datei aus dem Ein-Modell wird NICHT erneut uebersetzt`() = runTest {
        val store = FakeStore(mutablePreferencesOf().apply { this[KEY_DIM_ON] = true })
        store.updateData {
            it.toMutablePreferences().apply { this[KEY_MARKER] = DimmerModellMigration.STAND }
        }

        assertFalse(
            migration(store, entsperrt = true).migriereNachImport(setOf("dim_enabled", "dim_strength"))
        )

        assertEquals(true, store.data.first()[KEY_DIM_ON])
    }

    /** Und ein Import ganz ohne Dimmer-Schluessel laesst den lokalen Stand in Ruhe. */
    @Test
    fun `ein Import ohne Dimmer-Schluessel loest keine Migration aus`() {
        assertFalse(
            DimmerModellMigration.brauchtNachImportEineMigration(setOf("snooze_minutes", "dim_rules"))
        )
    }

    /**
     * DER ZWEITE ANLASS, strukturell geprüft (wie `Pruefrunde6R8RegelnTest` die R8-Regeln prüft):
     * Der 6h-Wartungslauf ist die einzige Kette, die auch den Nutzer erreicht, der die App nach
     * einem Play-Auto-Update gar nicht öffnet. Fällt dieser Aufruf weg, bleibt bei ihm
     * `dim_enabled` ungesetzt (Default `false`) - ab der ersten Nacht nach dem Update dimmt nichts
     * mehr, und bei DND-Modus 1 fällt „Nicht stören" gleich mit weg. Sichtbar wäre das nirgends.
     */
    @Test
    fun `der Wartungslauf stoesst die Migration an`() {
        val quelle = listOf(
            java.io.File("app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/service/AlarmMaintenanceService.kt"),
            java.io.File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/service/AlarmMaintenanceService.kt")
        ).firstOrNull { it.exists() }
            ?: error("AlarmMaintenanceService.kt nicht gefunden (${java.io.File(".").absolutePath})")

        assertTrue(
            "Ohne diesen Aufruf erreicht die Migration nur, wer die App oeffnet",
            quelle.readText().contains("dimmerModellMigration.migriereEinmalig()")
        )
    }

    /** Der Marker macht den zweiten Anlass zum No-op - egal welcher der beiden zuerst dran war. */
    @Test
    fun `ein zweiter Anlass schreibt nicht erneut`() = runTest {
        val store = altGeraet()
        migration(store, entsperrt = true).migriereEinmalig()
        // Der Nutzer schaltet den Dimmer danach selbst aus.
        store.updateData { it.toMutablePreferences().apply { this[KEY_DIM_ON] = false } }

        assertFalse(migration(store, entsperrt = true).migriereEinmalig())

        assertEquals(
            "Ein zweiter Lauf haette die Nutzerentscheidung ueberschrieben",
            false, store.data.first()[KEY_DIM_ON]
        )
    }
}
