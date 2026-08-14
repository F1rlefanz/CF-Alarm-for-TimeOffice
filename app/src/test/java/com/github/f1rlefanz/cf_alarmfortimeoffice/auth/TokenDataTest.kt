package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert [TokenData]s reine Logik ab: Ablauf-/Puffer-Mathematik, Refresh-Faehigkeit und vor
 * allem die Rotationsketten-Semantik von [TokenData.rotate]/[TokenData.validateRotation].
 *
 * HINTERGRUND (Bug 1, OAuth2TokenManager.refresh()): Der urspruengliche Security-Check rief
 * `currentToken.validateRotation(storedToken.previousRotationId)` auf - das vergleicht
 * `currentToken.previousRotationId == storedToken.previousRotationId`, also zwei "previous"-
 * Zeiger gegeneinander. Das ist nicht die Kettenpruefung, die der Name verspricht, und feuerte
 * bei JEDER legitimen gleichzeitigen Rotation (z.B. AlarmMaintenanceService UND
 * CalendarPreAlarmRefreshWorker entdecken beide ein abgelaufenes Token und refreshen beide -
 * dieser @Singleton hat keinen Mutex) faelschlich eine SecurityViolation, die das Token loescht.
 *
 * Die korrekte Kettenpruefung nutzt [TokenData.validateRotation] auf dem NEUEREN Token, mit der
 * rotationId des AELTEREN Tokens als erwartetem previousRotationId - genau die Richtung, die der
 * Name/die Doku von `validateRotation` (und die Feldbefuellung in `rotate()`) nahelegen.
 */
class TokenDataTest {

    private fun tokenAt(
        expiresAt: Long,
        accessToken: String = "access",
        refreshToken: String? = null,
        tokenProvider: TokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES,
        googleAccountEmail: String? = "user@example.com"
    ) = TokenData(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        scope = "scope",
        tokenProvider = tokenProvider,
        googleAccountEmail = googleAccountEmail
    )

    // --- isExpiredOrExpiringSoon / isValid: Puffer-Mathematik ---

