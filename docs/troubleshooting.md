---
layout: default
title: Problemlösung
---

{% include navigation.md %}

# 🆘 Problemlösung

## 🔍 Schnelldiagnose

**Bevor Sie beginnen**: Aktualisieren Sie auf die neueste App-Version und starten Sie Ihr Gerät neu.

### ⚡ Häufigste Probleme (90% der Fälle)

1. **Alarme funktionieren nicht** → Batterieoptimierung deaktivieren
2. **Kalender synchronisiert nicht** → Internetverbindung & Berechtigungen prüfen  
3. **Hue-Lichter reagieren nicht** → Gleiches WLAN-Netzwerk überprüfen
4. **Android 14+ Probleme** → "Alarme & Erinnerungen" Berechtigung aktivieren

---

## 🚨 Alarm-Probleme

### OnePlus Geräte (ColorOS/OxygenOS)

**Problem:** Alarme werden nicht zuverlässig ausgelöst

**Lösungsschritte:**
1. **Einstellungen** → **Akku** → **Akkuoptimierung**
2. Nach **CF Alarm** suchen → **Nicht optimieren** wählen
3. **Einstellungen** → **Apps** → **CF Alarm** → **Akku**
4. **Hintergrundaktivität erlauben** aktivieren
5. **Automatisches Starten erlauben** aktivieren

> 💡 **OnePlus Tipp**: Auch **Einstellungen** → **Datenschutz-Berechtigungen** → **Startup Manager** → CF Alarm aktivieren prüfen

### Samsung Geräte (One UI 4.0+)

**Problem:** App wird im Hintergrund beendet

**Lösungsschritte:**
1. **Einstellungen** → **Apps** → **CF Alarm** → **Akku**
2. **Uneingeschränkt** für Akkuverbrauch wählen
3. **Einstellungen** → **Gerätewartung** → **Akku** → **App-Energieverwaltung**
4. **CF Alarm** zu **Nie ruhende Apps** hinzufügen
5. **Einstellungen** → **Apps** → **CF Alarm** → **Berechtigungen**
6. Sicherstellen, dass **"Über anderen Apps anzeigen"** aktiviert ist

### Xiaomi/MIUI Geräte

**Problem:** MIUI's aggressive Hintergrundverwaltung

**Lösungsschritte:**
1. **Einstellungen** → **Apps** → **Apps verwalten** → **CF Alarm**
2. **Autostart** aktivieren
3. **Akku & Leistung** → **Akku** → **App-Akku-Sparer**
4. CF Alarm auf **Keine Einschränkungen** setzen
5. **Weitere Berechtigungen** → **Pop-up-Fenster anzeigen** aktivieren

### Huawei/Honor Geräte (EMUI/MagicUI)

**Problem:** Ultra-aggressive Energieverwaltung

**Lösungsschritte:**
1. **Einstellungen** → **Akku** → **Start**
2. **CF Alarm** finden → **Manuell verwalten**
3. ALLE drei Optionen aktivieren:
   - **Automatischer Start** ✅
   - **Sekundärer Start** ✅  
   - **Im Hintergrund ausführen** ✅
4. **Einstellungen** → **Apps & Benachrichtigungen** → **CF Alarm** → **Akku**
5. **Nicht optimieren** wählen

---

## 📅 Kalender-Synchronisation Probleme

### Authentifizierung-Probleme

#### "Anmeldung erforderlich" Fehler
**Ursache:** OAuth-Token abgelaufen oder widerrufen

**Lösung:**
1. CF Alarm öffnen → **Einstellungen** → **Konto**
2. **Abmelden** → **Erneut anmelden**
3. Alle angeforderten Berechtigungen gewähren
4. Korrekten Arbeitskalender auswählen

#### Falscher Kalender ausgewählt  
**Ursache:** Mehrere Kalender verfügbar

**Lösung:**
1. **Einstellungen** → **Kalenderauswahl**
2. Ihren Arbeitskalender wählen (nicht privat)
3. Überprüfen, ob Kalender Arbeitstermine enthält
4. **Synchronisieren** zum Aktualisieren

### Netzwerk & Synchronisation Probleme

#### Kalenderereignisse werden nicht geladen
**Diagnose:**
```
Einstellungen → Info → Verbindungstest
```

