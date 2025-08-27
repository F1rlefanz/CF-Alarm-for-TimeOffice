package com.github.f1rlefanz.cf_alarmfortimeoffice.testing

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig

/**
 * 🧪 DEBUG ONLY: Firebase Crashlytics Test Activity
 * 
 * Provides a simple UI for testing Firebase Crashlytics integration
 * Only available in DEBUG builds for development testing
 */
class CrashlyticsTestActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            CFAlarmForTimeOfficeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CrashlyticsTestScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashlyticsTestScreen() {
    var lastTestResult by remember { mutableStateOf("No tests run yet") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🧪 Firebase Crashlytics Testing",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "DEBUG BUILD ONLY - Test non-fatal error reporting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    text = "Build Type: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // Test Buttons
        Text(
            text = "Individual Tests",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Button(
            onClick = {
                CrashlyticsTestUtils.simulateAuthFailure()
                lastTestResult = "✅ Auth NoCredentialException test sent to Firebase"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔐 Test Auth Failure")
        }
        
        Button(
            onClick = {
                CrashlyticsTestUtils.simulateCalendarEmptyList()
                lastTestResult = "✅ Calendar Empty List test sent to Firebase"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📅 Test Calendar Empty List")
        }
        
        Button(
            onClick = {
                CrashlyticsTestUtils.simulateHueTimeout()
                lastTestResult = "✅ Hue Bridge Timeout test sent to Firebase"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🌉 Test Hue Bridge Timeout")
        }
        
        Divider()
        
        // Combined Tests
        Text(
            text = "Combined Tests",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Button(
            onClick = {
                CrashlyticsTestUtils.runAllSimulations()
                lastTestResult = "✅ All test scenarios sent to Firebase"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("🚀 Run All Tests")
        }
        
        Button(
            onClick = {
                CrashlyticsTestUtils.verifyFirebaseSetup()
                lastTestResult = "✅ Firebase setup verification completed"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Text("🔍 Verify Firebase Setup")
        }
        
        // Results
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Last Test Result",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lastTestResult,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📊 Testing Instructions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "1. Build and install RELEASE version",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "2. Run tests above",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "3. Wait 5-10 minutes",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "4. Check Firebase Console for non-fatal errors",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "5. Verify custom keys and breadcrumbs are visible",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
