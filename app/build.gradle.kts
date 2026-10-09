import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.github.f1rlefanz.cf_alarmfortimeoffice"
    compileSdk = 37

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
            storeFile = project.file(keystoreProperties["storeFile"] as String? ?: "../cf-alarm-release.keystore")
            storePassword = keystoreProperties["storePassword"] as String? ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = keystoreProperties["keyAlias"] as String? ?: "cf-alarm-key"
            keyPassword = keystoreProperties["keyPassword"] as String? ?: System.getenv("KEY_PASSWORD")

            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
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
        versionCode = 163
        versionName = "1.47.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AD_ID wird NICHT hier gesperrt, sondern im Manifest (maxSdkVersion="0", siehe CLAUDE.md).
        // Bis v1.44 standen hier ein ignoreAssetsPattern und ein manifestPlaceholder, die das
        // vorgaben - beide wirkungslos (kein Manifest las den Platzhalter; #133, G10-05).

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
            // SIGNING: Produktions-Keystore, wenn vorhanden - siehe releaseSigningAvailable oben.
            signingConfig =
                if (releaseSigningAvailable) signingConfigs.getByName("release") else null

            // R8 an seit 10.08.2026 (AGP 9.3.1); vorher aus wegen eines R8-9.2.14-NPE (core 1.19.0 +
            // compileSdk 37). Release-APK am Emulator mit vollem Happy Path verifiziert. Die R8-Warnung zu
            // `Log4JLogger.<clinit>()` aus commons-logging (transitiv ueber den Google-HTTP-Client,
            // ungenutzt) ist harmlos.
            // Minify und `-dontshrink`/`-dontoptimize` in proguard-rules.pro nur gemeinsam umschalten:
            // mit gesetztem `-dontshrink` waere `isMinifyEnabled = true` eine Attrappe.
            // `assembleRelease` braucht Netz, siehe CLAUDE.md.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // APP IDENTIFICATION: Clear debug identification
            // kein applicationIdSuffix: Google-Anmeldung hängt am Paketnamen
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true

        lintConfig = project.file("lint.xml")

        // BEWUSST KEINE Baseline: Es gibt keine lint-baseline.xml mehr (August 2026 gelöscht).
        // Die alte enthielt nur 27 FullBackupContent-Einträge zu <exclude>-Zeilen, die es in
        // den Backup-Regeln längst nicht mehr gab, und wurde ohne gesetztes `baseline = file(...)`
        // ohnehin nie gelesen - halb verdrahteter toter Ballast. Wer eine Baseline will:
        // frisch erzeugen UND `baseline = file(...)` setzen, nicht das eine ohne das andere.
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }
}

