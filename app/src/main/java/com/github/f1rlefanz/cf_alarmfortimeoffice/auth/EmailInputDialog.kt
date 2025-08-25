package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Emergency Email Input Dialog
 * 
 * This dialog is shown when automatic email extraction fails completely.
 * It allows users to manually enter their Google account email address
 * for Calendar API authorization.
 */
class EmailInputDialog(private val context: Context) {
    
    fun showEmailInputDialog(
        onEmailProvided: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        Logger.w(LogTags.AUTH, "🆘 MANUAL-INPUT: Showing email input dialog as fallback")
        
        val editText = EditText(context).apply {
            hint = "your.email@gmail.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(editText)
        }
        
        AlertDialog.Builder(context)
            .setTitle("📧 Google Account Email Required")
            .setMessage("""
                |Automatic email detection failed.
                |
                |Please enter your Google account email address manually:
                |
                |• Use the same email you signed in with
                |• This is needed for Calendar access
                |• Your email will be saved for future use
            """.trimMargin())
            .setView(layout)
            .setPositiveButton("✅ Use This Email") { _, _ ->
                val inputEmail = editText.text.toString().trim()
                
                if (inputEmail.isNotEmpty() && inputEmail.contains("@")) {
                    Logger.business(LogTags.AUTH, "✅ MANUAL-INPUT: User provided email: $inputEmail")
                    
                    // Save to SharedPreferences for future use
                    try {
                        val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                        prefs.edit().putString("current_user_email", inputEmail).apply()
                        Logger.d(LogTags.AUTH, "💾 MANUAL-INPUT: Email saved to SharedPreferences")
                    } catch (e: Exception) {
                        Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT: Failed to save email", e)
                    }
                    
                    onEmailProvided(inputEmail)
                } else {
                    Logger.w(LogTags.AUTH, "❌ MANUAL-INPUT: Invalid email provided: $inputEmail")
                    showEmailInputDialog(onEmailProvided, onCancelled) // Retry
                }
            }
            .setNegativeButton("❌ Cancel") { _, _ ->
                Logger.w(LogTags.AUTH, "❌ MANUAL-INPUT: User cancelled email input")
                onCancelled()
            }
            .setCancelable(false) // Force user to make a choice
            .show()
    }
    
    companion object {
        /**
         * Quick check if manual email input might be needed
         */
        fun isManualInputNeeded(email: String?): Boolean {
            return email.isNullOrEmpty() || 
                   !email.contains("@") || 
                   email == "user.needs.to.enter@gmail.com"
        }
        
        /**
         * Get saved manual email if available
         */
        fun getSavedManualEmail(context: Context): String? {
            return try {
                val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                val savedEmail = prefs.getString("current_user_email", null)
                
                if (!savedEmail.isNullOrEmpty() && 
                    savedEmail.contains("@") && 
                    savedEmail != "user.needs.to.enter@gmail.com") {
                    Logger.d(LogTags.AUTH, "📋 MANUAL-INPUT: Found saved email: $savedEmail")
                    savedEmail
                } else {
                    null
                }
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT: Error reading saved email", e)
                null
            }
        }
    }
}
