import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    kotlin("kapt")
    
    // Firebase plugins (2025 Standards)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// ==============================
// 🔐 SECURE KEYSTORE PROPERTIES LOADING
// ==============================
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.github.f1rlefanz.cf_alarmfortimeoffice"
    compileSdk = 36  // Updated for androidx.core 1.17.0 requirement

    // ==============================
    // 🔐 SECURE SIGNING CONFIGURATION
    // ==============================
    signingConfigs {
        create("release") {
            // Secure production signing - NO hardcoded passwords!
            storeFile = file(keystoreProperties["storeFile"] as String? ?: "../cf-alarm-release.keystore")
            storePassword = keystoreProperties["storePassword"] as String? ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = keystoreProperties["keyAlias"] as String? ?: "cf-alarm-key"
            keyPassword = keystoreProperties["keyPassword"] as String? ?: System.getenv("KEY_PASSWORD")
            
            // Enhanced security settings
            enableV1Signing = true  // JAR Signature (for older Android versions)
            enableV2Signing = true  // APK Signature Scheme v2 (Android 7.0+)
            enableV3Signing = true  // APK Signature Scheme v3 (Android 9.0+)
            enableV4Signing = true  // APK Signature Scheme v4 (Android 11+)
        }
        
        // Debug signing config (uses default Android debug keystore)
        getByName("debug") {
            // Uses ~/.android/debug.keystore automatically
            // No configuration needed - handled by Android SDK
        }
    }

    defaultConfig {
        applicationId = "com.github.f1rlefanz.cf_alarmfortimeoffice"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Secure OAuth Client ID loading - supports different environments
        val googleWebClientId = keystoreProperties["googleWebClientId"] as String? 
            ?: System.getenv("GOOGLE_WEB_CLIENT_ID") 
            ?: "931091152160-8s3nd7os2p61ac6ecm799gjhekkf0b4i.apps.googleusercontent.com" // Fallback for development
        
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildTypes {
        release {
            // ==============================
            // 🚀 PRODUCTION BUILD CONFIGURATION
            // ==============================
            
            // SIGNING: Use production keystore
            signingConfig = signingConfigs.getByName("release")
            
            // SECURITY: Enable code obfuscation and optimization
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // SECURITY: Disable debugging in release builds
            isDebuggable = false
            isJniDebuggable = false
            
            // SECURITY: Enable dead code elimination
            isPseudoLocalesEnabled = false
            
            // APP IDENTIFICATION: Clear production naming
            // No suffix - this is the production version
        }
        
        debug {
            // ==============================
            // 🛠️ DEVELOPMENT BUILD CONFIGURATION  
            // ==============================
            
            // SIGNING: Uses default debug keystore (automatic)
            // signingConfig = signingConfigs.getByName("debug") // Not needed, automatic
            
            // Development settings
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            
            // APP IDENTIFICATION: Clear debug identification
            // applicationIdSuffix = ".debug"  // TEMP: Disabled for Google Services compatibility
            versionNameSuffix = "-DEBUG"
        }
        
        // ==============================
        // 🧪 OPTIONAL: STAGING BUILD TYPE (DISABLED)
        // ==============================
        // Uncomment if you need staging builds with separate package ID
        /*
        create("staging") {
            // Hybrid configuration: Production signing + Limited debugging
            initWith(getByName("release"))
            
            // Override for staging-specific settings
            isDebuggable = true
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-STAGING"
            
            // IMPORTANT: Disable minification for staging to allow proper debugging
            isMinifyEnabled = false
            isShrinkResources = false
            
            // Use production signing for realistic testing
            signingConfig = signingConfigs.getByName("release")
        }
        */
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Enable desugaring for LocalDateTime support on API < 26
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Production-ready lint configuration
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        
        // Use our custom lint configuration
        lintConfig = file("lint.xml")
        
        // Optional: Create baseline for existing issues
        // baseline = file("lint-baseline.xml")
        
        // Enable HTML and XML reports
        htmlReport = true
        xmlReport = true
        htmlOutput = file("${layout.buildDirectory.get()}/reports/lint/lint-report.html")
        xmlOutput = file("${layout.buildDirectory.get()}/reports/lint/lint-report.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    // ==============================
    // 🧪 TEST CONFIGURATION
    // ==============================
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        
        // Ensure test framework can find our TestSuite
        animationsDisabled = true
        
        // JUnit configuration
        unitTests.all { test ->
            test.useJUnitPlatform {
                // Include all tests, including our TestSuite
                includeTags("junit")
            }
        }
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM and UI dependencies
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material.icons.extended)

    // Lifecycle and ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Authentication & Credentials
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.gpsAuth)
    implementation(libs.googleid)
    implementation(libs.google.auth.library.oauth2.http)
    implementation(libs.google.auth.library.credentials)

    // Google API Client for Calendar
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.calendar)
    implementation(libs.google.http.client.android)
    implementation(libs.google.http.client.gson)

    // Data storage & serialization
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    // Network dependencies for Hue integration
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Security
    implementation(libs.androidx.security.crypto)

    // Logging
    implementation(libs.timber)
    
    // 🚀 PHASE 3: WorkManager für Background-Services
    implementation(libs.androidx.work.runtime.ktx)

    // Firebase (2025 Standards) - ONLY Crashlytics
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // Firebase Analytics EXPLICITLY EXCLUDED to prevent AD_ID permission
    configurations.all {
        exclude(group = "com.google.firebase", module = "firebase-analytics")
        exclude(group = "com.google.firebase", module = "firebase-analytics-ktx")
    }

    // Desugaring for LocalDateTime support
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Dependency Injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Debug dependencies
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
