package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ein 403 der Calendar-API kann drei Dinge heissen - und seit v1.43.6 haengt an der Einstufung,
 * ob die App "Kalender nicht gefunden" meldet und das Entfernen anbietet. Abruf- und
 * Kontingentgrenzen sind voruebergehend (Google: "retry with backoff") und duerfen NICHT als
 * fehlender Kalender gelten.
 */
class VoruebergehendeAblehnungTest {

    @Test
    fun `Abruf- und Kontingentgrenzen sind voruebergehend`() {
        listOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "quotaExceeded",
            "dailyLimitExceeded",
            "calendarUsageLimitsExceeded",
            "RATELIMITEXCEEDED"
        ).forEach { grund ->
            assertTrue(grund, istVoruebergehendeAblehnung(listOf(grund)))
        }
    }

    @Test
    fun `Scope-Mangel und fehlender Zugriff sind es nicht`() {
        listOf(
            emptyList(),
            listOf("insufficientPermissions"),
            listOf("forbidden"),
            listOf("requiredAccessLevel"),
            listOf("forbiddenForNonOrganizer")
        ).forEach { gruende ->
            assertFalse("$gruende", istVoruebergehendeAblehnung(gruende))
        }
    }
}
