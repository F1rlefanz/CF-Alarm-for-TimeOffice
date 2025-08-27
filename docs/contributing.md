---
layout: default
title: Mitwirken-Leitfaden
---

{% include navigation.md %}

# 🤝 Mitwirken an CF Alarm for Time Office

## Willkommen Mitwirkende! 🎉

Wir freuen uns, dass Sie zu CF Alarm for Time Office beitragen möchten! Dieses Dokument bietet Richtlinien und Informationen für Mitwirkende.

## Schnellstart

1. **Fork** des Repositorys erstellen
2. **Klonen** Sie Ihren Fork lokal
3. **Erstellen** Sie einen Feature-Branch
4. **Machen** Sie Ihre Änderungen
5. **Testen** Sie gründlich
6. **Senden** Sie einen Pull-Request

## Entwicklungssetup

### Voraussetzungen

- **Android Studio** 2025.1.1 (Narwhal) oder neuer
- **JDK** 17 (LTS)
- **Android SDK** API 36
- **Kotlin** 2.1.0+

### Erste Schritte

```bash
# Ihren Fork klonen
git clone https://github.com/IHR_BENUTZERNAME/CF-Alarm-for-TimeOffice.git
cd CF-Alarm-for-TimeOffice

# keystore.properties erstellen (siehe Sicherheitssetup)
touch keystore.properties

# In Android Studio öffnen
# Build → Projekt mit Gradle-Dateien synchronisieren
```

## 🔐 Sicherheitssetup

`keystore.properties` im Projektstamm erstellen:

```properties
storeFile=./debug.keystore
storePassword=android
keyAlias=androiddebugkey  
keyPassword=android
googleWebClientId=IHRE_GOOGLE_CLIENT_ID
```

## Code-Richtlinien

### Architektur
- **MVVM + Clean Architecture** befolgen
- **Repository Pattern** für Datenzugriff verwenden
- **UseCase-Klassen** für Business-Logik implementieren
- **Dependency Injection** mit Hilt anwenden

### Code-Stil
- **Kotlin Coding Conventions** befolgen
- **Aussagekräftige Variablennamen** verwenden
- **Umfassende Kommentare** schreiben
- Funktionen **klein und fokussiert** halten

### Testen
- **Unit-Tests** für Business-Logik schreiben
- **Integrationstests** für Repositories hinzufügen
- **Edge-Cases** und Fehlerszenarien testen
- **Testabdeckung** über 80% halten

## Wonach wir suchen

### 🐛 Fehlerberichte
- Klare **Reproduktionsschritte**
- **Geräteinformationen** (Android-Version, Modell)
- **Screenshots** falls zutreffend
- **Logs** wenn relevant

### ✨ Feature-Anfragen
- **Detaillierte Beschreibung** des Features
- **Anwendungsfall** Erklärung
- **Mockups** oder Wireframes (bei UI-bezogenen Features)
- **Implementierungsvorschläge**

### 🔧 Code-Beiträge
- **Sauberer, gut getesteter Code**
- **Dokumentations-Updates**
- **Sicherheits-Best-Practices**
- **Leistungsoptimierungen**

## Entwicklungsbereiche

### Hohe Priorität
- **Wear OS Integration**
- **Musik-Service Integration**
- **Akkuoptimierung**
- **Barrierefreiheits-Verbesserungen**

### Mittlere Priorität
- **UI/UX-Verbesserungen**
- **Weitere Smart Home Plattformen**
- **Erweiterte Benachrichtigungs-Features**
- **Widget-Entwicklung**

### Dokumentation
- **API-Dokumentation**
- **Benutzerhandbücher**
- **Problembehandlungsanleitungen**
- **Übersetzung (i18n)**

## Pull-Request-Prozess

### Vor dem Einreichen
1. **Testen** Sie Ihre Änderungen gründlich
2. **Aktualisieren** Sie die Dokumentation falls nötig
3. **Fügen** Sie Tests für neue Funktionalität hinzu
4. **Befolgen** Sie unsere Code-Stil-Richtlinien
5. **Rebase** auf neuesten main-Branch

### PR-Anforderungen
- **Klarer Titel** der die Änderung beschreibt
- **Detaillierte Beschreibung** was geändert wurde
- **Link zu verwandten Issues**
- **Screenshots** für UI-Änderungen
- **Testergebnisse** enthalten

### Review-Prozess
1. **Automatische Prüfungen** müssen bestehen
2. **Code-Review** durch Maintainer
3. **Testen** auf mehreren Geräten
4. **Genehmigung** und Merge

## Community-Richtlinien

### Respektvoll sein
- **Konstruktives Feedback** nur
- **Einladend** für neue Mitwirkende
- **Professionelle** Kommunikation
- **Inklusive** Sprache

### Hilfreich sein
- **Wissen und Expertise** teilen
- **Anderen Mitwirkenden** helfen
- **Lösungen dokumentieren**
- **Schnell** auf Feedback antworten

## Hilfe erhalten

### Dokumentation
- [Entwickler-Leitfaden](developer-guide)
- [API-Referenz](api-reference)
- [Problembehandlung](troubleshooting)

### Kommunikation
- **GitHub Issues** für Bugs und Features
- **GitHub Discussions** für allgemeine Fragen
- **E-Mail** für sicherheitsbezogene Themen

## Anerkennung

Mitwirkende werden:
- **Aufgelistet** in unserem Contributors-Bereich
- **Erwähnt** in Release-Notes
- **Eingeladen** zu Beta-Test-Programmen
- **Willkommen geheißen** im Maintainer-Team (langfristige Mitwirkende)

## Häufige Beitragsarten

### Code-Beiträge
- **Neue Features** implementieren
- **Bugs** beheben
- **Performance** verbessern
- **Code-Refactoring**
- **Tests** hinzufügen

### Dokumentations-Beiträge
- **README** verbessern
- **API-Dokumentation** erweitern
- **Tutorials** schreiben
- **Übersetzungen** hinzufügen

### Design-Beiträge
- **UI/UX Verbesserungen**
- **Icons** und Grafiken
- **Mockups** für neue Features
- **Barrierefreiheit** verbessern

## Entwicklungsrichtlinien

### Branch-Strategie
- `main` - Stabile Production-Releases
- `develop` - Entwicklungsintegration
- `feature/feature-name` - Neue Features
- `bugfix/bug-description` - Fehlerbehebungen
- `hotfix/critical-fix` - Kritische Produktionsfixes

### Commit-Nachrichten
```
type(scope): kurze Beschreibung

Längere Beschreibung falls nötig...

- Aufzählungspunkte für Details
- Weitere Änderungen

Fixes #123
```

Typen: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

### Code-Review-Kriterien
- **Funktionalität**: Code erfüllt Anforderungen
- **Lesbarkeit**: Code ist klar und verständlich
- **Performance**: Keine unnötigen Performance-Einbußen
- **Sicherheit**: Sicherheitsbest-Practices befolgt
- **Tests**: Angemessene Testabdeckung
- **Dokumentation**: Nötige Dokumentation aktualisiert

## Technische Standards

### Code-Qualität
- **Keine Compiler-Warnungen**
- **Lint-Regeln** befolgen
- **Code-Coverage** mindestens 80%
- **Performance-Benchmarks** bestehen

### Sicherheitsstandards
- **Keine hardcoded Secrets**
- **Input-Validierung**
- **Sichere API-Aufrufe**
- **Datenverschlüsselung** wo nötig

## Release-Zyklus

### Versionsnummerierung
Wir verwenden [Semantic Versioning](https://semver.org/):
- **Major** (X.0.0): Breaking Changes
- **Minor** (0.X.0): Neue Features, rückwärtskompatibel
- **Patch** (0.0.X): Bugfixes, rückwärtskompatibel

### Release-Zeitplan
- **Major Releases**: Alle 6 Monate
- **Minor Releases**: Monatlich
- **Patch Releases**: Bei Bedarf

## Support für Mitwirkende

### Mentorship-Programm
- **Neue Mitwirkende** erhalten einen Mentor
- **1-zu-1 Unterstützung** für erste PRs
- **Feedback** und Lernmöglichkeiten

### Entwickler-Werkzeuge
- **Pre-commit Hooks** für Code-Qualität
- **CI/CD Pipeline** für automatisierte Tests
- **Development Environment** Setup-Scripts

## Lizenz

Durch Mitwirken stimmen Sie zu, dass Ihre Beiträge unter der MIT-Lizenz lizenziert werden.

---

**Vielen Dank für Ihren Beitrag zu CF Alarm for Time Office!** 🚀

Ihre Beiträge helfen dabei, die beste Alarm-App für Time Office Nutzer zu schaffen. Jeder Beitrag, egal wie klein, macht einen Unterschied!