**Lösungen:**
1. **WLAN-Probleme**: Vorübergehend zu mobilen Daten wechseln
2. **Proxy/VPN**: Vorübergehend deaktivieren zum Testen
3. **Unternehmens-Firewall**: Freigabe für `*.googleapis.com` anfordern
4. **Cache leeren**: Einstellungen → Apps → CF Alarm → Speicher → Cache leeren

---

## 💡 Philips Hue Probleme

### Bridge-Erkennungsprobleme

#### Bridge nicht gefunden
**Diagnose:**
1. Beide Geräte im gleichen WLAN-Netzwerk? ✅
2. Bridge Power-LED leuchtet kontinuierlich blau? ✅
3. Hue-App funktioniert auf dem gleichen Gerät? ✅

**Lösungen:**
1. **Netzwerk-Reset**: WLAN-Router neu starten
2. **Bridge-Reset**: Bridge 30 Sekunden trennen, wieder verbinden
3. **Manuelle IP**: Einstellungen → Hue → Manuelle Bridge-IP
4. **UPnP prüfen**: UPnP am Router aktivieren (falls deaktiviert)

### Lichtsteuerungs-Probleme

#### Lichter reagieren nicht
**Diagnose in Hue-App:**
1. Können Sie Lichter manuell steuern? ✅
2. Sind Lichter im richtigen Raum/Gruppe? ✅
3. Bridge-Firmware aktualisiert? ✅

**CF Alarm Lösungen:**
1. **Einstellungen** → **Hue** → **Lichter aktualisieren**
2. **Lichter testen**: Test-Button für jedes Licht tippen
3. **Bridge neu koppeln**: Bridge entfernen und wieder hinzufügen

---

## 🔔 Benachrichtigungs- & Berechtigungsprobleme

### Android 13/14 spezifische Probleme

#### Fehlende "Alarme & Erinnerungen" Berechtigung
**Problem:** Neue Android 14+ Berechtigung nicht gewährt

**Lösung:**
1. **Einstellungen** → **Apps** → **CF Alarm** → **Berechtigungen**
2. **Spezieller App-Zugriff** → **Alarme & Erinnerungen**
3. **CF Alarm** aktivieren ✅
4. **App neu starten** um Änderungen anzuwenden

### Nicht-Stören-Probleme

#### Alarme durch Nicht-Stören stumm
**Problem:** Nicht-Stören blockiert Alarmtöne

**Lösung:**
1. **Einstellungen** → **Ton** → **Nicht stören**
2. **Ausnahmen** → **Apps** → **CF Alarm** hinzufügen
3. **ODER** **Alarme** → **Immer erlauben** ✅

---

## ⚡ Leistungs- & Akkuprobleme

### Hoher Akkuverbrauch

#### Hintergrundaktivitäts-Analyse
**Verbrauch prüfen:**
1. **Einstellungen** → **Akku** → **CF Alarm**
2. **Hintergrundaktivität** Prozentsatz überprüfen
3. **Erwartet**: 2-5% täglicher Verbrauch
4. **Übermäßig**: 10%+ deutet auf Probleme hin

**Optimierung:**
1. **Sync-Häufigkeit reduzieren**: Einstellungen → Kalender → Sync-Intervall → 30 Minuten
2. **Hue-Features deaktivieren**: Falls nicht benötigt
3. **Standortdienste**: Deaktivieren falls nicht verwendet
4. **Hintergrund-Aktualisierung**: Einstellungen → Apps → CF Alarm → Akku → Optimieren

---

## 🔧 Erweiterte Problembehandlung

### Entwickleroptionen-Debug

#### Detaillierte Protokollierung aktivieren
1. **Einstellungen** → **Info** → **Version** 7x tippen
2. **Entwickleroptionen** freigeschaltet
3. **CF Alarm Einstellungen** → **Entwickleroptionen** → **Ausführliche Protokollierung** ✅
4. **Problem reproduzieren**
5. **Einstellungen** → **Support** → **Debug-Logs exportieren**

### Factory Reset (Nukleare Option)

**Wenn alles andere fehlschlägt:**

⚠️ **WARNUNG**: Dies löscht ALLE App-Daten einschließlich:
- Alle Alarmkonfigurationen
- Google-Kontoverbindung  
- Philips Hue Kopplungen
- Benutzerdefinierte Einstellungen

