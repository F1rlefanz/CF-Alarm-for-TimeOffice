# 📋 CHANGELOG

Alle wichtigen Änderungen an diesem Projekt werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/),
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.5] - 2025-08-27

### 🔥 Firebase Crashlytics Integration
- **Non-Fatal Error Monitoring**: Vollständige Sichtbarkeit in App-Probleme
- **3 Critical Failure Points**: Google Auth, Calendar API, Hue Bridge Timeouts
- **App State Context**: Automatische Erfassung von Netzwerk, Battery, Device Info
- **Error Categorization**: Strukturierte Firebase Dashboard-Filter

### 🔧 Code Quality
- **Memory Leak Fix**: WeakReference in HueBridgeConnectionManager 
- **Lint Warnings**: Alle 8+ Warnungen behoben (unused parameters, API checks)
- **Build Optimization**: Deprecated buildDir ersetzt
- **Debug Testing**: CrashlyticsTestUtils für Validierung

### 🎯 Production Ready
- **Release Build**: Crashlytics nur in Production aktiv
- **Custom Keys**: Firebase Dashboard-Filter (auth, calendar, hue, network)
- **Breadcrumb Logging**: Strukturierte Debugging-Information

---

## [1.0.4] - 2025-08-26

### 🔐 Security (KRITISCH)
- **BREAKING**: Hardcoded Keystore-Passwörter aus build.gradle.kts entfernt
- **Secure Properties Loading**: Implementiert sichere Keystore-Konfiguration über keystore.properties
- **Password Rotation**: Produktions-Keystore-Passwörter geändert (alte Passwörter ungültig)
- **OAuth Client ID Cleanup**: Redundante hardcoded Client IDs aus BusinessConstants.kt entfernt
- **Environment Variables Support**: Fallback-Mechanismus für CI/CD-Umgebungen implementiert

### ✨ Improved
- **Build Configuration**: Professionelles Properties-basiertes Signing-System
- **Code Quality**: Eliminierung von Sicherheitslücken in der Build-Pipeline
- **Documentation**: Erweiterte Kommentierung der Sicherheitskonfiguration

### 🔧 Technical
- **PKCS12 Keystore**: Korrekte Handhabung identischer Store- und Key-Passwörter
- **Gradle Security**: Sichere Implementierung der Signing-Konfiguration
- **Git Security**: Sensitive Daten vollständig aus Versionskontrolle entfernt

### ⚠️ Breaking Changes
- **Entwickler-Setup**: `keystore.properties` muss lokal erstellt werden
- **Build-System**: Hardcoded Passwörter funktionieren nicht mehr
- **CI/CD**: Umgebungsvariablen für Keystore-Passwörter erforderlich

### 📝 Migration Guide
Für Entwickler, die dieses Update installieren:
1. Erstelle `keystore.properties` im Projektroot
2. Füge deine Keystore-Konfiguration hinzu
3. Stelle sicher, dass `keystore.properties` in `.gitignore` steht
4. Für CI/CD: Setze `KEYSTORE_PASSWORD` und `KEY_PASSWORD` Umgebungsvariablen

---

## [1.0.3] - 2025-08-23

### ✨ Features
- **Philips Hue Integration**: Vollständige Smart Light Unterstützung
- **Calendar Sync**: Google Calendar Integration optimiert
- **Shift Recognition**: Intelligente Schichtmuster-Erkennung
- **Android 14+ Support**: Modernste Android-Kompatibilität

### 🔧 Technical
- **Network Security**: HTTPS-basierte Hue Bridge Kommunikation
- **OAuth 2.0**: Moderne Credential Manager Integration
- **Background Services**: Optimierte WorkManager-Implementierung

---

## [1.0.0] - 2025-01-15

### 🎉 Initial Release
- **Core Functionality**: Automatische Wecker basierend auf Kalender-Events
- **Google Integration**: OAuth 2.0 Calendar API
- **Philips Hue**: Smart Light Wake-up Simulation
- **Shift Support**: Schichtarbeiter-optimierte Funktionen
- **Security**: Verschlüsselte Token-Speicherung
- **Android Support**: API 26+ (Android 8.0+)

### 🏗️ Architecture
- **Clean Architecture**: MVVM + Repository Pattern
- **Jetpack Compose**: Moderne Android UI
- **Hilt DI**: Dependency Injection
- **Kotlin Coroutines**: Asynchrone Programmierung
- **DataStore**: Sichere lokale Datenspeicherung

---

## Versionierung

- **MAJOR**: Inkompatible API-Änderungen
- **MINOR**: Neue Funktionen (rückwärtskompatibel)  
- **PATCH**: Bugfixes und Sicherheitsupdates

## Security Advisories

### CVE-2025-0001 (Behoben in 1.0.4)
**Schweregrad**: HOCH  
**Beschreibung**: Hardcoded Keystore-Passwörter in Versionskontrolle  
**Betroffene Versionen**: 1.0.0 - 1.0.3  
**Lösung**: Update auf Version 1.0.4+
