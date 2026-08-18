package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.CalendarPermissionOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.PendingAuthStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Pruefrunde 6, Befunde 3 und 7 - beide haengen am selben onActivityResult-Pfad.
 *
 * BEFUND 3: [BatteryOptimizationHelper.REQUEST_CODE_BATTERY_EXEMPTION] war zahlengleich mit
 * [OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION] (beide 1001). Beide starten ueber
 * startActivityForResult DERSELBEN MainActivity; deren einzige Verzweigung prueft auf den
 * Kalender-Code. Wer die Akku-Ausnahme erteilte, bekam danach den Toast "Kalenderzugriff wurde
 * verweigert" - und der Merker einer echt schwebenden Kalender-Autorisierung wurde dabei verworfen.
 *
 * BEFUND 7: Der Merker lag nur im Speicher. Stirbt der Prozess, waehrend der Zustimmungsdialog der
 * Play Services vorne steht, stellt Android das Activity-Result im neuen Prozess zu - der Merker
 * ist dann weg, und eine ERTEILTE Zustimmung wurde als "verweigert" gemeldet.
 *
 * Gemeinsame Zusicherung: "unbekannt" ist ein eigener Zustand. Er heisst "nachsehen, ob ein Token
 * da ist" - weder "abgelehnt" noch "erfolgreich".
 */
class Pruefrunde6AuthPermissionResultTest {

    /** Merker-Ablage im Speicher - steht fuer die persistente SharedPreferences-Umsetzung. */
    private class FakePendingAuthStore(private var email: String? = null) : PendingAuthStore {
        var consumeCount = 0
            private set

        override fun remember(email: String) {
            this.email = email
        }

        override fun consume(): String? {
            consumeCount++
            val current = email
            email = null
            return current
        }

        fun peek(): String? = email
    }

    private class FakeTokenRepository(private var token: TokenData? = null) : TokenRepository {
        override suspend fun get(): TokenData? = token
        override suspend fun save(token: TokenData): Result<Unit> {
            this.token = token
            return Result.success(Unit)
        }

        override suspend fun clear(): Result<Unit> {
            token = null
            return Result.success(Unit)
        }

        override fun observe(): Flow<TokenData?> = flowOf(token)
    }

    private fun gueltigesToken() = TokenData(
        accessToken = "access-token",
        expiresAt = System.currentTimeMillis() + 2 * 60 * 60 * 1000L,
        scope = "https://www.googleapis.com/auth/calendar.readonly",
        googleAccountEmail = "nutzer@example.com"
    )

    private fun manager(
        store: PendingAuthStore,
        repository: TokenRepository = FakeTokenRepository()
    ) = OAuth2TokenManager(mock<Context>(), repository, store)

    // ------------------------------------------------------------------ Befund 3

    @Test
    fun `Akku-Ausnahme und Kalender-Autorisierung duerfen sich keinen Request-Code teilen`() {
        assertTrue(
            "Beide Codes laufen ueber startActivityForResult DERSELBEN MainActivity; sind sie " +
                "gleich, wird die Rueckkehr aus dem Akku-Dialog als Kalender-Ergebnis verarbeitet",
            BatteryOptimizationHelper.REQUEST_CODE_BATTERY_EXEMPTION !=
                OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION
        )
    }

    @Test
    fun `die Rueckkehr aus dem Akku-Dialog entwaffnet eine schwebende Kalender-Autorisierung nicht`() =
        runTest {
            val store = FakePendingAuthStore("nutzer@example.com")
            val tokenManager = manager(store)

            val outcome = tokenManager.handlePermissionResult(
                BatteryOptimizationHelper.REQUEST_CODE_BATTERY_EXEMPTION,
                Activity.RESULT_CANCELED
            )

            assertEquals(
                "Ein fremdes Ergebnis ist keine Ablehnung des Kalenderzugriffs",
                CalendarPermissionOutcome.UNKNOWN,
                outcome
            )
            assertEquals(
                "Der Merker der schwebenden Autorisierung darf dabei nicht verbraucht werden",
                "nutzer@example.com",
                store.peek()
            )
            assertEquals(0, store.consumeCount)
        }

    // ------------------------------------------------------------------ Befund 7

    @Test
    fun `der Merker ueberlebt den Speicherverlust und wird genau einmal gelesen`() {
        val store = FakePendingAuthStore("nutzer@example.com")
        val tokenManager = manager(store)

        // Kein Speicherwert gesetzt - genau die Lage nach einem Prozesstod.
        assertEquals("nutzer@example.com", tokenManager.consumePendingAuthEmail())
        assertNull("Nach dem Konsum darf kein Rest zurueckbleiben", store.peek())
        assertNull("Ein zweiter Aufruf darf nichts mehr liefern", tokenManager.consumePendingAuthEmail())
    }

    @Test
    fun `eine nach Prozesstod erteilte Zustimmung wird nicht als Ablehnung gemeldet`() = runTest {
        val store = FakePendingAuthStore(null)   // Merker verloren
        val tokenManager = manager(store, FakeTokenRepository(gueltigesToken()))

        val outcome = tokenManager.handlePermissionResult(
            OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION,
            Activity.RESULT_OK
        )

        assertEquals(
            "Ohne Merker, aber mit gueltigem Token ist die Zustimmung erteilt - der wartende " +
                "Callback fehlt jedoch, die Oberflaeche muss nachgezogen werden",
            CalendarPermissionOutcome.GRANTED_AFTER_RESTART,
            outcome
        )
    }

    @Test
    fun `ohne Merker und ohne Token ist das Ergebnis unbekannt, nicht abgelehnt`() = runTest {
        val store = FakePendingAuthStore(null)
        val tokenManager = manager(store, FakeTokenRepository(null))

        val outcome = tokenManager.handlePermissionResult(
            OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION,
            Activity.RESULT_OK
        )

        assertEquals(
            "Kein Merker und kein Token heisst: wir wissen es nicht - und behaupten deshalb weder " +
                "Erfolg noch Ablehnung",
            CalendarPermissionOutcome.UNKNOWN,
            outcome
        )
    }

    @Test
    fun `eine echte Ablehnung bleibt eine Ablehnung und raeumt den Merker weg`() = runTest {
        val store = FakePendingAuthStore("nutzer@example.com")
        val tokenManager = manager(store)

        val outcome = tokenManager.handlePermissionResult(
            OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION,
            Activity.RESULT_CANCELED
        )

        assertEquals(CalendarPermissionOutcome.DENIED, outcome)
        assertNull(
            "Sonst beantwortet ein verwaister Merker den naechsten Durchlauf",
            store.peek()
        )
    }
}
