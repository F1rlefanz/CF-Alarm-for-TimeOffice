package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmSchedulerTest
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarSyncTest
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.HueIntegrationTest
import com.github.f1rlefanz.cf_alarmfortimeoffice.security.SecurityTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Main Test Suite - Runs all critical tests
 * 
 * This test suite covers the most critical functionality:
 * 1. Alarm Scheduling - Core feature
 * 2. Calendar Synchronization - Data source
 * 3. Hue Integration - Value-added feature
 * 4. Security & Encryption - Data protection
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    StateSynchronisationTest::class,
    AlarmSchedulerTest::class,
    CalendarSyncTest::class,
    HueIntegrationTest::class,
    SecurityTest::class
)
class TestSuite {
    companion object {
        const val TEST_COVERAGE_TARGET = 70 // Target 70% code coverage for critical paths
        
        /**
         * Test execution priorities:
         * 1. CRITICAL: AlarmSchedulerTest - Must pass for release
         * 2. CRITICAL: SecurityTest - Must pass for data protection
         * 3. HIGH: CalendarSyncTest - Should pass for full functionality
         * 4. MEDIUM: HueIntegrationTest - Optional feature, can fail
         */
        fun getTestPriorities(): Map<String, String> {
            return mapOf(
                "AlarmSchedulerTest" to "CRITICAL",
                "SecurityTest" to "CRITICAL",
                "CalendarSyncTest" to "HIGH",
                "HueIntegrationTest" to "MEDIUM"
            )
        }
    }
}