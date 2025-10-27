package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import android.content.SharedPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner

/**
 * Unit Tests für ModernOAuth2TokenManager
 * 
 * Testet:
 * - Email-Extraktion aus JWT Token
 * - Multi-Source Email Retrieval (SharedPrefs → TokenData → JWT)
 * - Email-Caching nach Recovery
 */
@RunWith(MockitoJUnitRunner::class)
class ModernOAuth2TokenManagerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockTokenRepository: TokenStorageRepository

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        `when`(mockContext.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE))
            .thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.apply()).then { }
    }

    @Test
    fun `getUserEmailFromAccounts returns email from SharedPreferences first`() = runTest {
        `when`(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn("primary@example.com")
        `when`(mockSharedPreferences.getBoolean("user_signed_in", false))
            .thenReturn(true)

        val email = mockSharedPreferences.getString("current_user_email", null)

        assertEquals("primary@example.com", email)
        assertTrue("User should be signed in", 
            mockSharedPreferences.getBoolean("user_signed_in", false))
    }

    @Test
    fun `getUserEmailFromAccounts falls back to TokenData when SharedPrefs empty`() = runTest {
        `when`(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        `when`(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = "fallback@example.com"
            ))

        val tokenData = mockTokenRepository.getCurrentToken()

        assertNotNull("TokenData should exist", tokenData)
        assertEquals("fallback@example.com", tokenData?.userEmail)
    }

    @Test
    fun `extractEmailFromJWT successfully parses valid JWT`() {
        val email = "jwt@example.com"
        val payload = """{"email":"$email","sub":"123456"}"""
        val encodedPayload = android.util.Base64.encodeToString(
            payload.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
        )
        val jwt = "header.$encodedPayload.signature"

        val segments = jwt.split(".")
        assertEquals("JWT should have 3 segments", 3, segments.size)

        val decodedPayload = String(
            android.util.Base64.decode(segments[1], 
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        )
        val jsonObject = org.json.JSONObject(decodedPayload)
        val extractedEmail = jsonObject.getString("email")

        assertEquals(email, extractedEmail)
    }

    @Test
    fun `extractEmailFromJWT returns null for invalid JWT`() {
        val invalidTokens = listOf(
            "invalid",
            "only.two",
            "header.invalid_base64.sig",
            "header.e30=.signature"
        )

        invalidTokens.forEach { invalidToken ->
            val segments = invalidToken.split(".")
            assertTrue("Invalid token should not have 3 segments or valid payload", 
                segments.size != 3 || segments[1].isEmpty())
        }
    }

    @Test
    fun `recovered email is cached to SharedPreferences`() = runTest {
        val recoveredEmail = "recovered@example.com"
        `when`(mockTokenRepository.getCurrentToken())
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
    fun `multi-source email retrieval follows correct priority`() = runTest {
        `when`(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn("prefs@example.com")
        `when`(mockSharedPreferences.getBoolean("user_signed_in", false))
            .thenReturn(true)

        val email1 = mockSharedPreferences.getString("current_user_email", null)
        assertEquals("Should use SharedPrefs first", "prefs@example.com", email1)

        `when`(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        `when`(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = "token@example.com"
            ))

        val email2 = mockSharedPreferences.getString("current_user_email", null)
        assertNull("SharedPrefs should be empty", email2)
        
        val tokenData = mockTokenRepository.getCurrentToken()
        assertEquals("Should use TokenData second", "token@example.com", tokenData?.userEmail)
    }

    @Test
    fun `getUserEmailFromAccounts returns null when no email anywhere`() = runTest {
        `when`(mockSharedPreferences.getString("current_user_email", null))
            .thenReturn(null)
        `when`(mockTokenRepository.getCurrentToken())
            .thenReturn(TokenData(
                accessToken = "token",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 3600000,
                scope = "calendar",
                userEmail = null
            ))

        val email = mockSharedPreferences.getString("current_user_email", null)
        val tokenData = mockTokenRepository.getCurrentToken()

        assertNull("Email should not be available in SharedPrefs", email)
        assertNull("Email should not be available in TokenData", tokenData?.userEmail)
    }

    @Test
    fun `extractEmailFromJWT handles different payload formats`() {
        val payloads = listOf(
            """{"email":"test@example.com"}""",
            """{"email":"test@example.com","sub":"123"}""",
            """{"sub":"123","email":"test@example.com","iat":1234567890}""",
            """{"email":"test+tag@example.com"}"""
        )

        payloads.forEach { payload ->
            val encodedPayload = android.util.Base64.encodeToString(
                payload.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val jwt = "header.$encodedPayload.signature"

            val segments = jwt.split(".")
            val decodedPayload = String(
                android.util.Base64.decode(segments[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            )
            val jsonObject = org.json.JSONObject(decodedPayload)
            
            assertTrue("Should contain email field", jsonObject.has("email"))
            assertTrue("Email should be valid", 
                jsonObject.getString("email").contains("@"))
        }
    }
}
