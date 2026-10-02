# ==============================
# GLOBAL OPTIMIZATION SETTINGS
# ==============================

# Keep important attributes for debugging
# Lesbare Stacktraces fuers app-eigene Crash-Logging (last_crash.txt); Signature braucht
# Gson zur Typaufloesung, die Laufzeit-Annotationen braucht kotlinx-serialization.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Rename source files for security
-renamesourcefileattribute SourceFile

# ==============================
# UMBENENNUNG (OBFUSKATION) IST AN - SEIT ISSUE #54
# ==============================
#
# WARUM: Play prueft ab Februar 2027 bei Apps mit mehr als 10 MB DEX, dass Obfuskation,
# Optimierung UND Shrinking je mindestens 25 % erreichen. Gemessen am 01.10.2026 (r8-metadata.dat,
# R8 9.4.14): DEX 10 096 720 Byte, Optimierung 77,9 %, Shrinking 78,4 % - aber Obfuskation
# 0,01 %, weil hier bis dahin `-dontobfuscate` stand. Die Zeile ist deshalb entfernt.
# `tools/release/r8_kennzahlen.py` liest die Werte bei jedem CI- und Auslieferungslauf aus dem
# Bundle (BUNDLE-METADATA/com.android.tools/r8.json) und warnt, wenn sie wieder abrutschen.
#
# WAS ES KOSTET UND WIE ES BEZAHLT IST: Absturzprotokolle (last_crash.txt) und WARN/ERROR-Zeilen,
# die ein Tester per "Logs senden" schickt, zeigen jetzt `a.b.c(SourceFile:412)` statt Klassen-
# und Methodennamen. Die Zeilennummern bleiben (SourceFile/LineNumberTable oben), der Rest wird
# mit der mapping.txt DESSELBEN Builds zurueckuebersetzt. Die liegt an zwei Stellen:
#  - eingebettet im Bundle (BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map) -
#    Play uebersetzt damit Abstuerze in der Console selbst. Deshalb KEIN `mappingFile:` beim
#    Upload: Play lehnt eine zweite Mapping-Datei ab, wenn das Bundle schon eine traegt. Fehlt
#    die eingebettete, bricht die Auslieferung ab (r8_kennzahlen.py).
#  - als CI-Artefakt `mapping-<versionName>` (veroeffentlichen.yml, 90 Tage) fuer den lokalen
#    Blick; zuordnen ueber die Zeile "Version: <name> (<code>)" in last_crash.txt.
# Zurueckuebersetzen (R8 steckt im AGP-builder-JAR, die cmdline-tools braucht es nicht):
#   JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/builder/9.4.0/*/builder-9.4.0.jar)
#   java -cp "$JAR" com.android.tools.r8.retrace.Retrace mapping.txt last_crash.txt
# (AGP-Version an gradle/libs.versions.toml anpassen; am 02.10.2026 mit 9.4.0 ausprobiert.)
#
# WAS BEIM UMBENENNEN BRECHEN KANN - und welche Regel es haelt (Inventur 02.10.2026). Alles, was
# zur Laufzeit ueber einen NAMEN gefunden wird, braucht eine Regel ohne `allowobfuscation`:
#  - Gson (Hue-Antworten, Feldname = JSON-Schluessel): `hue.data.** { *; }` unten. NICHT anfassen.
#  - google-http-client (Kalender-Modelle, `@Key` ohne Wert = Feldname): `@Key <fields>` unten.
#  - WorkManager speichert den Worker-Klassennamen in seiner Datenbank: `-keepnames ... ListenableWorker`.
#  - Hilt (`@HiltViewModel`, `@EarlyEntryPoint`, LazyClassKey): eigene + generierte Regeln.
#  - Manifest-Komponenten (Activity, Service, Receiver, der Dimm-Dienst als Bedienungshilfe):
#    aapt-Regeln aus dem Manifest.
#  - kotlinx.serialization und Enum.name: Namen sind Compile-Zeit-Literale, keine Regel noetig.
#  - Protobuf-lite in Tink und DataStore: Consumer-Regeln der Bibliotheken (Feldnamen-Reflexion).
#  - `javaClass.simpleName` steht nur noch in Logzeilen (rein kosmetisch, Retrace hilft dort nicht).
# Eine Regel, die nur `-dontobfuscate` verdeckt haette, hat die Inventur nicht gefunden. Der
# Gegenbeweis ist der Release-Build am Geraet (Kalender laden, Hue, Export/Import, Wecker,
# pruefe_direct_boot.py) - kein Unit-Test sieht einen Namens-Fehler.

