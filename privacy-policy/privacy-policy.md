# Datenschutzerklärung für CF Alarm for Time Office

**Letzte Aktualisierung:** 15. Juli 2025  
**Version:** 1.0  
**Gültig ab:** 15. Juli 2025

## 1. Verantwortlicher

**Entwickler:** Christoph F.  
**App-Name:** CF Alarm for Time Office  
**Kontakt:** [E-Mail-Adresse einfügen]  
**GitHub:** https://github.com/f1rlefanz/cf-alarmfortimeoffice

## 2. Zweck der App

CF Alarm for Time Office ist eine Kalender-basierte Wecker-App, die automatisch Alarme basierend auf Kalenderterminen setzt und optional Philips Hue-Beleuchtung steuert.

## 3. Verarbeitete Daten

### 3.1 Kalenderdaten
- **Datentyp:** Kalenderereignisse (Termine, Zeiten, Titel)
- **Zweck:** Automatische Alarmeinstellung basierend auf Arbeitsterminen
- **Rechtsgrundlage:** Einwilligung (Art. 6 Abs. 1 lit. a DSGVO)
- **Speicherort:** Lokal auf dem Gerät, verschlüsselt
- **Speicherdauer:** Bis zur App-Deinstallation oder manuellen Löschung

### 3.2 Google OAuth-Authentifizierung
- **Datentyp:** Google-Zugriffstoken, Benutzer-ID
- **Zweck:** Zugriff auf Google Calendar API
- **Rechtsgrundlage:** Einwilligung (Art. 6 Abs. 1 lit. a DSGVO)
- **Speicherort:** Lokal auf dem Gerät, AES-256-GCM verschlüsselt
- **Speicherdauer:** Bis zur Abmeldung oder Token-Ablauf

### 3.3 Netzwerkdaten
- **Datentyp:** IP-Adresse, Netzwerkstatus
- **Zweck:** Philips Hue Bridge-Kommunikation (nur lokales Netzwerk)
- **Rechtsgrundlage:** Berechtigtes Interesse (Art. 6 Abs. 1 lit. f DSGVO)
- **Speicherort:** Nicht gespeichert, nur zur Laufzeit verarbeitet
- **Speicherdauer:** Keine permanente Speicherung

### 3.4 Gerätedaten
- **Datentyp:** Alarmberechtigungen, Benachrichtigungsstatus
- **Zweck:** Korrekte Alarmfunktionalität
- **Rechtsgrundlage:** Berechtigtes Interesse (Art. 6 Abs. 1 lit. f DSGVO)
- **Speicherort:** Android-Systemeinstellungen
- **Speicherdauer:** Bis zur App-Deinstallation

## 4. Datenweitergabe

### 4.1 Keine Weitergabe an Dritte
- **Grundsatz:** Keine Daten werden an Dritte weitergegeben
- **Ausnahmen:** Keine
- **Externe APIs:** Nur Google Calendar API (direkte Kommunikation)

### 4.2 Lokale Verarbeitung
- **Prinzip:** Alle Daten bleiben auf dem Gerät
- **Philips Hue:** Nur lokale Netzwerkkommunikation
- **Cloud-Backup:** Sensitive Daten ausgeschlossen

## 5. Datensicherheit

### 5.1 Verschlüsselung
- **OAuth-Tokens:** AES-256-GCM Verschlüsselung
- **Speicherort:** EncryptedSharedPreferences
- **Schlüsselverwaltung:** Android KeyStore

### 5.2 Netzwerksicherheit
- **Externe Verbindungen:** Nur HTTPS
- **Lokale Verbindungen:** HTTP nur für Philips Hue (privates Netzwerk)
- **Zertifikate:** System-Zertifikate, keine benutzerdefinierten

### 5.3 Backup-Schutz
- **Cloud-Backup:** Sensitive Daten ausgeschlossen
- **Geräteübertragung:** Authentifizierungsdaten nicht übertragen

## 6. Ihre Rechte

### 6.1 Auskunftsrecht (Art. 15 DSGVO)
Sie haben das Recht zu erfahren, welche Daten über Sie verarbeitet werden.

### 6.2 Berichtigungsrecht (Art. 16 DSGVO)
Sie können die Korrektur unrichtiger Daten verlangen.

### 6.3 Löschungsrecht (Art. 17 DSGVO)
Sie können die Löschung Ihrer Daten verlangen.

### 6.4 Widerspruchsrecht (Art. 21 DSGVO)
Sie können der Verarbeitung Ihrer Daten widersprechen.

### 6.5 Datenportabilität (Art. 20 DSGVO)
Sie können Ihre Daten in einem strukturierten Format erhalten.

## 7. Daten löschen

### 7.1 In der App
- **Einstellungen → Konto → Abmelden:** Löscht alle Authentifizierungsdaten
- **Einstellungen → Datenschutz → Alle Daten löschen:** Vollständige Löschung

### 7.2 Vollständige Löschung
- **App deinstallieren:** Entfernt alle lokalen Daten
- **Google-Berechtigungen widerrufen:** accounts.google.com → Drittanbieter-Apps

## 8. Minderjährige

Diese App ist nicht für Personen unter 16 Jahren bestimmt. Wir sammeln wissentlich keine Daten von Minderjährigen.

## 9. Änderungen der Datenschutzerklärung

Änderungen werden in der App bekannt gegeben. Die aktuelle Version ist immer unter GitHub verfügbar.

## 10. Kontakt

Bei Fragen zum Datenschutz kontaktieren Sie uns:
- **E-Mail:** [E-Mail-Adresse einfügen]
- **GitHub Issues:** https://github.com/f1rlefanz/cf-alarmfortimeoffice/issues

---

**Hinweis:** Diese App ist Open Source. Der Quellcode kann auf GitHub eingesehen werden: https://github.com/f1rlefanz/cf-alarmfortimeoffice
