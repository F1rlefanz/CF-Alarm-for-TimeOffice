package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import android.content.Context
import android.os.UserManager
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Context-Attrappen fuer [AlarmRepository]-Tests.
 *
 * Das Repository fragt den `UserManager`, weil ein Read auf dem CREDENTIAL-ENCRYPTED DataStore vor
 * der ersten Entsperrung NICHT wirft, sondern still leere Preferences liefert - was von "keine
 * Alarme" nicht zu unterscheiden waere (siehe die Begruendung an `loadedWhileUnlocked`). Fuer die
 * meisten Tests ist "entsperrt" der richtige Zustand; [locked] existiert fuer die Tests, die genau
 * diese Falle festhalten.
 */
internal object AlarmRepoTestContext {

    fun unlocked(): Context = withUnlocked(true)

    fun locked(): Context = withUnlocked(false)

    private fun withUnlocked(unlocked: Boolean): Context {
        // Die innere Attrappe ZUERST fertig stubben. Wird sie innerhalb des `thenReturn(...)` des
        // aeusseren Stubs aufgebaut, sieht Mockito eine unfertige Stubbing-Kette
        // (UnfinishedStubbingException) - genau daran sind alle Tests dieser Datei zuerst
        // gescheitert.
        val userManager = mock<UserManager>()
        whenever(userManager.isUserUnlocked).thenReturn(unlocked)

        val context = mock<Context>()
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)
        return context
    }
}