# ==============================
# BIBLIOTHEKEN: KEINE KEEP-ALLES-REGELN
# ==============================
#
# Jede Bibliothek bringt ihre eigenen Consumer-Regeln mit (alle zusammen stehen nach einem
# Release-Build in mapping/release/configuration.txt). Eine `-keep class <bibliothek>.** { *; }`
# haelt darueber hinaus die GANZE Bibliothek samt ungenutztem Code fest und nimmt R8 Shrinking und
# Optimierung. Gemessen am 29.09.2026: 20 solche Regeln hielten 14 368 Klassen; ohne sie war das
# Release-APK 42,7 % kleiner. Eigene Regeln fuer Bibliotheken nur dort, wo App-Code per Reflexion
# zugreift und keine Consumer-Regel das abdeckt - und dann so schmal wie moeglich.

# ==============================
# CRASH REPORTING & DEBUGGING
# ==============================

-keep public class * extends java.lang.Exception

# Keep error and warning logs for production debugging
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    # Keep these for production debugging:
    # public static *** w(...);
    # public static *** e(...);
    # public static *** wtf(...);
}

# Remove Android Log calls (except errors)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    # Keep warnings and errors for production
    # public static int w(...);
    # public static int e(...);
}

# ==============================
# KOTLIN & ANDROID CORE
# ==============================

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Android
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ==============================
# JETPACK COMPOSE
# ==============================

# WARUM hier kein `-keep @androidx.compose.runtime.Composable class * { *; }` mehr steht:
# `@Composable` traegt `@Target(FUNCTION, TYPE, TYPE_PARAMETER, PROPERTY_GETTER)` -
# `AnnotationTarget.CLASS` fehlt (androidx.compose.runtime 1.12.0, Composable.kt:34-58). Der
# Kotlin-Compiler laesst eine `@Composable`-Klassendeklaration also gar nicht erst zu; die Regel
# konnte seit dem Initial Commit nichts treffen. Verifiziert: in app/src/main gibt es keine
# einzige Klassen-, Objekt- oder Interface-Deklaration mit dieser Annotation.
#
# WARUM `-keepclasseswithmembers` statt `-keep`: bis v1.27.0 stand hier `-keep class * { ... }`.
# Die Klassenspezifikation `*` trifft JEDE Klasse, und `-keep` macht die getroffene Klasse zur
# Shrink- UND Obfuskations-Wurzel - unabhaengig davon, ob sie die genannten Member ueberhaupt
# besitzt. Damit war jede uebersetzte Klasse eine Wurzel: R8 hat seit dem Einschalten am
# 10.08.2026 nur noch MEMBER entfernt, keine einzige Klasse, und nichts obfuskiert. Nachgemessen
# am Release-Build vom 14.08.2026: mapping/release/seeds.txt fuehrte 37.511 Klassen als Wurzeln,
# darunter `kotlin.internal.InlineOnly` und `okhttp3.internal.**`, die keine andere keep-Regel im
# gemergten Regelsatz trifft. `-keepclasseswithmembers` haelt nur die Klassen, die tatsaechlich
# `@Composable`-Methoden HABEN - genau das, was gemeint war. Dieselbe korrekte Form benutzt die
# Datei weiter unten beim `@javax.inject.Inject`-Block bereits.
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ==============================
# DEPENDENCY INJECTION - HILT
# ==============================

# Hilt
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keepnames @dagger.hilt.android.EarlyEntryPoint class *

# Keep injection points
-keepclasseswithmembers class * {
    @javax.inject.Inject <fields>;
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ==============================
# GOOGLE SERVICES & AUTHENTICATION
# ==============================

# Google Play Services
-dontwarn com.google.android.gms.**

# Credential Manager findet den Play-Services-Anbieter per Reflexion ueber Manifest-Metadaten;
# die schmale Regel dafuer aus der androidx.credentials-Dokumentation.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
    *;
}

# ==============================
# GOOGLE CALENDAR API
# ==============================

# google-http-client liest und befuellt die Modelle (Event, EventDateTime, Request-Parameter,
# Fehlerantworten) per Reflexion ueber ihre @Key-Felder und erzeugt sie ueber den parameterlosen
# Konstruktor. Eigene Consumer-Regeln dafuer bringt die Bibliothek nicht mit.
# Seit die Obfuskation an ist (Issue #54), traegt diese Regel ZUSAETZLICH den Feldnamen: ein
# `@Key` ohne Wert nimmt den Java-Feldnamen als JSON-Schluessel (`summary`, `start`, `items`).
# Deshalb `-keepclassmembers` OHNE `allowobfuscation` - umbenannt kaeme jedes Kalender-Event leer an.
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keepclassmembers class * extends com.google.api.client.json.GenericJson {
    <init>();
}
-keepclassmembers enum * {
    @com.google.api.client.util.Value <fields>;
}

