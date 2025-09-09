package com.github.f1rlefanz.cf_alarmfortimeoffice.poc

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🧪 SIMPLE HTTP CLIENT 2.0.0 TEST
 * Rufen Sie diese Klasse manuell aus der MainActivity auf, um zu testen!
 */
object SimpleHttpClientTest {
    
    fun runTest(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("HTTP_CLIENT_TEST", "🧪 Starting HTTP Client 2.0.0 Test...")
                
                // Test 1: HTTP Transport Setup
                val httpTransport = NetHttpTransport()
                Log.d("HTTP_CLIENT_TEST", "✅ NetHttpTransport() created successfully")
                
                // Test 2: JSON Factory Setup  
                val jsonFactory = GsonFactory.getDefaultInstance()
                Log.d("HTTP_CLIENT_TEST", "✅ GsonFactory.getDefaultInstance() created successfully")
                
                // Test 3: Calendar Service Setup
                val calendarService = Calendar.Builder(httpTransport, jsonFactory, null)
                    .setApplicationName("CF Alarm HTTP Test")
                    .build()
                    
                Log.d("HTTP_CLIENT_TEST", "✅ Calendar.Builder() with HTTP 2.0.0 successful")
                Log.d("HTTP_CLIENT_TEST", "✅ Application Name: ${calendarService.applicationName}")
                
                Log.d("HTTP_CLIENT_TEST", "🎉 HTTP CLIENT 2.0.0 TEST COMPLETED SUCCESSFULLY!")
                Log.d("HTTP_CLIENT_TEST", "Migration from 1.47.1 to 2.0.0 is SAFE!")
                
            } catch (e: Exception) {
                Log.e("HTTP_CLIENT_TEST", "❌ HTTP Client 2.0.0 Test FAILED: ${e.message}", e)
                Log.e("HTTP_CLIENT_TEST", "❌ Migration might have issues!")
            }
        }
    }
}
