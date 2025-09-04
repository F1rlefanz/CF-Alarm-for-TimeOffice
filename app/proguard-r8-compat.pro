# R8-specific compatibility rules for CF Alarm
# These rules address specific R8 warnings that don't appear in regular ProGuard

# Apache Commons Logging - R8 specific (simplified syntax)
-dontwarn org.apache.commons.logging.impl.Log4JLogger
-dontwarn org.apache.commons.logging.impl.**
-keep class org.apache.commons.logging.impl.Log4JLogger { *; }

# Google API Client - R8 type checking issues
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.googleapis.**
-dontwarn com.google.auth.**

# Keep classes that R8 struggles to analyze
-keepnames class * extends java.util.logging.Handler
-keepnames class * extends org.apache.commons.logging.Log

# R8 optimization suppressions for stability
-keep class org.apache.commons.logging.** { *; }
