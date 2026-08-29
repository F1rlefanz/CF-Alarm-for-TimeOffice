import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    compileSdk = 37

    // ==============================
    // 🔐 SECURE SIGNING CONFIGURATION
    // ==============================
    // SIGNIERUNG NUR, WENN DER SCHLUESSEL DA IST. Ohne das scheitert `assembleRelease` in der CI am
    // fehlenden Keystore - und genau deshalb hat die CI den Release-Pfad bisher gar nicht gebaut,
    // obwohl dort das eigentliche Risiko liegt (R8, lintVitalRelease). Fehlt der Schluessel, entsteht
    // eine UNSIGNIERTE Release-APK: Die laesst sich weder installieren noch hochladen, der Fehler
    // kann also nicht unbemerkt durchrutschen. Lokal existiert keystore.properties immer
    // (Voraussetzung laut CLAUDE.md), dort wird wie bisher signiert.
    val releaseSigningAvailable =
        keystorePropertiesFile.exists() || System.getenv("KEYSTORE_PASSWORD") != null

    signingConfigs {
        create("release") {
            // Secure production signing - NO hardcoded passwords!
            storeFile = project.file(keystoreProperties["storeFile"] as String? ?: "../cf-alarm-release.keystore")
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
        targetSdk = 37
        versionCode = 122
        versionName = "1.37.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // ==============================
        // 🚫 EXPLICIT AD_ID REMOVAL
        // ==============================
        // CRITICAL: Remove AD_ID permission added by play-services-auth
        // This must be done at build time via androidResources
        androidResources {
            ignoreAssetsPattern = "!.svn:!.git:.*:!CVS:!thumbs.db:!picasa.ini:!*.scc:*~"
        }
        
        // Additional manifest placeholder (belt and suspenders approach)
        manifestPlaceholders["excludeAdIdPermission"] = "true"

        // ==============================
        // 🔐 SECURE OAUTH CLIENT ID CONFIGURATION
        // ==============================
        // SECURITY: OAuth Client ID must be configured in keystore.properties or environment variables
        // NO hardcoded fallback to prevent accidental credential leakage in version control
        val googleWebClientId = keystoreProperties["googleWebClientId"] as String?
            ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
            ?: throw GradleException(
                """
                ⚠️ GOOGLE_WEB_CLIENT_ID not configured!
                
                Please add it to 'keystore.properties':
                    googleWebClientId=your-client-id-here.apps.googleusercontent.com
                
                Or set environment variable:
                    export GOOGLE_WEB_CLIENT_ID=your-client-id-here
                
                Get your Client ID from: https://console.cloud.google.com/apis/credentials
                """.trimIndent()
            )

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")


    }

    buildTypes {
        release {
            // ==============================
            // 🚀 PRODUCTION BUILD CONFIGURATION
            // ==============================

            // SIGNING: Produktions-Keystore, wenn vorhanden - siehe releaseSigningAvailable oben.
            signingConfig =
                if (releaseSigningAvailable) signingConfigs.getByName("release") else null

            // R8 IST AN, seit 10.08.2026 (AGP 9.3.1). Vorher stand hier `false` mit dem Vermerk
            // "Re-enable after AGP upgrade fixes R8 9.2.14 NPE bug (core:1.19.0 + compileSdk 37)".
            // Der NPE tritt mit AGP 9.3.1 nicht mehr auf; nachgemessen und am Geraet verifiziert:
            //  - `assembleRelease` laeuft durch, `minifyReleaseWithR8` ohne Fehler
            //  - APK 19,8 MB → 10,9 MB (-45%)
            //  - Release-APK auf dem Emulator (Android 16) durch den vollen Happy Path: Anmeldung
            //    ueber Google, Kalenderliste, Kalenderauswahl speichern, 5 Schichten erkannt,
            //    5 Alarme gesetzt, alle sechs Tabs gerendert - kein ClassNotFoundException,
            //    NoSuchMethodError, NoClassDefFoundError oder VerifyError im Logcat.
            //    Damit sind die klassischen R8-Opfer mitgeprueft: der Google-HTTP-Client (Reflection),
            //    Gson, Retrofit und kotlinx-serialization.
            // Einzige Warnung: R8 meldet, dass `Log4JLogger.<clinit>()` aus commons-logging nicht
            // typ-prueft und als unerreichbar gilt - eine transitive Abhaengigkeit des
            // Google-HTTP-Clients, die die App nie benutzt. Harmlos.
            //
            // WICHTIG, falls das je wieder abgeschaltet wird: Dann MUESSEN auch `-dontshrink` und
            // `-dontoptimize` in proguard-rules.pro wieder aktiviert werden - oder eben nicht.
            // Beides halb ist der schlechteste Zustand: Mit gesetztem `-dontshrink` waere
            // `isMinifyEnabled = true` eine Attrappe, die Bauzeit kostet und nichts bringt.
            //
            // `assembleRelease` braucht NETZ: Die nur mit Minify laufende Task
            // `produceReleaseComposeMapping` zieht eine Abhaengigkeit, die nicht im Offline-Cache
            // liegt. `--offline` scheitert daran mit einem irrefuehrenden
            // "Configuration cache state could not be cached" - kein R8-Problem.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "r8-rules.txt"  // Additional R8-specific rules
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

            // Produktions-Signierung fuer realistisches Testen - dieselbe Bedingung wie release.
            signingConfig =
                if (releaseSigningAvailable) signingConfigs.getByName("release") else null
        }
        */
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Enable desugaring for LocalDateTime support on API < 26
        isCoreLibraryDesugaringEnabled = true
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
        lintConfig = project.file("lint.xml")

        // BEWUSST KEINE Baseline: Es gibt keine lint-baseline.xml mehr (August 2026 gelöscht).
        // Die alte enthielt nur 27 FullBackupContent-Einträge zu <exclude>-Zeilen, die es in
        // den Backup-Regeln längst nicht mehr gab, und wurde durch diese auskommentierte Zeile
        // ohnehin nie gelesen - halb verdrahteter toter Ballast. Wer eine Baseline will:
        // frisch erzeugen UND diese Zeile aktivieren, nicht das eine ohne das andere.

        // HTML/XML reports werden seit AGP 9 immer erzeugt (Standardpfad
        // build/reports/lint-results-<variant>.*) - kein Opt-in mehr noetig.
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
        animationsDisabled = true
    }
}

