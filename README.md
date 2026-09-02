# CF Alarm for Time Office

<div align="center">

![CF Alarm Logo](https://img.shields.io/badge/CF%20Alarm-Time%20Office-blue?style=for-the-badge)
![Android](https://img.shields.io/badge/Android-8.0+-green?style=for-the-badge&logo=android)
![Status](https://img.shields.io/badge/Status-Interner%20Alpha--Test-orange?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)

**Automatische Wecker aus dem Google-Dienstplan-Kalender – mit Schicht-Dimmer, „Nicht stören" und optionaler Philips-Hue-Sonnenaufgangssimulation**

[📖 Dokumentation & Support](https://cf-alarm.duckdns.org) • [📲 Play Store (interner Test)](https://play.google.com/store/apps/details?id=com.github.f1rlefanz.cf_alarmfortimeoffice)

</div>

## ⚠️ Alpha-Test-Status

Die App wird im **internen Alpha-Test** verteilt. Sie ist **nicht** öffentlich im Play Store veröffentlicht und geht ausschließlich an eine kleine Gruppe eingeladener Tester (aktuell: Kolleginnen und Kollegen aus der Pflege). Welche Version gerade aktuell ist, steht im [Changelog](https://cf-alarm.duckdns.org/changelog.html) — hier stand die Nummer bis September 2026 fest im Text und war irgendwann fünfzehn Versionen alt.

Was das bedeutet:
- Es kann Bugs geben – Feedback ist ausdrücklich erwünscht.
- Funktionsumfang und Bedienung können sich noch ändern.
- Die App wird von **einer Einzelperson** in der Freizeit entwickelt, nicht von einem Unternehmen.
- Es gibt (noch) keinen offiziellen Support-Kanal – Rückmeldungen bitte direkt an den Entwickler.

> 💡 **Problem melden:** Im **Status**-Tab, Karte **„Debug-Informationen"** → **„Logs an Entwickler senden"**. Es öffnet sich der Teilen-Dialog, vorausgefüllt als E-Mail an **cfischer@csj.de** (eine andere App geht auch). Angehängt werden die Log-Dateien der letzten 8 Tage – das hilft enorm bei der Fehlersuche.

## 🚀 Was die App macht

CF Alarm liest Schichttermine aus einem Google-Kalender (z. B. dem Dienstplan-Kalender „TimeOffice") und stellt daraus automatisch passende Wecker – ohne dass der Dienstplan manuell abgetippt werden muss.

Die App hat sechs Tabs: **Home**, **Wecker**, **Dimmen**, **Hue**, **Status**, **Einstellungen**.

### 📅 Kalender & Schichterkennung
- **Automatische Wecker** aus Kalenderterminen der selbst ausgewählten Kalender
- **OAuth 2.0** für die Google-Anmeldung (Google Sign-In), nur Lesezugriff auf Kalender
- **Schichttypen mit eigenen Mustern**: pro Schichttyp eine feste Weckzeit (z. B. Frühschicht 05:30) und beliebig viele Erkennungsmuster; der Name des Schichttyps zählt ab zwei Zeichen selbst als Muster. Gematcht wird auf **Wortgrenzen** – „F" trifft einen Termin „F", nicht „Fortbildung".
- **Kürzel-Vorschläge aus dem echten Kalender** (neu in 1.23.0): die Karte „Diese Kürzel stehen in deinem Kalender" zeigt die Termintitel, die von keinem aktiven Muster getroffen werden, sortiert nach Häufigkeit. Antippen ordnet das Kürzel einem Schichttyp zu. Die App ordnet **nichts** von selbst zu – ein falsch geratenes Kürzel wäre ein Wecker zur falschen Zeit.
- **Stille Schicht**: kein Ton/keine Vibration/kein Vollbild, die Weckzeit bleibt aber als Anker für Dimmer und „Nicht stören" erhalten (z. B. Rufbereitschaft)
- **Nächsten Alarm einmalig überspringen** (Wecker-Tab), zeitbasiert und wieder aufhebbar
- **Schlummer-Dauer** global einstellbar (3/5/10/15 Minuten, Wecker-Tab) – ein Wert für Vollbild- und Benachrichtigungs-Knopf
- **Manueller Alarm** als Fallback, wenn der Dienstplan mal nicht aktuell oder die Calendar-API nicht erreichbar ist

### 🛡️ Zuverlässigkeit im Hintergrund
- **Wartungskette alle 6 Stunden**: Token erneuern, Kalender abfragen, Wecker neu setzen – erkennt auch **geänderte und gestrichene** Schichten, nicht nur neue
- **Auffrischung 3 Stunden vor jeder Weckzeit**, damit kurzfristige Dienstplan-Änderungen den nächsten Wecker noch erreichen
- **Nach Neustart und App-Update** werden alle Wecker wiederhergestellt
- **Benachrichtigung bei Schicht-Änderungen** („Schicht-Änderung", abschaltbar in den Einstellungen)
- **Status-Tab** zeigt, was den Wecker draußen aushebeln kann: Akku-Optimierung, Androids „App bei Nichtnutzung pausieren" – und eine eigene Karte für **TimeOffice** selbst, denn dessen Sync ist die vorgelagerte Datenquelle (fällt er aus, ist der Dienstplan-Kalender veraltet, ohne dass CF Alarm etwas merkt)
- **Hintergrunddienste pausieren** (Einstellungen): ein Schalter pausiert alles – Wecker, Dimmer, „Nicht stören", Hue-Automatik und die 6h-Wartung selbst – für längere Abwesenheit

### 🌙 Schicht-Dimmer (optional)
- **Nacht-Standard**: dimmt ab einer festen Uhrzeit bis zum nächsten CF-Alarm-Wecker, ohne dass dafür eine Regel angelegt werden muss; einzelne Schichten lassen sich per Chip ausnehmen
- **Wellness (Wind-down)**: dunkelt eine Weile vor jeder Weckzeit ab
- **Schicht-Regeln**: frei definierbare Zeitfenster, gekoppelt an die erkannten Schichten (z. B. nachts dimmen, aber nicht an Nachtdienst-Nächten), mit Vorschau für die nächsten Tage
- **Korrektur direkt aus der Benachrichtigung**: „Heller", „Dunkler", „Pause" für das laufende Fenster
- Abgedunkelt wird über einen **Bedienungshilfe-Dienst**, der in Androids Einstellungen selbst aktiviert werden muss. Er legt ausschließlich eine dunkle Schicht über den Bildschirm – er liest keine Bildschirminhalte und wertet keine Eingaben aus.

### 🔕 Nicht stören automatisch (optional, ab Android 11)
- Zwei unabhängig schaltbare Auslöser: **„Schlaf-Fenster folgt dem Dimmer"** und **„Während der Dienstzeit"** (einzelne Schichten ausnehmbar)
- **Rufbereitschaft**: an Tagen mit einer so markierten Schicht endet „Nicht stören" schon zu einer festen Uhrzeit (Standard 05:00) – ab dann bist du erreichbar
- Frei einstellbar, was stummgeschaltet wird (Anrufe, Nachrichten, Erinnerungen, Termine, Medien, Wecker anderer Apps …). Der eigene Wecker klingelt immer, dafür ist keine Ausnahme nötig.
- Umgesetzt als eigener Eintrag unter *Einstellungen → Ton → Nicht stören → Zeitpläne*, damit manuelles DND und fremde Automatisierungen unberührt bleiben

### 💡 Philips Hue Integration (optional)
- **Sonnenaufgangs-Simulation** zum sanften Wecken
- **Automatische Bridge-Erkennung** im lokalen Netzwerk (mDNS, N-UPnP, offizieller Endpunkt)
- **Regeln je Schichttyp** (oder für alle Schichten) mit Vorschau und Lampen-Test
- Das **Auto-Aus liegt als Zeitplan auf der Bridge**, nicht auf dem Handy – es greift also auch, wenn das Handy längst nicht mehr im Heim-WLAN ist

### 💾 Konfiguration exportieren / importieren (neu in 1.23.0)
- Einstellungen → **Konfiguration** → „Exportieren" / „Importieren" schreibt bzw. liest eine JSON-Datei über den System-Dateidialog
- Enthalten sind Schichttypen, Hue-Regeln, Dimmer-Regeln und die „Nicht stören"-Einstellungen – die Datei lässt sich weitergeben und funktioniert auch bei einer Neuinstallation auf demselben Gerät
- **Absichtlich nicht enthalten**: Anmeldung, Tokens, Kalenderauswahl, die Hue-Bridge-Zugangsdaten sowie Laufzeitzustand wie die Pause-Schalter. Das richtet man auf jedem Gerät selbst ein; ein importierter Pausenzustand hätte den Wecker stumm gelassen.
- Der Import fragt vorher nach, überschreibt die aktuelle Konfiguration und setzt danach alle Wecker neu

### 🔒 Sicherheit & Datenhaltung
- Tokens werden lokal mit **AES-256-GCM** (Google Tink) verschlüsselt gespeichert
- Keine eigene Cloud-Anbindung – Daten bleiben auf dem Gerät bzw. bei Google/Hue direkt
- Kalender-, Hue- und Token-Daten liegen in getrennten lokalen Speichern

## 📱 Voraussetzungen

- **Android 8.0** (API Level 26) oder höher; „Nicht stören"-Automatik ab **Android 11**
- Ein **Google-Konto** mit Zugriff auf den Dienstplan-Kalender
- Optional: eine **Philips Hue Bridge** im selben WLAN, falls die Lichtsteuerung genutzt werden soll

## ✅ So nimmst du am Alpha-Test teil

1. **Kurze Nachricht an den Entwickler** – per E-Mail an **cfischer@csj.de** oder via GitHub ([@F1rlefanz](https://github.com/F1rlefanz)) – mit der Bitte um Zugang zum internen Test.
2. Du erhältst einen **Installationslink** (Play-Store-Track für eingeladene Tester oder direkte APK).
3. **App installieren** und öffnen.
4. **Google-Konto verbinden** und die auszuwertenden Kalender auswählen.
5. **Schichttypen prüfen**: Weckzeiten anpassen und die Kürzel deiner Station zuordnen – dafür ist die Karte „Diese Kürzel stehen in deinem Kalender" da. Ohne passendes Muster klingelt kein Wecker.
6. Optional: **Schicht-Dimmer**, **„Nicht stören"** und **Philips Hue** einrichten.
7. **Manuellen Alarm auslösen** und prüfen, ob Verhalten und Lautstärke passen.

Rückmeldungen, Abstürze und ungewöhnliches Verhalten bitte direkt an den Entwickler melden – dafür ist der Alpha-Test da.

## 🔧 Konfiguration im Überblick

- **Kalenderauswahl** – welche Kalender (z. B. „TimeOffice") ausgewertet werden
- **Schichttypen** – Weckzeit, Erkennungsmuster, aktiv/inaktiv, „Stille Schicht"
- **Schlummer-Dauer** – global 3/5/10/15 Minuten (Wecker-Tab)
- **Automatische Alarme** – Hauptschalter im Wecker-Tab; „Nächsten Alarm überspringen" für den Einzelfall
- **Schicht-Dimmer** – Nacht-Standard, Wellness-Wind-down, eigene Regeln
- **Nicht stören** – zwei Auslöser, Rufbereitschaft-Cutoff, frei wählbare Ausnahmen
- **Hue-Regeln** – Lichtprofile je Schichttyp, mit oder ohne Sonnenaufgang
- **Benachrichtigungen** – Schicht-Änderungen, Dimmer-Korrektur
- **Hintergrunddienste pausieren** – alles aus für längere Abwesenheit
- **Konfiguration exportieren/importieren** – als Datei

## 👨‍💻 Für Entwickler

Der Code folgt Clean Architecture + MVVM mit Hilt als DI-Framework (Details siehe [`CLAUDE.md`](./CLAUDE.md)): Jetpack Compose (Material 3) → ViewModels mit StateFlow → Use Cases → Repositories → DataStore/Google-APIs/Hue-Bridge. Hintergrundarbeit läuft über AlarmManager (exakte Alarme) und WorkManager.

- `minSdk = 26`, `targetSdk = 37`, `compileSdk = 37`, Java 17 mit Core Library Desugaring
- **Release-Builds laufen durch R8** (`isMinifyEnabled`/`isShrinkResources = true`, seit 10.08.2026 wieder aktiv, nachdem AGP 9.3.1 den R8-NPE behoben hat); `debug` und `staging` bleiben unminifiziert. `assembleRelease` braucht **Netzzugang** – mit `--offline` scheitert es an einer nur im Minify-Pfad benötigten Abhängigkeit.
- Über 550 Unit-Tests in `app/src/test` plus Instrumentation-Smoke-Tests, die Application und MainActivity gegen den echten, unveränderten Hilt-Graphen hochfahren (fängt Konstruktions-Fehler, die kein Unit-Test nachbildet)
- CI (`.github/workflows/ci.yml`) baut `testDebugUnitTest`, `lintDebug`, `assembleDebug` **und** den Release-Pfad (`lintVitalRelease`, `assembleRelease`); ohne Keystore-Secret entsteht dabei bewusst eine unsignierte APK

**Build-Voraussetzung:** Eine `keystore.properties`-Datei im Projekt-Root mit u. a. `googleWebClientId` (Google OAuth Client-ID). Ohne diesen Wert bricht der Build bewusst mit einer `GradleException` ab – es gibt keinen hardcodierten Fallback.

```bash
# Debug-Build
./gradlew assembleDebug

# Unit-Tests
./gradlew test

# Lint
./gradlew lint

# Release-Build (braucht keystore.properties und Netzzugang)
./gradlew assembleRelease
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
