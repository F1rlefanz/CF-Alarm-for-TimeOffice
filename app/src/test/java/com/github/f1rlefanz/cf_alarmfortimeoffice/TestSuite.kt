package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmSchedulerTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test-Suite für die real getesteten Kernpfade.
 *
 * Hinweis: Die früheren Calendar-/Hue-/Security-"Tests" prüften ausschließlich
 * datei-interne Stubs (keine Produktionslogik) und wurden entfernt – einer davon
 * (HueIntegrationTest) enthielt zudem Assertions, die nie zutrafen. Echte
 * Unit-Tests gegen die realen Klassen (CalendarRepository, HueApiClient,
 * TinkEncryptionHelper) sind ein sinnvoller Folge-Schritt.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    StateSynchronisationTest::class,
    AlarmSchedulerTest::class
)
class TestSuite