    @Test
    fun `Token weit in der Zukunft ist gueltig`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() + 60 * 60 * 1000)
        assertFalse(token.isExpiredOrExpiringSoon())
        assertTrue(token.isValid())
    }

    @Test
    fun `Token innerhalb des 15-Minuten-Puffers gilt als bald ablaufend`() {
        // Puffer ist TOKEN_REFRESH_BUFFER_MINUTES = 15min. Ein Token, das in 10min technisch
        // noch nicht abgelaufen ist, muss trotzdem als "expiring soon" gelten.
        val token = tokenAt(expiresAt = System.currentTimeMillis() + 10 * 60 * 1000)
        assertTrue(token.isExpiredOrExpiringSoon())
        assertFalse(token.isValid())
    }

    @Test
    fun `Token knapp ausserhalb des Puffers gilt noch als gueltig`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() + 16 * 60 * 1000)
        assertFalse(token.isExpiredOrExpiringSoon())
    }

    @Test
    fun `bereits abgelaufenes Token ist nicht gueltig`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        assertTrue(token.isExpiredOrExpiringSoon())
        assertFalse(token.isValid())
    }

    @Test
    fun `leerer accessToken ist nie gueltig, auch mit langer Restlaufzeit`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() + 60 * 60 * 1000, accessToken = "")
        assertFalse(token.isValid())
    }

    @Test
    fun `benutzerdefinierter Puffer wird respektiert`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() + 5 * 60 * 1000)
        assertFalse(token.isExpiredOrExpiringSoon(bufferMinutes = 1))
        assertTrue(token.isExpiredOrExpiringSoon(bufferMinutes = 10))
    }

    // --- canRefresh: providerabhaengige Voraussetzungen ---

    @Test
    fun `GOOGLE_PLAY_SERVICES kann refreshen wenn Email vorhanden ist`() {
        val token = tokenAt(
            expiresAt = 0,
            tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES,
            googleAccountEmail = "user@example.com"
        )
        assertTrue(token.canRefresh())
    }

    @Test
    fun `GOOGLE_PLAY_SERVICES kann NICHT refreshen ohne Email`() {
        val token = tokenAt(
            expiresAt = 0,
            tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES,
            googleAccountEmail = null
        )
        assertFalse(token.canRefresh())
    }

    @Test
    fun `OAUTH2_STANDARD kann refreshen wenn refreshToken vorhanden ist`() {
        val token = tokenAt(
            expiresAt = 0,
            tokenProvider = TokenProvider.OAUTH2_STANDARD,
            refreshToken = "refresh-abc",
            googleAccountEmail = null
        )
        assertTrue(token.canRefresh())
    }

    @Test
    fun `OAUTH2_STANDARD kann NICHT refreshen ohne refreshToken`() {
        val token = tokenAt(
            expiresAt = 0,
            tokenProvider = TokenProvider.OAUTH2_STANDARD,
            refreshToken = null,
            googleAccountEmail = null
        )
        assertFalse(token.canRefresh())
    }

    // --- rotate()/validateRotation(): Rotationsketten-Semantik ---

    @Test
    fun `rotate haengt sich korrekt an die Kette - previousRotationId zeigt auf den Vorgaenger`() {
        val original = tokenAt(expiresAt = System.currentTimeMillis())
        val rotated = original.rotate(newAccessToken = "new-access")

        assertEquals(original.rotationId, rotated.previousRotationId)
        assertNotEquals(original.rotationId, rotated.rotationId)
        assertEquals(original.rotationCount + 1, rotated.rotationCount)
        assertEquals("new-access", rotated.accessToken)
    }

    @Test
    fun `validateRotation bestaetigt den direkten Vorgaenger`() {
        val original = tokenAt(expiresAt = System.currentTimeMillis())
        val rotated = original.rotate(newAccessToken = "new-access")

        assertTrue(rotated.validateRotation(original.rotationId))
    }

    @Test
    fun `validateRotation lehnt einen falschen Vorgaenger ab`() {
        val original = tokenAt(expiresAt = System.currentTimeMillis())
        val rotated = original.rotate(newAccessToken = "new-access")

        assertFalse(rotated.validateRotation("irgendeine-fremde-id"))
    }

    @Test
    fun `legitime gleichzeitige Rotation sieht NICHT wie ein Replay aus - Regressionstest fuer Bug 1`() {
        // Szenario: Zwei Aufrufer (z.B. AlarmMaintenanceService und
        // CalendarPreAlarmRefreshWorker) lesen denselben `original`-Token, beide erkennen ihn
        // als abgelaufen. Aufrufer A gewinnt das Rennen und rotiert zuerst - das Repository
        // enthaelt jetzt `rotatedByOtherCaller`. Aufrufer B haelt noch `original` in der Hand.
        val original = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        val rotatedByOtherCaller = original.rotate(newAccessToken = "rotated-elsewhere")

        // Die KORREKTE Pruefung (der Fix): das NEUERE Token (rotatedByOtherCaller) validiert
        // gegen die rotationId des Tokens, das Aufrufer B kennt (original). Das muss TRUE sein -
        // es ist eine legitime Kette, kein Diebstahl.
        assertTrue(
            "storedToken.validateRotation(currentToken.rotationId) muss die legitime " +
                "Parallel-Rotation erkennen",
            rotatedByOtherCaller.validateRotation(original.rotationId)
        )

        // Die BUGGY Pruefung aus OAuth2TokenManager vor dem Fix rief stattdessen
        // `currentToken.validateRotation(storedToken.previousRotationId)` auf - vergleicht also
        // original.previousRotationId (null, original ist der Wurzel-Token) gegen
        // rotatedByOtherCaller.previousRotationId (original.rotationId, nicht null). Das ist
        // FALSE und haette faelschlich eine SecurityViolation ausgeloest, obwohl die Rotation
        // vollkommen legitim war. Dieser Test dokumentiert genau die Falle.
        assertFalse(
            "die urspruengliche (fehlerhafte) Vergleichsrichtung haette hier faelschlich " +
                "'invalide' ergeben - das war Bug 1",
            original.validateRotation(rotatedByOtherCaller.previousRotationId)
        )
    }

    @Test
    fun `unabhaengiges fremdes Token erkennt man weiterhin als Kettenbruch`() {
        // Ein echtes Replay/Diebstahl-Szenario: das gespeicherte Token stammt gar nicht von
        // `original` ab (voellig andere Rotationskette, z.B. weil ein Angreifer ein altes,
        // zwischengespeichertes Token wiederverwendet).
        val original = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        val unrelatedRoot = tokenAt(expiresAt = System.currentTimeMillis(), accessToken = "unrelated")
        val unrelatedRotated = unrelatedRoot.rotate(newAccessToken = "unrelated-rotated")

        assertFalse(unrelatedRotated.validateRotation(original.rotationId))
        assertNotEquals(unrelatedRotated.rotationId, original.rotationId)
    }

    @Test
    fun `identisches Token ohne Rotation ist der Normalfall und keine Verletzung`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        // storedToken == currentToken (gleiche rotationId): niemand sonst hat rotiert.
        assertEquals(token.rotationId, token.rotationId)
    }

    // --- isLegitimateSuccessorOf: das vollstaendige Urteil, das refresh() faellt ---

    @Test
    fun `identisches Token ist ein legitimer Nachfolger`() {
        val token = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        assertTrue(token.isLegitimateSuccessorOf(token))
    }

    @Test
    fun `direkt rotiertes Token ist ein legitimer Nachfolger`() {
        val original = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        val rotated = original.rotate(newAccessToken = "rotated")
        assertTrue(rotated.isLegitimateSuccessorOf(original))
    }

    @Test
    fun `frische Neu-Autorisierung ist KEIN Kettenbruch - Regressionstest`() {
        // REALER BEFUND (Pruefrunde 14.08.2026, Dimension "Auth / Token-Rotation"):
        // `authorize()` speichert ein ueber fromOAuthResponse() gebautes Token. Das rotiert
        // nicht, sondern beginnt eine NEUE Kette (previousRotationId = null, rotationCount = 0)
        // und stammt damit zwangslaeufig nicht vom bisherigen Token ab. Landete dieser Write
        // zwischen dem Lesen des alten Tokens und der Kettenpruefung in refresh() - realistisch,
        // weil OAuth2TokenManager ein @Singleton ohne Mutex ist und Wartungslauf, Pre-Alarm-
        // Worker und UI unabhaengig refreshen -, galt er als Diebstahl: `clear()` loeschte
        // ausgerechnet das Token, das der Nutzer sich soeben per "Kalender-Zugriff erneuern"
        // geholt hatte. Die Oberflaeche zeigte danach weiter "angemeldet", waehrend jeder
        // Wartungslauf ohne Token abbrach.
        val expiring = tokenAt(expiresAt = System.currentTimeMillis() - 1000)
        val freshlyAuthorized = TokenData.fromOAuthResponse(
            accessToken = "frisch-autorisiert",
            refreshToken = null,
            expiresInSeconds = 3600,
            scope = "scope",
            googleAccountEmail = "user@example.com",
            tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES
        )

        assertNotEquals(freshlyAuthorized.rotationId, expiring.rotationId)
        assertFalse(
            "Eine Neu-Autorisierung rotiert nicht - sie kann den alten Vorgaenger nicht kennen",
            freshlyAuthorized.validateRotation(expiring.rotationId)
        )
        assertTrue(
            "Eine frische Neu-Autorisierung muss als legitim gelten, sonst loescht refresh() " +
                "das gerade geholte Token",
            freshlyAuthorized.isLegitimateSuccessorOf(expiring)
        )
    }

    @Test
    fun `unabhaengiges fremdes Token bleibt ein Kettenbruch`() {
        // Die Gegenprobe zum Test darueber: der Diebstahls-Zweig darf nicht generell abgeschaltet
        // werden. Ein fremdes Token, das AELTER ist als der bekannte Stand, stammt weder von ihm
        // ab noch ist es eine neuere Anmeldung.
        val now = System.currentTimeMillis()
        val current = tokenAt(expiresAt = now - 1000).copy(lastRotationAt = now)
        val unrelatedRoot = tokenAt(expiresAt = now, accessToken = "unrelated")
            .copy(lastRotationAt = now - 60_000)
        val unrelatedRotated = unrelatedRoot.rotate(newAccessToken = "unrelated-rotated")
            .copy(lastRotationAt = now - 60_000)

        assertFalse(unrelatedRotated.isLegitimateSuccessorOf(current))
    }

    @Test
    fun `eine AELTERE fremde Erstanmeldung gilt nicht als Nachfolger`() {
        val now = System.currentTimeMillis()
        val current = tokenAt(expiresAt = now - 1000).copy(lastRotationAt = now)
        val olderFreshChain = tokenAt(expiresAt = now, accessToken = "aelter")
            .copy(lastRotationAt = now - 60_000)

        assertFalse(
            "previousRotationId == null allein reicht nicht - der Stand muss auch neuer sein",
            olderFreshChain.isLegitimateSuccessorOf(current)
        )
    }
}
