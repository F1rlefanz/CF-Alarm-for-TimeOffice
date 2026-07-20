package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import androidx.core.content.UnusedAppRestrictionsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testet ausschliesslich die reine Zuordnung [UnusedAppRestrictionsHelper.needsPrompt] - welche
 * UnusedAppRestrictionsConstants-Status-Werte bedeuten "die Einschraenkung ist aktiv, Nutzer
 * sollte gefragt werden"? Die restlichen Funktionen des Helpers sind duenne Android-/DataStore-
 * Wrapper (kein Test-Overkill, siehe BatteryOptimizationHelper - dort gilt dieselbe Konvention).
 */
class UnusedAppRestrictionsHelperTest {

    @Test
    fun `ERROR braucht keinen Prompt (fail-open)`() {
        assertFalse(UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.ERROR))
    }

    @Test
    fun `FEATURE_NOT_AVAILABLE braucht keinen Prompt (Geraet unter API 30)`() {
        assertFalse(
            UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE)
        )
    }

    @Test
    fun `DISABLED braucht keinen Prompt (Nutzer bereits ausgenommen)`() {
        assertFalse(UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.DISABLED))
    }

    @Test
    fun `API_30_BACKPORT braucht einen Prompt`() {
        assertTrue(
            UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.API_30_BACKPORT)
        )
    }

    @Test
    fun `API_30 braucht einen Prompt`() {
        assertTrue(UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.API_30))
    }

    @Test
    fun `API_31 braucht einen Prompt`() {
        assertTrue(UnusedAppRestrictionsHelper.needsPrompt(UnusedAppRestrictionsConstants.API_31))
    }
}
