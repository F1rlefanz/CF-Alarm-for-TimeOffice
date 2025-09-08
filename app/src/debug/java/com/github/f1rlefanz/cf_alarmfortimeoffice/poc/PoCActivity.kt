package com.github.f1rlefanz.cf_alarmfortimeoffice.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 🧪 DEBUG ONLY: Proof of Concept Activity
 * 
 * Diese Activity wird NUR in Debug-Builds kompiliert und dient
 * ausschließlich zum Testen der Google APIs Migration.
 * 
 * ⚠️ WIRD IN PRODUKTION AUTOMATISCH ENTFERNT
 */
class PoCActivity : ComponentActivity() {
    
    private lateinit var poc: GoogleApiMigrationPoC
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        poc = GoogleApiMigrationPoC(this)
        
        setContent {
            MaterialTheme {
                PoCScreen()
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PoCScreen() {
        var testResults by remember { mutableStateOf<List<TestResult>>(emptyList()) }
        var isRunning by remember { mutableStateOf(false) }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "🧪 Google APIs Migration PoC",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "DEBUG BUILD ONLY",
                style = MaterialTheme.typography.labelMedium,
                color = Color.Red
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { 
                    runPoC { results -> 
                        testResults = results
                        isRunning = false
                    }
                    isRunning = true
                },
                enabled = !isRunning
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRunning) "Running..." else "Run Full PoC")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            LazyColumn {
                items(testResults) { result ->
                    TestResultCard(result)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
    
    @Composable
    fun TestResultCard(result: TestResult) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (result.success) 
                    Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    result.testName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (result.error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Error: ${result.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                }
            }
        }
    }
    
    private fun runPoC(onComplete: (List<TestResult>) -> Unit) {
        lifecycleScope.launch {
            val results = mutableListOf<TestResult>()
            
            // Test 1: Basic Credential Manager
            try {
                val result1 = poc.testBasicCredentialManager()
                results.add(
                    TestResult(
                        "Test 1: Credential Manager",
                        result1.isSuccess,
                        result1.getOrNull() ?: "",
                        result1.exceptionOrNull()?.message
                    )
                )
            } catch (e: Exception) {
                results.add(
                    TestResult(
                        "Test 1: Credential Manager",
                        false,
                        "",
                        e.message
                    )
                )
            }
            
            // Test 2: OAuth2 Flow
            try {
                val result2 = poc.testModernOAuth2Flow()
                results.add(
                    TestResult(
                        "Test 2: OAuth2 Flow",
                        result2.isSuccess,
                        result2.getOrNull() ?: "",
                        result2.exceptionOrNull()?.message
                    )
                )
            } catch (e: Exception) {
                results.add(
                    TestResult(
                        "Test 2: OAuth2 Flow",
                        false,
                        "",
                        e.message
                    )
                )
            }
            
            // Test 3: Calendar API
            try {
                val result3 = poc.testCalendarApiAccess()
                results.add(
                    TestResult(
                        "Test 3: Calendar API",
                        result3.isSuccess,
                        result3.getOrNull() ?: "",
                        result3.exceptionOrNull()?.message
                    )
                )
            } catch (e: Exception) {
                results.add(
                    TestResult(
                        "Test 3: Calendar API", 
                        false,
                        "",
                        e.message
                    )
                )
            }
            
            onComplete(results)
        }
    }
    
    data class TestResult(
        val testName: String,
        val success: Boolean,
        val message: String,
        val error: String?
    )
}
