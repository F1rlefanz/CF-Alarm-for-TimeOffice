// Temporärer Code zum SHA-1 Check
// Füge das in deine MainActivity ein:

import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

fun checkCurrentSHA1() {
    try {
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNATURES
        )
        
        for (signature in packageInfo.signatures) {
            val md = MessageDigest.getInstance("SHA1")
            md.update(signature.toByteArray())
            val sha1 = md.digest()
            
            val hexString = sha1.joinToString(":") { 
                "%02X".format(it) 
            }
            
            Log.e("SHA1_CHECK", "Current app SHA-1: $hexString")
        }
    } catch (e: Exception) {
        Log.e("SHA1_CHECK", "Error getting SHA1", e)
    }
}

// Rufe das in onCreate auf:
// checkCurrentSHA1()
