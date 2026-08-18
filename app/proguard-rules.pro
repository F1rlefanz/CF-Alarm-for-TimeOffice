# ==============================
# CF ALARM FOR TIME OFFICE - PROGUARD RULES
# ==============================
# Production-ready ProGuard configuration
# Version: 2.0 - Optimized for Play Store Release
# Last Updated: January 2025

# ==============================
# GLOBAL OPTIMIZATION SETTINGS
# ==============================

# Moderate optimization for stability (reduced from 5 to 3)
-optimizationpasses 3
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Safe optimizations that won't break Google APIs
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Keep important attributes for debugging
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Rename source files for security
-renamesourcefileattribute SourceFile

# ==============================
# KEINE UMBENENNUNG - SHRINKING JA, OBFUSKATION NEIN
# ==============================
#
# WELCHER ABLAUF SONST KAPUTT GEHT: Bis v1.27.0 standen weiter unten zwei Regeln der Form
# `-keep class * { ... }`. Die Klassenspezifikation `*` machte JEDE uebersetzte Klasse zur
# Keep-Wurzel, R8 hat also seit dem Einschalten am 10.08.2026 nichts entfernt und NICHTS
# umbenannt (nachgemessen: 1828 mapping.txt-Eintraege des eigenen Pakets, kein einziger
# verschleiert). Mit der Korrektur auf `-keepclasseswithmembers`/`-keepclassmembers` faellt diese
# Wurzelwirkung weg - der Release-Build wuerde ab sofort ZUM ERSTEN MAL den gesamten App-Code
# umbenennen.
#
# Das kollidiert frontal mit der einzigen Diagnosequelle dieser App: Absturzprotokolle und
# WARN/ERROR-Zeilen, die ein Alpha-Tester per "Logs senden" schickt (last_crash.txt bzw. der
# SimpleFileTree). Dafuer haelt die Zeile oben ausdruecklich SourceFile und LineNumberTable - die
# Zeilennummern blieben also, Klassen- und Methodennamen aber nicht, und
# `-renamesourcefileattribute` ersetzt zusaetzlich den Dateinamen. Uebrig bliebe `a.b.c(SourceFile:412)`.
# Zurueckuebersetzen liesse sich das nur mit der mapping.txt DIESES Builds - und die wird nirgends
# archiviert (weder ci.yml noch build.gradle.kts sichern app/build/outputs/mapping/release/).
#
# Das Shrinking bleibt eingeschaltet und ist der eigentliche Gewinn (Groesse); das Umbenennen
# bringt hier fast nichts und kostet die Fehlersuche. Ausserdem hat noch nie ein Release-Build mit
# wirksamem Shrinking auf einem Geraet gelaufen - eine Umbenennung obendrauf waere in derselben
# Version die zweite unerprobte Aenderung, und reflexionsbedingte Ausfaelle sieht die CI nicht
# (sie baut die Release-APK, fuehrt sie aber nicht aus).
#
# WANN DIESE ZEILE WEG DARF: sobald die mapping.txt je Release archiviert wird (CI-Artefakt oder
# Play-Console-Upload) UND ein Release-Build am Geraet durchgespielt wurde. Vorher nicht.
-dontobfuscate

# ==============================
# CRASH REPORTING & DEBUGGING
# ==============================

# Lesbare Stacktraces fuers app-eigene Crash-Logging (last_crash.txt)
-keepattributes SourceFile,LineNumberTable
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
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.** { *; }
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

# Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }

# Compose Compiler
-dontwarn androidx.compose.**

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
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
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
-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Google Sign-In
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Credentials API
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# ==============================
# GOOGLE CALENDAR API
# ==============================

# Google Auth Library
-keep class com.google.auth.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.calendar.** { *; }

# HTTP Client
-keep class com.google.api.client.http.** { *; }
-keep class com.google.api.client.json.** { *; }
-dontwarn com.google.api.client.http.**

