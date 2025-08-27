package com.github.f1rlefanz.cf_alarmfortimeoffice.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY Activity zum Testen der Firebase Crashlytics Integration
 * 
 * Nur im debug sourceset verfügbar - automatisch aus Release builds ausgeschlossen.
 * Ermöglicht einfaches Testen der non-fatal error Berichterstattung.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CrashlyticsTestActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            CFAlarmForTimeOfficeTheme {
                CrashlyticsTestScreen()
            }
        }
    }
    
    @Composable
    private fun CrashlyticsTestScreen() {
        val coroutineScope = rememberCoroutineScope()
        var testStatus by remember { mutableStateOf("Bereit für Tests") }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("🧪 Crashlytics Test") }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Firebase Crashlytics Test",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Status: $testStatus",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Diese Tests senden non-fatal Exceptions an Firebase Crashlytics. " +
                                    "Prüfe die Firebase Console nach 5-10 Minuten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Individual Test Buttons
                TestButton(
                    text = "🔐 Test: Google Auth Failure",
                    description = "Simuliert NoCredentialException"
                ) {
                    coroutineScope.launch {
                        testStatus = "Teste Google Auth Failure..."
                        CrashlyticsTestUtils.simulateAuthFailure(this@CrashlyticsTestActivity)
                        testStatus = "Google Auth Test gesendet ✓"
                    }
                }
                
                TestButton(
                    text = "📅 Test: Calendar Empty List",
                    description = "Simuliert leere Kalender-Response"
                ) {
                    coroutineScope.launch {
                        testStatus = "Teste Calendar Empty List..."
                        CrashlyticsTestUtils.simulateCalendarEmptyList()
                        testStatus = "Calendar Test gesendet ✓"
                    }
                }
                
                TestButton(
                    text = "💡 Test: Hue Bridge Timeout",
                    description = "Simuliert Bridge Connection Timeout"
                ) {
                    coroutineScope.launch {
                        testStatus = "Teste Hue Bridge Timeout..."
                        CrashlyticsTestUtils.simulateHueTimeout()
                        testStatus = "Hue Bridge Test gesendet ✓"
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Combined Tests
                Button(
                    onClick = {
                        coroutineScope.launch {
                            testStatus = "Führe alle Tests aus..."
                            CrashlyticsTestUtils.runAllCrashlyticsTests(this@CrashlyticsTestActivity)
                            testStatus = "Alle Tests gesendet ✓"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = "🚀 Alle Tests ausführen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Button(
                    onClick = {
                        coroutineScope.launch {
                            testStatus = "Teste ErrorHandler Integration..."
                            CrashlyticsTestUtils.testErrorHandlerIntegration(this@CrashlyticsTestActivity)
                            testStatus = "ErrorHandler Test gesendet ✓"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text(
                        text = "⚙️ Test ErrorHandler Integration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "💡 Nach den Tests:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Warte 5-10 Minuten\n" +
                                    "2. Öffne Firebase Console\n" +
                                    "3. Gehe zu Crashlytics → Probleme\n" +
                                    "4. Filter: test_scenario = 'crashlytics_debug_test'\n" +
                                    "5. Überprüfe Custom Keys und Breadcrumbs",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    private fun TestButton(
        text: String,
        description: String,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
