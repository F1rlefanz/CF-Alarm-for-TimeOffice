package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Haelt die beiden Eigenschaften des [DataStoreTokenRepository.observe]-Flows fest, an denen der
 * Token-Verlust-Waechter haengt. Der Flow selbst braucht einen echten verschluesselten DataStore
 * (Tink/Keystore) und ist im JVM-Test nicht konstruierbar - geprueft wird deshalb die
 * Operator-Kette in genau der Form, in der sie dort steht.
 *
 * REALER BEFUND (Pruefrunde 14.08.2026, Dimension "Auth / Token-Rotation"): Dort stand ein
 * blankes `.catch { emit(emptyPreferences()) }`. Das hat zwei Fehler auf einmal:
 *
 *  1. Es emittiert ein FALSCHES NEGATIVES SIGNAL. `AuthViewModel.observeTokenLoss()` wertet
 *     ausschliesslich "kein Token" aus und liest es als "Google hat den Zugriff entzogen" -
 *     ein einmaliger IO-Fehler haette einem Nutzer mit intaktem Token eine Neuanmeldung
 *     aufgedraengt.
 *  2. `.catch` BEENDET den Flow (faengt, emittiert, laesst normal abschliessen). Der Waechter
 *     war danach fuer die gesamte Prozesslaufzeit tot - ein SPAETERER, echter Token-Verlust
 *     wurde nie mehr bemerkt. Dieselbe Fehlerklasse, die bei
 *     `CalendarSelectionRepository` bereits mit `retryWhen` behoben wurde.
 */
class TokenObserveResilienceTest {

    /** Die Operator-Kette aus observe(), auf einem beliebigen Upstream. */
    private fun <T> Flow<T>.asObserveChain(attempts: Long): Flow<T> =
        retryWhen { _, attempt ->
            if (attempt >= attempts) {
                false
            } else {
                delay(1)
                true
            }
        }.catch { /* bewusst OHNE emit - siehe Punkt 1 */ }

    @Test
    fun `ein transienter Lesefehler heilt sich und liefert den echten Wert`() = runTest {
        var attempt = 0
        val upstream = flow {
            attempt++
            if (attempt == 1) throw IOException("einmaliger Lesefehler")
            emit("token")
        }

        val emissions = upstream.asObserveChain(attempts = 5).toList()

        assertEquals(
            "Nach einem transienten Fehler muss der echte Wert ankommen, nicht 'kein Token'",
            listOf("token"),
            emissions
        )
    }

    @Test
    fun `ein dauerhafter Lesefehler emittiert KEIN falsches Verlust-Signal`() = runTest {
        val upstream = flow<String> { throw IOException("dauerhaft nicht lesbar") }

        val emissions = upstream.asObserveChain(attempts = 3).toList()

        assertTrue(
            "Kein Signal ist richtiger als ein falsches: ein emittiertes 'kein Token' loest " +
                "einen Zwangs-Zustimmungsdialog aus, obwohl der Token intakt sein kann",
            emissions.isEmpty()
        )
    }

    @Test
    fun `der Flow ueberlebt einen Fehler und liefert danach weitere Werte`() = runTest {
        var attempt = 0
        val upstream = flow {
            attempt++
            if (attempt == 1) throw IOException("einmaliger Lesefehler")
            emit("token")
            emit("kein-token")
        }

        val emissions = upstream.asObserveChain(attempts = 5).toList()

        assertEquals(
            "Ein SPAETERER echter Token-Verlust muss weiterhin ankommen - genau das ging " +
                "verloren, als ein blosses .catch{} den Flow beendete",
            listOf("token", "kein-token"),
            emissions
        )
    }
}
