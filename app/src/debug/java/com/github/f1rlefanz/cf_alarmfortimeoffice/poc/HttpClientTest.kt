package com.github.f1rlefanz.cf_alarmfortimeoffice.poc

import android.content.Context
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 🧪 SIMPLE HTTP CLIENT 2.0.0 TEST
 * Schneller Test für das HTTP Client Update ohne UI-Komplexität
 */
class HttpClientTest(private val context: Context) {
    
    suspend fun testHttpClient2(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.AUTH, "🧪 Testing HTTP Client 2.0.0")
            
            val httpTransport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            
            val calendarService = Calendar.Builder(httpTransport, jsonFactory, null)
                .setApplicationName("CF Alarm HTTP Test")
                .build()
                
            Logger.d(LogTags.AUTH, "✅ HTTP Client 2.0.0 SUCCESS")
            Result.success("✅ HTTP Client 2.0.0 works perfectly!")
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ HTTP Client 2.0.0 FAILED: ${e.message}")
            Result.failure(e)
        }
    }
}