// `kotlin { }` ist eine Project-Extension, nicht Teil von `android { }` (dort innen meldet
// Android Studio "Suspicious receiver type").
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
    implementation(libs.androidx.material3)
    implementation(libs.material.icons.extended)

    // Lifecycle and ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Liefert LocalLifecycleOwner (androidx.lifecycle.compose) - die Variante aus
    // androidx.compose.ui.platform ist seit Compose 1.7 deprecated.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Authentication & Credentials
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.gpsAuth)
    implementation(libs.googleid)

    // Google API Client for Calendar
    // Die beiden `-android`-Artefakte sind bewusst NICHT dabei: sie liefern nur
    // GoogleAccountCredential bzw. AndroidJsonFactory/AndroidHttp, und der Kalenderpfad benutzt
    // keines davon (CalendarRepository baut NetHttpTransport + GsonFactory und setzt den Token
    // selbst per HttpRequestInitializer). Die Kernartefakte google-api-client und
    // google-http-client kommen ueber google-api-services-calendar.
    implementation(libs.google.api.services.calendar)
    implementation(libs.google.http.client.gson)

    // Data storage & serialization
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    // Network dependencies for Hue integration
    // Kein Retrofit: HueApiClient ist handgebautes OkHttp + Gson, weil die V1-Semantik
    // "HTTP 200 auch bei Ablehnung" die Body-Auswertung in HueV1Envelope braucht.
    implementation(libs.okhttp)

    // Security
    implementation(libs.tink.android)

    // Logging
    implementation(libs.timber)

    implementation(libs.androidx.work.runtime.ktx)

    // MUSS BLEIBEN, obwohl der Quelltext kein `com.google.android.gms.common.*` importiert.
    // Diese Zeile ist der einzige Weg, auf dem play-services-base/-basement/-tasks in den Graphen
    // kommen: nimmt man sie heraus, verschwinden alle drei - und play-services-auth (766
    // Referenzen auf com/google/android/gms/common/), play-services-auth-base (850) sowie
    // credentials-play-services-auth (108 auf com/google/android/gms/tasks/) verlieren ihre
    // Klassen. Das faellt in keinem Unit-Test auf, sondern erst beim Anmelden am Geraet.
    implementation(libs.play.services.base)

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
    androidTestImplementation(libs.androidx.junit)
    // Espresso selbst wird nirgends importiert - die Zeile MUSS trotzdem bleiben: sie ist der
    // einzige Weg, auf dem androidx.test:runner in den androidTest-Klassenpfad kommt, und genau
    // den verlangt `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` oben.
    // Ohne sie startet connectedDebugAndroidTest gar nicht erst; kein Unit-Test sieht das.
    androidTestImplementation(libs.androidx.espresso.core)
    // Android Studio meldet hier "Dependency 'platform(libs.androidx.compose.bom)' is declared
    // multiple times" - das ist ein FEHLALARM, die Zeile muss bleiben.
    // `androidTestImplementation` erbt NICHT von `implementation`, die BOM aus dem Block oben gilt
    // hier also nicht. Und `androidx-ui-test-junit4` steht im
    // Version-Catalog bewusst OHNE eigene Version - es bezieht sie ausschliesslich von der BOM.
    // Nachgemessen am 18.08.2026:
    //   ./gradlew app:dependencies --configuration debugAndroidTestCompileClasspath
    //   -> androidx.compose.ui:ui-test-junit4 -> 1.12.0   (aufgeloest ueber compose-bom:2026.08.00)
    // Ohne diese Zeile bliebe die Abhaengigkeit unversioniert und der Instrumentationstest-Build
    // scheitert. Wer der IDE-Warnung folgt, macht die Tests kaputt, nicht den Build sauberer.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

/**
 * "Was ist neu" in der App kommt aus `CHANGELOG.md` - beim Bauen herausgeschnitten, nie von Hand
 * gepflegt. Die Datei ist ohnehin Pflicht (die Schleuse verlangt zu jedem Bump einen Eintrag), also
 * reist der Text mit jeder Version automatisch mit. Nur die juengsten Versionen kommen in die APK:
 * die ganze Datei ist ueber 150 KB, und die volle Liste steht auf der Website.
 *
 * Bewusst kein Markdown-Umbau hier: die Aufgabe schneidet nur ab. Gelesen wird der Ausschnitt von
 * `Neuigkeiten.parse()` - dort ist es testbar.
 */
abstract class NeuigkeitenAusChangelog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelog: RegularFileProperty

    @get:Input
    abstract val anzahlVersionen: Property<Int>

    @get:OutputDirectory
    abstract val ausgabe: DirectoryProperty

    @TaskAction
    fun schneiden() {
        val zeilen = changelog.get().asFile.readLines(Charsets.UTF_8)
        val anfaenge = zeilen.indices.filter { zeilen[it].startsWith("## ") }
        if (anfaenge.isEmpty()) throw GradleException("CHANGELOG.md enthaelt keine '## '-Versionen")
        val ende = anfaenge.getOrNull(anzahlVersionen.get()) ?: zeilen.size
        val datei = ausgabe.get().file("neuigkeiten.md").asFile
        datei.parentFile.mkdirs()
        datei.writeText(zeilen.subList(anfaenge.first(), ende).joinToString("\n"), Charsets.UTF_8)
    }
}

val neuigkeitenAusChangelog = tasks.register<NeuigkeitenAusChangelog>("neuigkeitenAusChangelog") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    anzahlVersionen.set(10)
}

androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(
            neuigkeitenAusChangelog,
            NeuigkeitenAusChangelog::ausgabe
        )
    }
}