// `kotlin { }` ist eine Extension auf PROJECT, nicht auf ApplicationExtension. Bis zum
// 18.08.2026 stand dieser Block INNERHALB von `android { }` - er wirkte trotzdem, weil Kotlin
// stillschweigend auf den aeusseren Project-Receiver auswich. Android Studio meldet das als
// "Suspicious receiver type": es las sich wie eine AGP-Einstellung, war aber immer eine
// projektweite. Hier steht es dort, wo es hingehoert.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Unit-Tests laufen über den Standard-JUnit-4-Runner (JUnit 4.13.2).
// Kein useJUnitPlatform: Es ist bewusst keine JUnit-5-Engine eingebunden,
// sonst würde der Test-Task 0 Tests ausführen (stiller No-Op).

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
    // Liefert LocalLifecycleOwner (androidx.lifecycle.compose) - die Variante aus
    // androidx.compose.ui.platform ist seit Compose 1.7 deprecated.
    implementation(libs.androidx.lifecycle.runtime.compose)

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
    implementation(libs.tink.android)  // ✅ Modern Crypto Library für Token-Verschlüsselung

    // Logging
    implementation(libs.timber)

    // 🚀 PHASE 3: WorkManager für Background-Services
    implementation(libs.androidx.work.runtime.ktx)

    // ==============================
    // 🛠️ FIXED: Using version catalog instead of hardcoded versions
    // ==============================
    implementation(libs.play.services.base)  // ✅ Now using version catalog (18.9.0)
    // androidx.core:core-ktx already included above via libs.androidx.core.ktx

    // Desugaring for LocalDateTime support
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Dependency Injection
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Android Studio meldet hier "Dependency 'platform(libs.androidx.compose.bom)' is declared
    // multiple times" - das ist ein FEHLALARM, die Zeile muss bleiben.
    // `androidTestImplementation` erbt NICHT von `implementation`, die BOM aus dem Block oben gilt
    // hier also nicht. Und `androidx-ui-test-junit4` / `androidx-ui-test-manifest` stehen im
    // Version-Catalog bewusst OHNE eigene Version - sie beziehen sie ausschliesslich von der BOM.
    // Nachgemessen am 18.08.2026:
    //   ./gradlew app:dependencies --configuration debugAndroidTestCompileClasspath
    //   -> androidx.compose.ui:ui-test-junit4 -> 1.12.0   (aufgeloest ueber compose-bom:2026.08.00)
    // Ohne diese Zeile bliebe die Abhaengigkeit unversioniert und der Instrumentationstest-Build
    // scheitert. Wer der IDE-Warnung folgt, macht die Tests kaputt, nicht den Build sauberer.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Debug dependencies
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
