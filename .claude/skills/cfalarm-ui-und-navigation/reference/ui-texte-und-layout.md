# UI-Texte und Compose-Layout — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen.** `MainScreen` feuert
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit `package:`-Data — das ist Androids
  **Systemdialog** („Zulassen, dass die App immer im Hintergrund läuft?"), ein Tipp, keine Liste.
  Der Screen beschrieb eine vierstufige Anleitung, die niemand je zu sehen bekam. Wer
  den Ablauf ändert, muss den Text mitändern — und umgekehrt.
- **Kernpunkt einmal, konkret, mit dem echten Einsatz.** Für eine Wecker-App heißt das nicht
  „Background-Jobs werden gestoppt", sondern „der Wecker bleibt still". Tiefere Erklärung gehört
  hinter „Warum ist das nötig?", nicht ein zweites Mal auf den Screen.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich mit der UI, NIE eine Position.**
  „die Karte darunter" im AUTHORIZATION_LOST-Text zeigte auf die Alarm-Status-Karte; der Knopf
  „Kalender-Zugriff erneuern" sitzt eine Karte weiter. Positionen verschieben sich beim nächsten
  Layout-Umbau lautlos, Beschriftungen fallen beim Umbenennen auf.
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen, die ein Test gegen die echte
  Standardkonfiguration prüft** (`ShiftConfigScreenTextTest`) — der Konfigurations-Hinweis nannte
  Muster („IMCF, IMCS, IMCN, IMCZ") und behauptete „erkannt wird über die Muster, nicht über den
  Schichtnamen allein"; beides hatte derselbe Arbeitsdurchgang unwahr gemacht, der den Text einführte.
  Zwei Bildschirme widersprachen sich (der `ShiftEditDialog` sagte es korrekt). Drift muss auffallen,
  nicht stumm bleiben.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als
  anderer ausgeben.** Vier Fälle in einer Runde gefunden und behoben: „Zeige 5 von N Events" auf
  einer Karte, die überhaupt keine Events listet; „Schichttypen werden noch geladen" für einen
  Ladevorgang, der DAUERHAFT gescheitert ist; „⚠️ Aktiver Fehler" auf der Dimmer-Karte, obwohl der
  Nutzer den Dimmer nie eingeschaltet hat (das entwertet genau die roten Karten daneben, an denen
  der Wecker wirklich hängt); und „Verstanden" als Beschriftung des ABBRECHEN-Knopfs, während
  daneben der Knopf steht, der wirklich weiterführt. Dazu: eine nicht lesbare Schicht-Konfiguration
  rendert im Konfigurations-Screen keine stumme leere Liste mehr, sondern sagt, dass sie nicht
  lesbar ist und NICHT überschrieben wird.
- **Eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.** Die Erkennung überspringt
  sie vollständig, es entsteht kein Alarm — die Liste zeigte trotzdem „Alarm: 05:30" in der
  Akzentfarbe. Eine angezeigte Weckzeit, die nie gestellt wird, ist die gefährlichste Anzeige, die
  eine Wecker-App haben kann; das Gegenstück `isSilent` hat aus demselben Grund ein eigenes Icon.
- **Der Kürzel-Zuordnungsdialog ist scrollbar.** Bei fünf Standard-Definitionen plus Erklärtext war
  der letzte Knopf auf schmalen Geräten abgeschnitten — und er ist der EINZIGE angebotene Weg für
  dieses Kürzel: kein Muster, keine erkannte Schicht, kein Wecker. Dieselbe Fehlerklasse wie der
  unerreichbare „Auf Standardwerte zurücksetzen"-Knopf desselben Screens.
- **Deutsche Nutzer-Texte in `UITextConstants` ohne Aufrufer löschen, nicht liegen lassen.** Sie sehen
  wie aktive UI-Texte aus und werden sonst als Vorlage weitergeschleppt (die Countdown-Texte hatten
  nach dem Entfernen von `CountdownTimer.kt` nur noch ihre Deklaration).

### Compose-Layout

- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.** Ohne das nimmt
  der Beschreibungstext die volle Breite und der Schalter landet außerhalb der Karte. Eine feste
  `.width(…dp)` als Pflaster bricht bei schmalem Display oder großer Schrift.
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite.** In schmalen, geteilten Buttons bleibt zu
  wenig für die Schrift, und Compose bricht mitten im Wort. Dafür gibt es **`CompactButton`** und
  **`CompactOutlinedButton`** (in `ui/components/CompactActionButton.kt`) — **nur** für schmale,
  geteilte Buttons, nicht für ganzbreite, wo ein Zweizeiler gewollt ist.
- **Eine `LazyColumn` in einer `Column` braucht `weight(1f)`.** Ohne sie misst sie sich auf ihre
  Inhaltshöhe und frisst die gesamte Resthöhe — im `ShiftConfigScreen` war der Knopf „Auf
  Standardwerte zurücksetzen" darunter dadurch **unerreichbar** (bei fünf Schichten plus der
  Kürzel-Karte, am Gerät nachgeprüft). Ein zweiter Spacer mit `weight` daneben hilft nicht, er
  konkurriert nur.
- **`RadioButton`/`Checkbox` mit `onClick = null` brauchen `heightIn(min = 48.dp)` am Row.**
  Das Muster „ganze Zeile klickbar" (`Modifier.selectable`/`toggleable` am Row, `onClick = null`
  am Knopf) ist richtig — aber der Knopf ist damit **nicht mehr klickbar** und bringt seine
  eingebaute `minimumInteractiveComponentSize()` nicht mehr mit. Ohne die Klemme schrumpft die
  Reihe auf ~32dp: breiter als vorher, aber flacher als Materials Minimum. Am Emulator
  nachgemessen (Density 420: 48dp = 126px). Konstante: `MIN_TOUCH_TARGET` in
  `ui/screens/hue/HueRuleConfigHelpers.kt`.
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`. `FlowRow` ist seit Compose 1.11.4
  stabil (nur die deprecated Überladung mit `overflow` ist `@ExperimentalLayoutApi`) → kein
  `@OptIn` nötig.

