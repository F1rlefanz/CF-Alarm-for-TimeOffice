package com.github.f1rlefanz.cf_alarmfortimeoffice.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import java.io.File

/**
 * PHASE 2: Root Detection for Enhanced Security
 * 
 * Detects if the device is rooted/jailbroken to alert users about potential security risks.
 * This is a defense-in-depth measure to complement encrypted storage and network security.
 * 
 * ARCHITECTURE:
 * - Single Responsibility: Root/jailbreak detection only
 * - Defensive Programming: Multiple detection methods for reliability
 * - Privacy-First: Only logs detection results, doesn't block functionality
 * 
 * SECURITY RATIONALE:
 * - Rooted devices can potentially bypass EncryptedSharedPreferences
 * - Apps can be more easily reverse-engineered on rooted devices
 * - Additional attack surface for malicious apps
 * 
 * @author CF-Alarm Development Team
 * @since Security Phase 2
 */
object RootDetection {
    
    // Common root management apps and tools
    private val ROOT_APPS = arrayOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.topjohnwu.magisk"
    )
    
    // Common root binary paths
    private val ROOT_BINARIES = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/dev/com.koushikdutta.superuser.daemon/",
        "/system/xbin/busybox"
    )
    
    /**
     * Performs comprehensive root detection check.
     * Uses multiple detection methods for higher reliability.
     * 
     * @param context Application context for package manager access
     * @return RootDetectionResult with detailed findings
     */
    fun performRootDetection(context: Context): RootDetectionResult {
        Logger.d(LogTags.SECURITY, "🔍 SECURITY: Starting comprehensive root detection scan")
        
        val results = mutableListOf<DetectionMethod>()
        
        // Method 1: Check for root management apps
        val rootAppsFound = checkForRootApps(context)
        if (rootAppsFound.isNotEmpty()) {
            results.add(DetectionMethod.ROOT_APPS_FOUND())
            Logger.w(LogTags.SECURITY, "⚠️  ROOT-DETECTION: Root management apps detected: ${rootAppsFound.joinToString()}")
        }
        
        // Method 2: Check for root binaries
        val rootBinariesFound = checkForRootBinaries()
        if (rootBinariesFound.isNotEmpty()) {
            results.add(DetectionMethod.ROOT_BINARIES_FOUND())
            Logger.w(LogTags.SECURITY, "⚠️  ROOT-DETECTION: Root binaries detected: ${rootBinariesFound.joinToString()}")
        }
        
        // Method 3: Check for test-keys (unofficial ROM)
        if (checkForTestKeys()) {
            results.add(DetectionMethod.TEST_KEYS)
            Logger.w(LogTags.SECURITY, "⚠️  ROOT-DETECTION: Device signed with test-keys (unofficial ROM)")
        }
        
        // Method 4: Check for writable system directories
        val writableSystemDirs = checkWritableSystemDirs()
        if (writableSystemDirs.isNotEmpty()) {
            results.add(DetectionMethod.WRITABLE_SYSTEM_DIRS())
            Logger.w(LogTags.SECURITY, "⚠️  ROOT-DETECTION: Writable system directories: ${writableSystemDirs.joinToString()}")
        }
        
        val isRooted = results.isNotEmpty()
        val riskLevel = calculateRiskLevel(results)
        
        val result = RootDetectionResult(
            isRooted = isRooted,
            riskLevel = riskLevel,
            detectionMethods = results,
            deviceInfo = DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                buildTags = Build.TAGS
            )
        )
        
        logRootDetectionResult(result)
        return result
    }
    
    /**
     * Checks for installed root management applications
     */
    private fun checkForRootApps(context: Context): List<String> {
        val foundApps = mutableListOf<String>()
        val packageManager = context.packageManager
        
        for (packageName in ROOT_APPS) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                foundApps.add(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                // Package not found - good!
            }
        }
        
        return foundApps
    }
    
    /**
     * Checks for common root binary files
     */
    private fun checkForRootBinaries(): List<String> {
        val foundBinaries = mutableListOf<String>()
        
        for (binaryPath in ROOT_BINARIES) {
            try {
                val file = File(binaryPath)
                if (file.exists()) {
                    foundBinaries.add(binaryPath)
                }
            } catch (e: Exception) {
                // Access denied or other error - continue checking
            }
        }
        
        return foundBinaries
    }
    
    /**
     * Checks if device is signed with test-keys (custom ROM indicator)
     */
    private fun checkForTestKeys(): Boolean {
        return Build.TAGS != null && Build.TAGS.contains("test-keys")
    }
    
    /**
     * Checks for writable system directories (should be read-only on non-rooted devices)
     */
    private fun checkWritableSystemDirs(): List<String> {
        val foundWritable = mutableListOf<String>()
        val systemDirs = arrayOf(
            "/system",
            "/system/bin",
            "/system/sbin",
            "/system/xbin",
            "/vendor/bin",
            "/sbin"
        )
        
        for (dir in systemDirs) {
            try {
                val file = File(dir)
                if (file.exists() && file.canWrite()) {
                    foundWritable.add(dir)
                }
            } catch (e: Exception) {
                // Access denied - good!
            }
        }
        
        return foundWritable
    }
    
    /**
     * Calculates risk level based on detection methods found
     */
    private fun calculateRiskLevel(detectionMethods: List<DetectionMethod>): RiskLevel {
        return when {
            detectionMethods.isEmpty() -> RiskLevel.LOW
            detectionMethods.size == 1 -> RiskLevel.MEDIUM
            detectionMethods.size >= 2 -> RiskLevel.HIGH
            else -> RiskLevel.LOW
        }
    }
    
    /**
     * Logs comprehensive root detection results
     */
    private fun logRootDetectionResult(result: RootDetectionResult) {
        if (result.isRooted) {
            Logger.w(LogTags.SECURITY, "🚨 ROOT-DETECTION: Device appears to be ROOTED")
            Logger.w(LogTags.SECURITY, "📊 ROOT-DETECTION: Risk Level: ${result.riskLevel}")
            Logger.w(LogTags.SECURITY, "🔍 ROOT-DETECTION: Detection methods: ${result.detectionMethods.size}")
            Logger.business(LogTags.SECURITY, "⚠️  SECURITY-ALERT: Rooted device detected - enhanced security monitoring active")
        } else {
            Logger.business(LogTags.SECURITY, "✅ ROOT-DETECTION: Device appears to be non-rooted")
            Logger.d(LogTags.SECURITY, "🔒 SECURITY-STATUS: Standard security measures sufficient")
        }
        
        Logger.d(LogTags.SECURITY, "📱 DEVICE-INFO: ${result.deviceInfo.manufacturer} ${result.deviceInfo.model} (Android ${result.deviceInfo.androidVersion})")
        Logger.d(LogTags.SECURITY, "🛡️  SECURITY-PATCH: ${result.deviceInfo.securityPatch}")
    }
}

/**
 * Root detection result with comprehensive information
 */
data class RootDetectionResult(
    val isRooted: Boolean,
    val riskLevel: RiskLevel,
    val detectionMethods: List<DetectionMethod>,
    val deviceInfo: DeviceInfo
) {
    fun toLogString(): String {
        return "RootDetectionResult(rooted=$isRooted, risk=$riskLevel, methods=${detectionMethods.size}, device=${deviceInfo.manufacturer} ${deviceInfo.model})"
    }
}

/**
 * Risk levels for root detection
 */
enum class RiskLevel {
    LOW,    // No root indicators found
    MEDIUM, // One root indicator found
    HIGH    // Multiple root indicators found
}

/**
 * Detection methods that can identify root access
 */
sealed class DetectionMethod {
    data class ROOT_APPS_FOUND(val apps: List<String> = emptyList()) : DetectionMethod()
    data class ROOT_BINARIES_FOUND(val binaries: List<String> = emptyList()) : DetectionMethod()
    object TEST_KEYS : DetectionMethod()
    data class WRITABLE_SYSTEM_DIRS(val dirs: List<String> = emptyList()) : DetectionMethod()
}

/**
 * Device information for security analysis
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val securityPatch: String,
    val buildTags: String
)
