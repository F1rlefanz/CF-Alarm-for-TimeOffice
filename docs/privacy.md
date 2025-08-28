---
layout: default
title: Datenschutzerklärung
---

# 🔒 Datenschutzerklärung
**CF Alarm for Time Office**

---

**Letzte Aktualisierung:** 26. August 2025 | **Version:** 1.1 | **Gültig ab:** 26. August 2025

---

## 1. Verantwortlicher

**Entwickler:** Christoph F.  
**App-Name:** CF Alarm for Time Office  
**GitHub:** [https://github.com/f1rlefanz/cf-alarmfortimeoffice](https://github.com/f1rlefanz/cf-alarmfortimeoffice)

## 2. Zweck der App

CF Alarm for Time Office ist eine Kalender-basierte Wecker-App, die automatisch Alarme basierend auf Kalenderterminen setzt und optional Philips Hue-Beleuchtung steuert.

## 3. Verarbeitete Daten

### 3.1 Kalenderdaten
- **Datentyp:** Kalenderereignisse (Termine, Zeiten, Titel)
- **Zweck:** Automatische Alarmeinstellung basierend auf Arbeitsterminen
- **Rechtsgrundlage:** Einwilligung (Art. 6 Abs. 1 lit. a DSGVO)
- **Speicherort:** Lokal auf dem Gerät, AES-256-GCM verschlüsselt
- **Speicherdauer:** Bis zur App-Deinstallation oder manuellen Löschung

### 3.2 Google OAuth-Authentifizierung
- **Datentyp:** Google-Zugriffstoken, verschlüsselte Benutzer-ID
- **Zweck:** Sicherer Zugriff auf Google Calendar API
- **Rechtsgrundlage:** Einwilligung (Art. 6 Abs. 1 lit. a DSGVO)
- **Speicherort:** Lokal auf dem Gerät, Android Keystore verschlüsselt
- **Speicherdauer:** Bis zur Abmeldung oder automatischen Token-Ablauf

### 3.3 Netzwerkdaten (Philips Hue)
- **Datentyp:** Lokale IP-Adresse, Hue Bridge Identifikation
- **Zweck:** Kommunikation mit Philips Hue Bridge (nur lokales Netzwerk)
- **Rechtsgrundlage:** Berechtigtes Interesse (Art. 6 Abs. 1 lit. f DSGVO)
- **Speicherort:** Temporär zur Laufzeit, keine dauerhafte Speicherung
- **Speicherdauer:** Keine permanente Speicherung

## 4. Datenweitergabe & Sicherheit

### 4.1 Keine Weitergabe an Dritte
- **Grundsatz:** Alle Daten bleiben lokal auf Ihrem Gerät
- **Google API:** Direkte, verschlüsselte Kommunikation (HTTPS)
- **Hue Bridge:** Nur lokale Netzwerkkommunikation
- **Keine Cloud-Server:** CF Alarm betreibt keine eigenen Server
- **Keine Analytics:** Keine Nutzungsdaten werden gesammelt

### 4.2 Enterprise-Grade Sicherheit
- **AES-256-GCM Verschlüsselung:** Alle sensiblen Daten
- **Android Keystore:** Hardware-geschützte Schlüsselverwaltung
- **Certificate Pinning:** Schutz vor Man-in-the-Middle Angriffen
- **Root Detection:** Warnung bei kompromittierten Geräten
- **Secure Code Practices:** Regelmäßige Sicherheitsaudits

## 5. Ihre Rechte (DSGVO)

### 5.1 Auskunftsrecht (Art. 15 DSGVO)
Sie haben das Recht zu erfahren, welche personenbezogenen Daten über Sie verarbeitet werden.

### 5.2 Berichtigungsrecht (Art. 16 DSGVO)
Sie können die Korrektur unrichtiger oder unvollständiger Daten verlangen.

### 5.3 Löschungsrecht (Art. 17 DSGVO)
Sie können die unverzügliche Löschung Ihrer personenbezogenen Daten verlangen.

### 5.4 Widerspruchsrecht (Art. 21 DSGVO)
Sie können der Verarbeitung Ihrer personenbezogenen Daten widersprechen.

### 5.5 Datenportabilität (Art. 20 DSGVO)
Sie können Ihre Daten in einem strukturierten, gängigen Format erhalten.

## 6. Daten löschen

### 6.1 In der App
- **Einstellungen → Konto → Abmelden:** Löscht alle Authentifizierungsdaten
- **Einstellungen → Datenschutz → Alle Daten löschen:** Vollständige lokale Datenlöschung
- **Kalender-Cache leeren:** Entfernt gespeicherte Kalenderdaten

### 6.2 Vollständige Löschung
- **App deinstallieren:** Entfernt automatisch alle lokalen Daten
- **Google-Berechtigungen widerrufen:** [myaccount.google.com/permissions](https://myaccount.google.com/permissions)
- **Android-Einstellungen:** Apps → CF Alarm → Speicher → Daten löschen

## 7. Minderjährige

Diese App ist nicht für Personen unter 16 Jahren bestimmt. Wir sammeln wissentlich keine personenbezogenen Daten von Minderjährigen ohne elterliche Einwilligung.

## 8. Internationale Datenübertragung

Da alle Daten lokal gespeichert bleiben, findet keine internationale Datenübertragung statt. Die Kommunikation mit Google Calendar API erfolgt direkt zwischen Ihrem Gerät und Google (EU-GDPR-konform).

## 9. Änderungen der Datenschutzerklärung

Änderungen werden rechtzeitig in der App und auf dieser Website bekannt gegeben. Bei wesentlichen Änderungen werden Sie direkt in der App informiert.

## 10. Kontakt & Datenschutzbeauftragter

Bei Fragen zum Datenschutz oder zur Ausübung Ihrer Rechte kontaktieren Sie uns:

- **GitHub Issues:** [https://github.com/f1rlefanz/cf-alarmfortimeoffice/issues](https://github.com/f1rlefanz/cf-alarmfortimeoffice/issues)

**Aufsichtsbehörde:**  
Bei datenschutzrechtlichen Beschwerden können Sie sich an die zuständige Datenschutzaufsichtsbehörde wenden.

---

## Open Source & Transparenz

Diese App ist Open Source. Der vollständige Quellcode kann auf GitHub eingesehen werden: [https://github.com/f1rlefanz/cf-alarmfortimeoffice](https://github.com/f1rlefanz/cf-alarmfortimeoffice)

**Letzte Aktualisierung:** 26. August 2025 | **Version:** 1.1 | **App Version:** 1.0.4+

---

[🏠 Zur Startseite](/) | [📱 GitHub](https://github.com/F1rlefanz/CF-Alarm-for-TimeOffice)
