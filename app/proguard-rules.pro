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
# CRASH REPORTING & DEBUGGING
# ==============================

# Firebase Crashlytics
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

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
-keep @androidx.compose.runtime.Composable class * { *; }
-keep class * {
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

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

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

# Security Crypto
-keep class androidx.security.crypto.** { *; }

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
-dontwarn org.apache.commons.logging.impl.Log4JLogger
-dontwarn org.apache.http.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.joda.time.**
-dontwarn java.lang.invoke.**

# ==============================
# OPTIMIZATIONS FOR APK SIZE
# ==============================

# Remove unused resources
-dontshrink
-dontoptimize # Temporarily disabled for stability

# Enable R8 full mode optimizations in gradle
# android.enableR8.fullMode=true