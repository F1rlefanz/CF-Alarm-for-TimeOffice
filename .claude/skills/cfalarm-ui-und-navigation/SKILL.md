---
name: cfalarm-ui-und-navigation
description: Zusicherungen fuer Compose-Layout, Nutzertexte und das Zurueck-Verhalten der CFAlarm-Wecker-App. Die App navigiert ueber einen eigenen NavigationState ohne Backstack, deshalb gehoert Zurueck dem BackHandler in MainScreen und jeder neue Zustand muss dort mitbedacht werden. Dazu die Layout-Fallen (weight(1f) neben einem Switch, 48dp-Touchziele, LazyColumn in einer Column, FlowRow statt chunked) und die Regel, dass kein Text eine Anzeige oder einen Ablauf behaupten darf, den es nicht gibt. Zu verwenden bei Arbeit an MainScreen, MainContentScreen, NavigationViewModel, UITextConstants, Onboarding-Screens oder beliebigen Compose-Komponenten — und wenn Zurueck die App beendet, ein Knopf unerreichbar ist oder Text mitten im Wort umbricht.
---

# UI-Texte, Compose-Layout und Navigation

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/navigation.md` — BackHandler, Onboarding-Gates, Rueckweg aus Unterscreens
- `reference/ui-texte-und-layout.md` — Textregeln und die Compose-Layout-Fallen

---

## Kurzregeln

- **Zurück gehört dem `BackHandler` in `MainScreen`** — es gibt keinen Backstack, ohne Handler
  beendet jeder Druck die App. Wer einen neuen `NavigationState` ergänzt, muss ihn dort mitbedenken.
- **VIER Gates sind nicht optional**: `BatteryExemption`, `UnusedAppRestrictions` und
  `TimeOfficeHealthCheck` müssen ihr Dismissed-Flag schreiben, `OEMWarning` muss `finishOnboarding()`
  anstoßen. Auf dem Home-Tab bleibt der Handler bewusst aus.
- **„Später" beim Akku-Gate heißt ERLEDIGT, nicht abgebrochen** (`batteryGateResolved`) — sonst fällt
  der Nutzer aus jedem Zweig heraus und bekommt den nächsten Schritt NIE angeboten.
- **`HueRuleConfig`/`DimmerRuleConfig` brauchen `cameFromSettingsList`**, nicht nur `returnToTab`:
  System-Back und screen-eigener Zurück-Pfeil MÜSSEN für denselben Einstiegspfad zum selben Ziel führen.

## UI-Texte und Compose-Layout — Kurzregeln

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen** — es erscheint Androids
  Systemdialog, keine Liste. Ablauf und Text ändern sich gemeinsam.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich, NIE eine Position — und muss
  auch die HANDLUNGSRICHTUNG stimmen haben** („ausschalten", nicht der bloße Schaltername).
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen**, die ein Test gegen die echte
  Standardkonfiguration prüft — Drift muss auffallen.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als anderer
  ausgeben.** Insbesondere: **eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.**
- **Ein Zustand, der die App dauerhaft nicht wecken lässt, MUSS dort stehen, wo der Nutzer
  nachsieht** — nicht nur an seinem Schalter. Die Master-Pause hat dabei **Vorrang vor „N aktive
  Alarme"**, und es steht höchstens EIN Zusatz-Hinweis gleichzeitig.
- **Eine Meldung erreicht ihren Nutzer nur, wenn sie in GENAU dem Zustand gerendert wird, in dem sie
  gesetzt wird** — Renderstelle UND Zustand bei jedem neuen Meldungsfeld per grep prüfen.
- **Kein `SnackbarDuration.Indefinite`**: es blockiert über den Mutex des `SnackbarHostState` alle
  übrigen Meldungen samt ihrer `clearError()`-Aufrufe. Bleibende Zustände sind Karten im Status-Tab.
- **Auf dem ANZEIGEPFAD bekommt ein sicherheitsrelevanter Compose-Parameter KEINEN Default**
  (`masterPausePaused` in `AlarmStatusHeader`, `WeckerTabContent`, `StatusTabContent`) — sonst
  fällt ein künftiger Aufrufer wortlos in den alten Fehler zurück. Reine Textfunktionen wie
  `noShiftExplanation()` dürfen einen Default haben; dort ist der Wert ein Zusatz, keine Zusage.
- **Deutsche Nutzer-Texte in `UITextConstants` ohne Aufrufer löschen, nicht liegen lassen.**
- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.**
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite** — für schmale, geteilte Buttons gibt es
  `CompactButton`/`CompactOutlinedButton`.
- **Eine `LazyColumn` in einer `Column` braucht `weight(1f)`** — sonst wird der Knopf darunter
  unerreichbar (real passiert). Gleiches gilt für lange Dialoge: scrollbar machen.
- **`RadioButton`/`Checkbox` mit `onClick = null` brauchen `heightIn(min = 48.dp)` am Row.**
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`.

---