# ==============================
# NETWORKING - RETROFIT & OKHTTP
# ==============================

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ==============================
# DATA SERIALIZATION
# ==============================

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
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
-keep class androidx.datastore.** { *; }
-keep class * extends androidx.datastore.core.Serializer { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keepnames class * extends androidx.work.ListenableWorker

# Lifecycle
-keep class androidx.lifecycle.** { *; }
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
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.data.** { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.shift.data.** { *; }

# Keep UI states
-keep class **.*UiState { *; }
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
# LOGGING LIBRARIES & SLF4J FIX
# ==============================

# SLF4J Logging (Fix for Google Auth Libraries)
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }

# Google Auth OAuth2 Library specific
-keep class com.google.auth.oauth2.** { *; }
-dontwarn com.google.auth.oauth2.Slf4jUtils**

# ==============================
# SUPPRESS WARNINGS
# ==============================

# Common warnings that can be safely ignored
-dontwarn javax.annotation.**
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
-dontwarn org.apache.commons.logging.impl.Log4JLogger
-dontwarn org.apache.log4j.**

# Keep Commons Logging interfaces but allow implementation removal
-keep interface org.apache.commons.logging.Log { *; }
-keep interface org.apache.commons.logging.LogFactory { *; }

# Safely ignore missing Log4J classes (we use Android logging instead)
-dontnote org.apache.commons.logging.impl.Log4JLogger
-dontnote org.apache.log4j.**

# ==============================
# 🛠️ CRITICAL FIX: ASHMEM PINNING COMPATIBILITY
# ==============================

# Fix for "Pinning is deprecated since Android Q" warning
# Suppress ashmem-related warnings and obfuscate problematic methods
-dontwarn android.os.SharedMemory
-dontwarn dalvik.system.VMRuntime
-dontwarn libcore.io.AshmemPinning
-dontwarn android.os.PinningHelperHooks

# WARUM `-keepclassmembers` statt `-keep`: bis v1.27.0 stand hier `-keep,allowobfuscation class *`.
# `allowobfuscation` erlaubt lediglich das Umbenennen, es hebt die Wurzel-Wirkung von `-keep` nicht
# auf - die Klassenspezifikation `*` machte also auch hier JEDE Klasse des Programms un-entfernbar,
# voellig unabhaengig davon, ob sie pin/unpin/setPinned besitzt. Zusammen mit der Compose-Regel
# oben war das der zweite Grund, warum R8 keine einzige Klasse entfernt hat.
# Die Regel bleibt in der Member-Form stehen statt ersatzlos zu verschwinden, weil ihr erklaerter
# Zweck (die pin/unpin-Methoden nicht festnageln) davon unberuehrt bleibt. Wirkung hat sie
# vermutlich keine: im eigenen Code existiert keine solche Methode, und die Meldung
# "Pinning is deprecated since Android Q" kommt aus der Plattform, nicht aus App-Code - dagegen
# helfen die -dontwarn-Zeilen darueber, kein Keep.
-keepclassmembers,allowobfuscation class * {
    *** pin(...);
    *** unpin(...);
    *** setPinned(...);
}

# Additional compatibility for Android Q+ memory management
-keep class android.os.** { *; }
-dontwarn android.os.**$$*

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
# (Compose-Block und ashmem-Block, beide weiter oben), die jede Klasse zur Wurzel machten. Wer die
# Wirksamkeit von R8 pruefen will, prueft deshalb das ARTEFAKT, nicht die Konfiguration:
#   mapping/release/seeds.txt darf nicht annaehernd so viele Klassen fuehren wie mapping.txt.
# NICHT mehr ueber Umbenennungen pruefen: seit `-dontobfuscate` (siehe Begruendung ganz oben)
# benennt R8 bewusst nichts mehr um - eine mapping.txt ohne verschleierte Namen ist hier also
# der SOLL-Zustand und kein Hinweis auf eine Attrappe.

# Enable R8 full mode optimizations in gradle
# android.enableR8.fullMode=true

# ==============================
# TINK CRYPTO ENCRYPTION (AES-256-GCM)
# ==============================

# Keep Tink classes and methods
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }

# Keep AEAD primitive
-keep class * extends com.google.crypto.tink.Aead { *; }

# Keep Tink config and registration
-keep class * extends com.google.crypto.tink.config.TinkFips { *; }
-keepclassmembers class com.google.crypto.tink.config.** {
    public static *** register(...);
}

# Keep Android integration
-keep class com.google.crypto.tink.integration.android.** { *; }
-keep class * extends com.google.crypto.tink.integration.android.** { *; }

# Keep key templates
-keep class com.google.crypto.tink.KeyTemplate { *; }
-keep class com.google.crypto.tink.KeyTemplates { *; }
-keepclassmembers class com.google.crypto.tink.KeyTemplates {
    public static *** get(...);
}

# Keep Protobuf classes used by Tink
-keep class com.google.crypto.tink.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Suppress warnings from Tink
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ==============================
# TOKEN ENCRYPTION SPECIFIC
# ==============================

# Keep custom encryption helper
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedDataStoreFactory { *; }
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionException { *; }

# Keep DataStore serializers
-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedDataStoreFactory$* { *; }
-keep class * extends androidx.datastore.core.Serializer { *; }