**Schritte:**
1. **Erst Backup**: Einstellungen → Backup → Einstellungsdatei exportieren
2. **Einstellungen** → **Apps** → **CF Alarm** → **Speicher**
3. **Daten löschen** → **Löschen** → **OK**
4. **App neu starten** → Setup erneut durchführen
5. **Wiederherstellen**: Einstellungsdatei importieren (falls kompatibel)

---

## 📱 Gerätespezifische bekannte Probleme

### Budget-/Mittelklasse-Probleme

#### Unzureichender RAM (2GB-3GB Geräte)
- **Symptome**: App häufig beendet, langsame Leistung
- **Lösungen**: 
  - Alle unnötigen Apps vor dem Schlafen schließen
  - Live-Wallpaper deaktivieren
  - Kalender-Sync-Häufigkeit auf 60 Minuten reduzieren

#### Speicherprobleme (32GB Geräte)
- **Symptome**: Datenbankfehler, Sync-Fehler
- **Lösungen**:
  - 1GB+ freien Speicher aufrechterhalten
  - Fotos/Videos in Cloud-Speicher verschieben
  - Ungenutzte Apps deinstallieren

---

## 🆘 Notfallkontakt & Support

### Vor Kontaktaufnahme mit Support

**Diese Informationen sammeln:**
- **Gerät**: Marke, Modell, Android-Version
- **App-Version**: Einstellungen → Info → Version
- **Problemtyp**: Alarm, Kalender, Hue, Leistung
- **Versuchte Schritte**: Auflistung der durchgeführten Problembehandlungsschritte
- **Häufigkeit**: Immer, manchmal, spezifische Bedingungen
- **Fehlermeldungen**: Genauer Text (Screenshot bevorzugt)

### Support-Kanäle (Antwortzeit)

1. **🔥 Kritische Alarmausfälle** (4 Stunden):
   - **E-Mail**: emergency@cf-alarm.app
   - **Einschließen**: Geräteinformationen, verpasste Alarmzeit, Auswirkungen

2. **🐛 Fehlerberichte** (24-48 Stunden):
   - **GitHub Issues**: [Fehler melden](https://github.com/F1rlefanz/CF-Alarm-for-TimeOffice/issues/new?template=bug_report.md)
   - **Einschließen**: Logs, Screenshots, Reproduktionsschritte

3. **❓ Allgemeine Fragen** (3-5 Tage):
   - **GitHub Diskussionen**: [Frage stellen](https://github.com/F1rlefanz/CF-Alarm-for-TimeOffice/discussions)
   - **Community-Support**: Andere Benutzer können helfen

### Log-Export Anweisungen

**Für technische Probleme:**
1. **Einstellungen** → **Support** → **Support-Paket vorbereiten**
2. **Einschließen**:
   - Systeminformationen ✅
   - Aktuelle Logs (24 Stunden) ✅
   - Netzwerkdiagnose ✅
   - Berechtigungsstatus ✅
3. **Exportieren** → Per E-Mail oder GitHub-Issue teilen
4. **Datenschutz**: Logs enthalten KEINE persönlichen Kalenderdaten

---

## ✅ Erfolgsrate nach Problemtyp

Basierend auf Community-Feedback:

| Problemtyp | Selbstlösungsrate | Durchschnittliche Lösungszeit |
|------------|-------------------|--------------------------------|
| Batterieoptimierung | **95%** | 5-10 Minuten |
| Kalenderberechtigungen | **90%** | 2-5 Minuten |
| Hue Bridge Setup | **85%** | 10-15 Minuten |
| Android 14+ Berechtigungen | **90%** | 3-7 Minuten |
| Leistungsprobleme | **75%** | 15-30 Minuten |
| Gerätespezifische Probleme | **80%** | 20-45 Minuten |

**Die meisten Probleme werden innerhalb von 15 Minuten mit diesem Leitfaden gelöst!** 🎯

---

📚 **Verwandte Dokumentation:**
- [🏠 Startseite](/) 
- [⚙️ Erweiterte Setup-Anleitung](advanced-setup)
- [💻 Entwicklerdokumentation](developer-guide)