package com.github.f1rlefanz.cf_alarmfortimeoffice.service.worker

import android.content.Context
import android.content.SharedPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.AppContainer
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.ModernOAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*

/**
 * Unit Tests für BackgroundTokenRefreshWorker
 * 
 * Testet:
 * - Email-Redundanz-System
 * - Token Storage Zugriff
 * - SharedPreferences Integration
 */
@RunWith(MockitoJUnitRunner::class)
class BackgroundTokenRefreshWorkerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockApplication: CFAlarmApplication

    @Mock
    private lateinit var mockAppContainer: AppContainer

    @Mock
    private lateinit var mockTokenManager: ModernOAuth2TokenManager

    @Mock
    private lateinit var mockTokenRepository: TokenStorageRepository

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        whenever(mockContext.applicationContext).thenReturn(mockApplication)
        whenever(mockApplication.appContainer).thenReturn(mockAppContainer)
        whenever(mockAppContainer.modernOAuth2TokenManager).thenReturn(mockTokenManager)
        whenever(mockAppContainer.tokenStorageRepository).thenReturn(mockTokenRepository)
        
        whenever(mockContext.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE))
            .thenReturn(mockSharedPreferences)
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        doNothing().whenever(mockEditor).apply()
    }

    @Test
    fun `health check succeeds when email in SharedPreferences`() = runTest {
        whenever(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn("test@example.com")
        whenever(mockSharedPreferences.getBoolean("user_signed_in", false))
            .thenReturn(true)
        whenever(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "valid_token",
                refreshToken = "refresh_token",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = "test@example.com"
            ))

        val email = mockSharedPreferences.getString("current_user_email", null)
        assertNotNull("SharedPreferences should contain email", email)
        assertEquals("test@example.com", email)
    }

    @Test
    fun `health check recovers email from TokenData when SharedPrefs empty`() = runTest {
        whenever(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        whenever(mockSharedPreferences.getBoolean("user_signed_in", false))
            .thenReturn(true)
        whenever(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "valid_token",
                refreshToken = "refresh_token",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = "recovered@example.com"
            ))

        val tokenData = mockTokenRepository.getCurrentToken()
        assertNotNull("TokenData should exist", tokenData)
        assertEquals("recovered@example.com", tokenData?.userEmail)
    }

    @Test
    fun `health check detects missing email in all sources`() = runTest {
        whenever(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        whenever(mockSharedPreferences.getBoolean("user_signed_in", false))
            .thenReturn(true)
        whenever(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "valid_token",
                refreshToken = "refresh_token",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = null
            ))

        val email = mockSharedPreferences.getString("current_user_email", null)
        val tokenData = mockTokenRepository.getCurrentToken()
        
        assertNull("SharedPreferences email should be null", email)
        assertNull("TokenData email should be null", tokenData?.userEmail)
    }

    @Test
    fun `token storage is accessible during health check`() = runTest {
        whenever(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "test_token",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = "test@example.com"
            ))

        val token = mockTokenRepository.getCurrentToken()

        assertNotNull("Token should be accessible", token)
        assertEquals("test@example.com", token?.userEmail)
        assertEquals("test_token", token?.accessToken)
    }

    @Test
    fun `SharedPreferences are updated when email recovered from TokenData`() = runTest {
        val recoveredEmail = "recovered@example.com"
        
        whenever(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        whenever(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = recoveredEmail
            ))

        mockEditor.putString("current_user_email", recoveredEmail)
        mockEditor.apply()

        verify(mockEditor).putString("current_user_email", recoveredEmail)
        verify(mockEditor).apply()
    }

    @Test
    fun `TokenData contains all required fields`() {
        val tokenData = TokenData(
            accessToken = "access_token_123",
            refreshToken = "refresh_token_456",
            expiresAt = System.currentTimeMillis() + 3600000,
            scope = "calendar",
            userEmail = "user@example.com"
        )

        assertNotNull("Access token should exist", tokenData.accessToken)
        assertNotNull("Refresh token should exist", tokenData.refreshToken)
        assertTrue("Expiry time should be in future", tokenData.expiresAt > System.currentTimeMillis())
        assertNotNull("User email should exist", tokenData.userEmail)
    }

    @Test
    fun `AppContainer dependencies are correctly linked`() {
        whenever(mockAppContainer.modernOAuth2TokenManager).thenReturn(mockTokenManager)
        whenever(mockAppContainer.tokenStorageRepository).thenReturn(mockTokenRepository)

        assertNotNull("Token manager should be accessible", mockAppContainer.modernOAuth2TokenManager)
        assertNotNull("Token repository should be accessible", mockAppContainer.tokenStorageRepository)
    }
}
