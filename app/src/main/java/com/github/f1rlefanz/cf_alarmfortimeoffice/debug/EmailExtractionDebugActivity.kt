package com.github.f1rlefanz.cf_alarmfortimeoffice.debug

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.launch

/**
 * DEBUG ACTIVITY: Test Email Extraction
 * Temporary activity to debug email extraction issues
 * 
 * To use: Add this to AndroidManifest.xml temporarily:
 * <activity
 *     android:name=".debug.EmailExtractionDebugActivity"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN" />
 *         <category android:name="android.intent.category.LAUNCHER" />
 *     </intent-filter>
 * </activity>
 */
class EmailExtractionDebugActivity : AppCompatActivity() {
    
    private lateinit var credentialAuthManager: CredentialAuthManager
    private lateinit var debugTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create simple layout programmatically
        debugTextView = TextView(this).apply {
            text = "Email Extraction Debug\n\nTesting..."
            textSize = 12f
            setPadding(32, 32, 32, 32)
        }
        setContentView(debugTextView)
        
        credentialAuthManager = CredentialAuthManager(this)
        
        runEmailExtractionTest()
    }
    
    private fun runEmailExtractionTest() {
        lifecycleScope.launch {
            try {
                val diagnosticInfo = credentialAuthManager.diagnoseEmailExtraction(this@EmailExtractionDebugActivity)
                
                val debugInfo = StringBuilder()
                debugInfo.append("=== EMAIL EXTRACTION DEBUG TEST ===\n\n")
                debugInfo.append(diagnosticInfo)
                debugInfo.append("\n\n=== TESTING GOOGLE SIGN-IN ===\n")
                
                debugTextView.text = debugInfo.toString()
                
                Logger.business(LogTags.AUTH, "🧪 DEBUG: Starting email extraction test")
                
                // Test Google Sign-In
                val signInResult = credentialAuthManager.signIn(this@EmailExtractionDebugActivity)
                
                debugInfo.append("\nSign-In Result: ${if (signInResult.success) "SUCCESS" else "FAILED"}\n")
                
                if (signInResult.success && signInResult.credentialResponse != null) {
                    debugInfo.append("Extracting user info...\n")
                    
                    val (userId, displayName, email) = credentialAuthManager.extractUserInfo(signInResult.credentialResponse, this@EmailExtractionDebugActivity)
                    
                    debugInfo.append("\n=== EXTRACTION RESULTS ===\n")
                    debugInfo.append("User ID: $userId\n")
                    debugInfo.append("Display Name: $displayName\n")
                    debugInfo.append("Email: $email\n")
                    debugInfo.append("Email Valid: ${email?.contains("@") == true}\n")
                    
                    if (email?.contains("@") == true) {
                        debugInfo.append("\n✅ SUCCESS: Valid email extracted!\n")
                        debugInfo.append("Calendar API should work now.\n")
                    } else {
                        debugInfo.append("\n❌ FAILED: No valid email extracted\n")
                        debugInfo.append("Calendar API will fail with BAD_AUTHENTICATION\n")
                        
                        // Additional diagnostic
                        val postDiagnostic = credentialAuthManager.diagnoseEmailExtraction(this@EmailExtractionDebugActivity)
                        debugInfo.append("\n=== POST-SIGNIN DIAGNOSTIC ===\n")
                        debugInfo.append(postDiagnostic)
                    }
                    
                } else {
                    debugInfo.append("Sign-In failed: ${signInResult.error}\n")
                }
                
                debugTextView.text = debugInfo.toString()
                
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "🧪 DEBUG: Email extraction test failed", e)
                debugTextView.text = "Debug test failed: ${e.message}\n\n${e.stackTrace.joinToString("\n")}"
            }
        }
    }
}