# HTTP Client
-dontwarn com.google.api.client.http.**

# ==============================
# NETWORKING - OKHTTP
# ==============================

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ==============================
# DATA SERIALIZATION
# ==============================

# Gson
-dontwarn sun.misc.**
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin Serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ==============================
# ANDROIDX LIBRARIES
# ==============================

# DataStore
-keep class * extends androidx.datastore.core.Serializer { *; }

# WorkManager
-keep class * extends androidx.work.ListenableWorker
-keepnames class * extends androidx.work.ListenableWorker

# Lifecycle
-keep class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ==============================
# APPLICATION SPECIFIC RULES
# ==============================

# Keep application class
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication { *; }

# Keep all activities
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity { *; }

# Keep receivers
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmReceiver { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver.BootReceiver { *; }

# Keep all data models
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.model.** { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.data.** { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.** { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.** { *; }

# Keep UI states
-keep class **.*State { *; }
-keep class **.*Event { *; }

# Keep ViewModels
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.** { *; }

# Keep services
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.service.** { *; }

# Keep repository interfaces and implementations
-keep interface com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.** { *; }
-keep class * implements com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.**

# Keep usecase interfaces and implementations
-keep interface com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.** { *; }
-keep class * implements com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.**

# Keep Hue integration
-keep interface com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.** { *; }
-keep interface com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.** { *; }
-keep class * implements com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.**
-keep class * implements com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.**

# ==============================
# SUPPRESS WARNINGS
# ==============================

# Common warnings that can be safely ignored
-dontwarn org.apache.commons.**
-dontwarn org.apache.http.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.joda.time.**
-dontwarn java.lang.invoke.**

# ==============================
# APACHE COMMONS LOGGING FIX
# ==============================

# Fix for Log4J warnings from Apache Commons Logging
# The Log4JLogger is an optional implementation that we don't use
-dontwarn org.apache.log4j.**

# Keep Commons Logging interfaces but allow implementation removal
-keep interface org.apache.commons.logging.Log { *; }

# Safely ignore missing Log4J classes (we use Android logging instead)
-dontnote org.apache.commons.logging.impl.Log4JLogger
-dontnote org.apache.log4j.**

# ==============================
# OPTIMIZATIONS FOR APK SIZE
# ==============================

# Remove unused resources
# -dontshrink    AUS seit 10.08.2026: mit dieser Zeile waere isMinifyEnabled=true eine
#                Attrappe - R8 laeuft, entfernt aber nichts. Siehe build.gradle.kts.
# -dontoptimize  AUS seit 10.08.2026 (war "Temporarily disabled for stability").
#                Am Geraet verifiziert: Release-APK mit vollem Happy Path lauffaehig.
#
# ACHTUNG, gelernt in Pruefrunde 6: Diese beiden Zeilen sind NICHT die einzige Tuer zur Attrappe.
# Vom 10.08. bis 18.08.2026 war Minify trotz auskommentiertem `-dontshrink` auf Klassenebene
# wirkungslos - nicht wegen einer Global-Direktive, sondern wegen zweier `-keep class *`-Regeln
# (je eine im damaligen Compose- und ashmem-Block), die jede Klasse zur Wurzel machten. Wer die
# Wirksamkeit von R8 pruefen will, prueft deshalb das ARTEFAKT, nicht die Konfiguration:
#   mapping/release/seeds.txt darf nicht annaehernd so viele Klassen fuehren wie mapping.txt.
# Seit Issue #54 benennt R8 wieder um - eine mapping.txt, in der das eigene Paket NICHT
# verschleiert ist, ist damit wieder ein Warnsignal. Der schnellste Blick darauf ist der
# Obfuskations-Wert, den tools/release/r8_kennzahlen.py aus dem Bundle liest.

# ==============================
# TINK CRYPTO ENCRYPTION (AES-256-GCM)
# ==============================

# Keep AEAD primitive
-keep class * extends com.google.crypto.tink.Aead { *; }

# Keep Protobuf classes used by Tink
-dontwarn com.google.protobuf.**

# Suppress warnings from Tink
-dontwarn com.google.crypto.tink.**

# ==============================
# TOKEN ENCRYPTION SPECIFIC
# ==============================

# Keep custom encryption helper
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedDataStoreFactory { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionException { *; }

# Google API Client
-assumenosideeffects class com.google.api.client.util.LoggingStreamingContent {
    <init>(...);
}

# Ungenutzte Log4J-Implementierung aus commons-logging
-assumenosideeffects class org.apache.commons.logging.impl.Log4JLogger {
    <init>(...);
    public void trace(...);
    public void debug(...);
    public void info(...);
}

# Generierte Klassen
-keep class **_Impl { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }