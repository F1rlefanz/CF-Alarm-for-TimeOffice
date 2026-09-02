---
name: cfalarm-release-und-changelog
description: Release-Ablauf der CFAlarm-Wecker-App - Versionsbump in app/build.gradle.kts, Changelog-Eintrag in CHANGELOG.md und Erzeugen der oeffentlichen Seite docs/changelog.html per Generator. CHANGELOG.md ist die Quelle; der Bereich zwischen den Markern in changelog.html wird ueberschrieben und darf nie von Hand editiert werden. Zu verwenden beim Bumpen von versionCode oder versionName, beim Schreiben eines Changelog-Eintrags, beim Vorbereiten oder Ausliefern eines Release, und wenn die oeffentliche Changelog-Seite nicht mehr zum Markdown passt.
---

# Release und Changelog

`CHANGELOG.md` im Repo-Root ist seit 17.08.2026 die **Quelle**; `docs/changelog.html` (GitHub
Pages, öffentlich) wird daraus erzeugt. Vorher war das HTML handgeschrieben — jeder Release war
HTML-Handarbeit ohne Quelle daneben.

## Ablauf

**1. Divergenz prüfen.** `git fetch`, Stand gegen `origin/main` — mehrere Sessions (lokal **und**
Cloud auf `claude/*`-Branches) pushen unabhängig nach `main`.

**2. Versionsbump** in `app/build.gradle.kts` (`versionCode`, `versionName`), **nur auf `main`**,
nie auf einem Feature-Branch. `versionCode` muss höher sein als der **höchste je vergebene** — nicht
nur höher als der eigene Basisstand, weil Cloud-Sessions ebenfalls Nummern vergeben.
Patch = Fix, Minor = Feature, Major nur nach Rücksprache.

**3. Changelog-Eintrag — NUR in `CHANGELOG.md`**, neuer Block ganz oben unter dem Vorspann:

```markdown
## 🆕 Version 1.26.0 (Aktuell – interne Alpha)

**Stand:** August 2026

_Eine kursive Zusammenfassung in einem Satz._

### 🐛 Behoben

- **Kurzfassung:** Erklärung in Nutzersprache.
```

Der Klammerzusatz wird zu `<small>(…)</small>`. Beim Release **das `🆕` und den Zusatz
„(Aktuell – interne Alpha)" der bisher obersten Version wegnehmen** und der neuen geben.
Rubriken-Emoji wie gehabt: `🐛 Behoben`, `✨ Neu`, `🎨 Feinschliff`, `🔧 Unter der Haube`,
`🔒 Sicherheit`, `🧰 Für Tester`.

**Sprache: Endnutzer, nicht Entwickler.** Was hätte der Nutzer gemerkt, was ist jetzt anders — keine
Klassennamen, keine Commit-Prosa. Die 55 bestehenden Einträge sind die Vorlage.

**4. Generator laufen lassen** (Python 3, nur Standardbibliothek):

```bash
python tools/changelog/build_changelog.py            # schreibt docs/changelog.html
python tools/changelog/build_changelog.py --pruefen  # exit 1, wenn Seite != Markdown
```

`CHANGELOG.md` und `docs/changelog.html` **zusammen** committen.

**5. Build und Tests**: `./gradlew testDebugUnitTest lintDebug assembleDebug`; `assembleRelease`
braucht Netz (R8 an). Die CI baut den Release-Pfad ebenfalls.

**6. Merge, Push, Branches aufräumen.** Der Push nach `main` **ist** die Auslieferung: seit
`.github/workflows/veroeffentlichen.yml` existiert, lädt jeder Push auf `main`, der den
`versionCode` erhöht, von allein in den **internen** Play-Track. Ein Push ohne Bump löst nichts
aus, und `app/build.gradle.kts` ist der einzige Pfad, der den Workflow überhaupt startet.

Bis zum 01.09.2026 stand hier „der Play-Upload bleibt manuell". Das gilt nicht mehr — heißt aber
**nicht**, dass die Messlatte gefallen wäre: automatisch beliefert wird ausschließlich der interne
Track. Der Produktions-Track bleibt Handarbeit, und zwar mit Absicht — grüne Tests plus grüner Build
haben in diesem Projekt schon einen Crash beim Start durchgelassen (05.08.2026). Der interne Track
ist die Stelle, an der so etwas auffällt, bevor es jemanden weckt. Die Begründung steht ausführlich
im Kopf von `veroeffentlichen.yml`.

## Nicht verhandelbar

- **`docs/changelog.html` nie von Hand editieren.** Der Bereich zwischen
  `<!-- CHANGELOG:BEGIN -->` und `<!-- CHANGELOG:END -->` wird beim nächsten Lauf komplett
  überschrieben — die Handänderung wäre weg. Alles außerhalb der Marker bleibt unangetastet, dort
  darf von Hand gearbeitet werden.
- **Das Original ist die Wahrheit, nicht die Prüfung.** Weicht etwas ab, wird die Konvertierung
  korrigiert, niemals das Prüfskript weichgespült.

## Verifikation der Umstellung selbst

`tools/changelog/pruefe_treue.py` vergleicht den reinen Text zweier Seiten (Tags raus, Entities
aufgelöst, Leerraum normalisiert). Gegen die handgeschriebene Originalseite gefahren am
17.08.2026: **55 Versionskarten, 65.995 Textzeichen, textgleich.**

```bash
python tools/changelog/pruefe_treue.py <original.html> [docs/changelog.html]
```

## Offener Vorschlag

Ein CI-Schritt `python tools/changelog/build_changelog.py --pruefen` würde den Fall abfangen, dass
jemand `CHANGELOG.md` ändert und die erzeugte Seite vergisst (oder umgekehrt das HTML von Hand
editiert). Kein Gradle nötig, läuft in Sekunden. Noch nicht eingebaut.
