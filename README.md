# CF Alarm for Time Office

<div align="center">

![CF Alarm Logo](https://img.shields.io/badge/CF%20Alarm-Time%20Office-blue?style=for-the-badge)
![Android](https://img.shields.io/badge/Android-8.0+-green?style=for-the-badge&logo=android)
![Status](https://img.shields.io/badge/Status-Interner%20Alpha--Test-orange?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)

**Automatische Wecker aus dem Google-Dienstplan-Kalender – mit optionaler Philips-Hue-Sonnenaufgangssimulation**

[📖 Dokumentation & Support](https://cf-alarm.duckdns.org) • [📲 Play Store (interner Test)](https://play.google.com/store/apps/details?id=com.github.f1rlefanz.cf_alarmfortimeoffice)

</div>

## ⚠️ Alpha-Test-Status

Diese App befindet sich aktuell in einem **internen Alpha-Test**. Sie ist **nicht** öffentlich im Play Store veröffentlicht und wird ausschließlich an eine kleine Gruppe eingeladener Tester verteilt (aktuell: Kolleginnen und Kollegen aus der Pflege).

Was das bedeutet:
- Es kann Bugs geben – Feedback ist ausdrücklich erwünscht.
- Funktionsumfang und Bedienung können sich noch ändern.
- Die App wird von **einer Einzelperson** in der Freizeit entwickelt, nicht von einem Unternehmen.
- Es gibt (noch) keinen offiziellen Support-Kanal – Rückmeldungen bitte direkt an den Entwickler.

> 💡 **Problem melden:** In der App unter **Einstellungen → Diagnose → „Logs senden / Problem melden"** kannst du das Diagnose-Protokoll direkt an den Entwickler schicken (per E-Mail an **cfischer@csj.de**). Das hilft enorm bei der Fehlersuche.

## 🚀 Was die App macht

CF Alarm liest Schichttermine aus einem Google-Kalender (z. B. dem Dienstplan-Kalender "TimeOffice") und stellt daraus automatisch passende Wecker – ohne dass der Dienstplan manuell abgetippt werden muss.

### 📅 Google Calendar Integration
- **Automatische Alarm-Erstellung** aus Kalenderterminen
- **OAuth 2.0** für die Google-Anmeldung (Google Sign-In)
- **Schichtmuster-Erkennung** für Früh-, Spät- und Nachtschicht (konfigurierbare Stichwörter)
- **Konfigurierbare Vorlaufzeit** vor Schichtbeginn

### 💡 Philips Hue Integration (optional)
- **Sonnenaufgangs-Simulation** zum sanften Wecken
- **Automatische Bridge-Erkennung** im lokalen Netzwerk
- Verschiedene Licht-Profile je nach Schichtart

### 🔒 Sicherheit & Datenhaltung
- Tokens werden lokal mit **AES-256-GCM** (Google Tink) verschlüsselt gespeichert
- Keine eigene Cloud-Anbindung – Daten bleiben auf dem Gerät bzw. bei Google/Hue direkt
- Manuelle Alarme als Fallback, falls der Kalender mal nicht aktuell ist oder die Calendar-API nicht erreichbar ist

## 📱 Voraussetzungen

- **Android 8.0** (API Level 26) oder höher
- Ein **Google-Konto** mit Zugriff auf den Dienstplan-Kalender
- Optional: eine **Philips Hue Bridge** im selben WLAN, falls die Lichtsteuerung genutzt werden soll

## ✅ So nimmst du am Alpha-Test teil

1. **Kurze Nachricht an den Entwickler** – per E-Mail an **cfischer@csj.de** oder via GitHub ([@F1rlefanz](https://github.com/F1rlefanz)) – mit der Bitte um Zugang zum internen Test.
2. Du erhältst einen **Installationslink** (Play-Store-Track für eingeladene Tester oder direkte APK).
3. **App installieren** und öffnen.
4. **Google-Konto verbinden**, um Kalenderzugriff zu erlauben.
5. **Schichtzeiten/-muster konfigurieren**, passend zum eigenen Dienstplan.
6. Optional: **Philips Hue Bridge** suchen und verbinden.
7. **Testalarm auslösen** und prüfen, ob Zeitpunkt und Verhalten passen.

Rückmeldungen, Abstürze und ungewöhnliches Verhalten bitte direkt an den Entwickler melden – dafür ist der Alpha-Test da.

## 🔧 Konfiguration im Überblick

- **Kalender-Filter** – welcher Kalender (z. B. "TimeOffice") ausgewertet wird
- **Weckzeit-Vorlauf** – wie viel Zeit vor Schichtbeginn geweckt wird
- **Schichtmuster** – Stichwort-Zuordnung für Früh-/Spät-/Nachtschicht
- **Snooze-Verhalten** – anpassbar je Schichtart
- **Hue-Szenarien** – unterschiedliche Lichtprofile je Situation

## 👨‍💻 Für Entwickler

Der Code folgt Clean Architecture + MVVM mit Hilt als DI-Framework (Details siehe [`CLAUDE.md`](./CLAUDE.md)).

**Build-Voraussetzung:** Eine `keystore.properties`-Datei im Projekt-Root mit u. a. `googleWebClientId` (Google OAuth Client-ID). Ohne diesen Wert bricht der Build bewusst mit einer `GradleException` ab – es gibt keinen hardcodierten Fallback.

```bash
# Debug-Build
./gradlew assembleDebug

# Unit-Tests
./gradlew test

# Lint
./gradlew lint
```

Eigene `SETUP.md`/`SECURITY.md`-Dokumente gibt es aktuell nicht – die relevanten Hinweise stehen in `CLAUDE.md` und in diesem README. Wer zum Projekt beitragen möchte, meldet sich am einfachsten direkt beim Entwickler.

## 📄 Lizenz

Dieses Projekt steht unter der **MIT License** – siehe [LICENSE](LICENSE).

## 🌟 Credits

<div align="center">

**Entwickelt von einer Einzelperson für Kolleginnen und Kollegen im Schichtdienst**

Gebaut mit Android Studio, unterstützt durch Claude und Gemini als Entwicklungswerkzeuge.

⭐ **Feedback und Sternchen sind willkommen!** ⭐

---

**Entwickelt mit ❤️ in Deutschland von [F1rlefanz](https://github.com/F1rlefanz)**

</div>
