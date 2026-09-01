# UI-Texte und Compose-Layout — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Der Akku-Onboarding-Screen darf keine Einstellungen versprechen
- Kernpunkt einmal, konkret, mit dem echten Einsatz
- Beschriftung wortgleich, nie eine Position — und die Handlungsrichtung muss stimmen
- Beispiele in Hinweistexten aus deklarierten, getesteten Listen
- Kein Text behauptet eine Anzeige, die es nicht gibt (deaktivierte Definition ohne Weckzeit)
- Scrollbare Dialoge, tote `UITextConstants`
- Compose-Layout: `weight(1f)` neben Switch und um `LazyColumn`, 24dp-ContentPadding,
  48dp-Touchziele, `FlowRow`
- Farbe als einziger Zustandsträger: `surfaceVariant` ist in der hellen Palette `background`
- Anzeigegröße 320 dp ist die reale untere Grenze
- Wahrheit der Anzeige (Prüfrunde 8): Master-Pause sichtbar machen, Meldeweg im richtigen Zustand,
  kein `SnackbarDuration.Indefinite`, Handlungsrichtung, kein Default für `masterPausePaused`

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
  Layout-Umbau lautlos, Beschriftungen fallen beim Umbenennen auf. **Wortgleichheit allein reicht
  nicht** — sie sagt nichts über die Handlungsrichtung; siehe „Wahrheit der Anzeige" unten.
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


### Wahrheit der Anzeige — Prüfrunde 8 (v1.30.0)

**Eine angekündigte Weckzeit, an der nichts klingelt, ist die gefährlichste Anzeige dieser App.**
`computeNextAlarmTime()` filterte nur nach Zeit und Skip-Merker, nie nach `AlarmInfo.isSilent` — die
Karte „Nächster Alarm" konnte also eine **stille Schicht** nennen, bei der `AlarmReceiver` per
Konstruktion stumm aussteigt. Der Nutzer las eine Zusage, stellte keinen eigenen Wecker und
verschlief; zusätzlich verdeckte der stille Eintrag den nächsten, der wirklich klingelt. Der
`ShiftConfigScreen` hatte für den Zwilling `isEnabled` schon eine Warnung in Großbuchstaben im Code
stehen — ausgerechnet die Karte, die den NÄCHSTEN Wecker behauptet, hatte keine. Heute ist der
stille Eintrag gekennzeichnet, die Zählung „N aktive Alarme" unterscheidet, und der nächste hörbare
Wecker steht daneben. **Merke: eine Bedingung, die die AUSLÖSUNG gated, muss auch die ANZEIGE
gaten — sonst sagt die App etwas zu, das sie nicht hält.**


Fünf Regeln aus einer Runde, und sie hängen zusammen: eine Meldung nützt nichts, wenn sie im
falschen Zustand, am falschen Ort, in der falschen Richtung oder gar nicht gerendert wird.

- **Ein Zustand, der die App dauerhaft nicht wecken lässt, MUSS dort stehen, wo der Nutzer
  nachsieht.** Die Master-Pause ist der umfassendste Sync-Stopp der App (löscht alle Alarme, stoppt
  Wartung, Dimmer, DND, Hue, Pre-Alarm-Refresh) — angezeigt wurde sie an genau einer Stelle: dem
  Schalter ganz unten im Einstellungen-Tab. Der Wecker-Tab behauptete derweil „Automatische Alarme:
  an" samt „Deaktivieren löscht sofort alle Wecker" (sie waren längst gelöscht), der Alarm-Status
  zeigte ein grundloses „Keine aktiven Alarme", und der Home-Tab nannte brav die nächste Schicht,
  weil die Schichterkennung unabhängig weiterläuft. Wer nach dem Urlaub Home und Wecker prüfte, sah
  nichts Auffälliges und verschlief jede Schicht — die App läuft aus diesem Zustand NIE von allein
  heraus. Jetzt: Karte im Status-Tab mit Folge und zwei Auswegen, Grund im Alarm-Status, korrigierte
  Schalterbeschreibung, Schalter während der Pause gesperrt. **Die Pause hat Vorrang vor „N aktive
  Alarme"** (`alarmStatusZustand` in `AlarmStatusHeader.kt`): ein nach `pause()` stehengebliebener
  Bestand verspricht eine Weckzeit, die nie gestellt wird. Und **höchstens EIN Zusatz-Hinweis**
  gleichzeitig (`noShiftExplanation`), sonst bleibt offen, welcher der wirksame ist.
- **Eine Meldung erreicht ihren Nutzer nur, wenn sie in GENAU dem Zustand gerendert wird, in dem sie
  gesetzt wird.** Vier Fälle in dieser Runde: die Warnung „nicht dauerhaft gespeichert" stand im
  `else`-Zweig der Karte und war ausgerechnet dann unsichtbar, wenn ein manueller Wecker existierte;
  die Meldung über einen gescheiterten Regel-Nachzug wurde 200 ms später von `copy(error = null)`
  gelöscht und nur in `MainContentScreen` gerendert, während der Nutzer im `ShiftConfigScreen`
  stand; der Hinweis nach gescheitertem Abmelden lag im `LoginScreen`, der in genau diesem Zustand
  (angemeldet geblieben) nicht komponiert ist; und eine Karte im Status-Tab erreicht niemanden, der
  im Einstellungen-Tab steht. **Bei jedem neuen Meldungsfeld per grep die Renderstelle UND den
  Zustand prüfen**, in dem sie greift.
- **`SnackbarDuration.Indefinite` blockiert den ganzen Host.** `SnackbarHostState.showSnackbar`
  serialisiert über einen Mutex: die eine unbefristete Snackbar (Hinweis auf stehengebliebene
  Wecker nach Kalender-Abwahl) ließ ALLE übrigen Kanäle desselben Hosts suspendieren — samt der
  `clearError()`-Aufrufe dahinter, sodass Kalender-, Schicht- und Wecker-Fehler weder erschienen
  noch geleert wurden. Ein Zustand, der BLEIBEN soll, gehört als Karte in den Status-Tab (so wie
  Kalender-Teilerfolg, fehlende Berechtigungen, Akku-Ausnahme). Ein Test hält fest, dass
  `MainContentScreen` keinen `Indefinite`-Aufruf mehr enthält.
- **Ein Nutzertext muss auch die RICHTUNG stimmen haben, nicht nur die Beschriftung.** „Wieder
  einschalten im Einstellungen-Tab unter ‚Hintergrunddienste pausieren'" schickte den Nutzer an
  einen Schalter, der bereits AN war und AUS gehört — der Text nannte den Namen des Schalters
  statt der nötigen Handlung. Das fiel erst der Prüfung über dem Fix auf, weil der Test nur
  `contains(...)` auf den Namen prüft: die Wortgleichheits-Regel oben ist notwendig, nicht
  hinreichend.
- **Ein neuer Compose-Parameter für einen sicherheitsrelevanten Zustand bekommt KEINEN Default.**
  `masterPausePaused: Boolean = false` hätte einen künftigen Aufrufer wortlos in den alten Fehler
  (Pause unsichtbar) zurückfallen lassen, ohne dass Compiler oder Test anschlagen. Auf dem
  Anzeigepfad (`AlarmStatusHeader`, `WeckerTabContent`, `StatusTabContent`) ist der Parameter
  deshalb pflichtig.

- **`surfaceVariant` ist in der HELLEN CSJR-Palette derselbe Farbwert wie `background` — eine Karte
  in dieser Farbe hat auf einer Seite keine Fläche mehr.** Beides ist `OffWhite` (`0xFFF0EDEA`),
  und zwar **so aus dem Corporate-Design übernommen** (`..Projektdateien/CSJR Corporate Design für
  Apps.zip`, `design-system/android/Theme.kt`) — es ist kein Tippfehler im Theme, den man
  „reparieren" dürfte: an der Palette zu drehen ändert alles, was `surfaceVariant` sonst noch
  nutzt. Falsch war die VERWENDUNG. `HueRuleCard` drückte damit den Zustand *aktiv* aus, also
  bekam ausgerechnet die eingeschaltete Regel keine sichtbare Karte, während die ausgeschalteten
  weiß abgesetzt dastanden (am Fairphone gesehen, 01.09.2026; zwei Hinweiskarten in
  `WeckerTabContent` und `ShiftConfigScreen` waren aus demselben Grund flächenlos). Im dunklen
  Schema war nie etwas kaputt (`DarkSurfaceVariant` != `DarkBg`) — ein Blick nur dort hätte den
  Fehler nicht gezeigt.

  Zwei Lehren, die über den Einzelfall hinausgehen: **Ein Zustand wird ADDITIV gezeigt, nicht
  durch Wegnehmen** — die aktive Regel hat jetzt Rand plus „Aktiv"-Text plus Schalter, drei
  Träger statt einem. Und **wer eine Farbrolle als Kartenfläche einsetzt, prüft sie gegen die
  Fläche, auf der die Karte liegt**, nicht gegen ihren Namen: `surfaceVariant` klingt nach
  „Variante einer Oberfläche" und ist hier der Seitenhintergrund. Für dezente Karten deshalb
  `surface` + `BorderStroke(1.dp, outline)`.

- **320 dp Breite ist die reale untere Grenze, nicht 360.** Der Eigentümer fährt sein Fairphone 6
  mit hochgestellter Anzeigegröße (Dichte 558 statt 480 bei `font_scale = 1.0`) — aus 1116 px
  werden damit **320 dp**. Dort brach „Benachrichtigungs-Schlummer-Knopf" mitten im Wort um
  (`Benachrichtigungs-Sch` / `lummer-Knopf`): ein Wort ohne Trennmöglichkeit, das breiter ist als
  seine Spalte, wird zeichenweise umbrochen. Die `weight(1f)`-Regel neben einem Bedienelement
  verhindert das NICHT — sie sorgt nur dafür, dass die Spalte überhaupt Platz bekommt.
  **Zusammengesetzte Hauptwörter in Beschreibungstexten deshalb kurz halten**, und neue Screens
  einmal bei 320 dp ansehen: `adb shell wm density 540` auf einem 1080-px-Emulator stellt genau
  diesen Fall her (danach `wm density reset`).

- **Eine modale Schublade MUSS einen Streifen frei lassen, sonst gibt es nichts zum Danebentippen.**
  Compose baut `ModalDrawerSheet` mit `sizeIn(minWidth = 240.dp, maxWidth = 360.dp)` und zieht
  KEINEN Rand ab. Auf einem 320-dp-Bildschirm (hochgestellte Anzeigegroesse) fuellte sie damit die
  ganze Breite - am 01.09.2026 pixelweise nachgemessen: weiss bis zur letzten Spalte. Damit fiel
  der wichtigste von vier Schliesswegen aus, und ausgerechnet der ist der beworbene: Material gibt
  der abgedunkelten Flaeche eine eigene Beschreibung ("Navigationsmenue schliessen") und eine
  Dismiss-Aktion. Die Material-Spezifikation nennt *Bildschirmbreite minus 56 dp*; die 56 dp sind
  das Mindestmass eines Beruehrungsziels, deshalb ein fester Abzug und KEIN Prozentsatz - ein
  Prozentsatz waere auf einem Tablet Verschwendung und auf einem kleinen Geraet zu schmal.

- **Drei Beschriftungen fuer einen Code-Pfad sind drei Versprechen.** Bis v1.39.0 loesten
  "Aktualisieren" (Kopfzeile), "Neu laden" (Cache-Karte) und "Jetzt synchronisieren"
  (>24h-Warnung) alle exakt `refreshData(forceRefresh = true)` aus. Der Eigentuemer konnte nicht
  mehr sagen, was welcher tut - und ein Symbol allein kann es nicht sagen: ein Kreispfeil heisst
  ueberall "irgendwas neu laden", nie WAS. Die Regel daraus: **ein Vorgang bekommt einen Namen und
  steht an der Stelle, deren Inhalt er veraendert.** Der Abgleich sitzt jetzt in der
  Kalender-Karte, nicht in der Kopfzeile.

- **Ein Zeitstempel braucht dazu, WORAUF er sich bezieht - sonst ist er eine falsche Zusicherung.**
  "Letzter Sync" zeigte `KEY_LAST_MAINTENANCE`. Der wird aber auch gestempelt, wenn die 6h-Wartung
  den Lauf UEBERSPRINGT (Puffer reichte) und zusaetzlich aus dem Vordergrund. "Vor 15 Minuten"
  konnte also heissen "vor 15 Minuten wurde entschieden, nichts zu tun" - in einem Tab, dessen
  Zweck die Frage "warum kam kein Wecker" ist, genau die falsche Auskunft. Der Code wusste es
  besser als die Oberflaeche: `KEY_LAST_EVENT_LOAD` fuehrt seit jeher den letzten ECHTEN
  Terminabruf, mit dem Kommentar, der andere Wert sei "als Frische-Signal wertlos". Angezeigt
  wurde er nur nie. Jetzt stehen beide da, jeder mit einer Zeile, was er bedeutet.

- **Ein Knopf, der sein Ergebnis wegwirft, ist schlimmer als kein Knopf.** Der Aktualisieren-Pfeil
  in der Cache-Karte rief `getCacheStats()` - eine Methode, die eine INFO-Logzeile schreibt und
  nichts zurueckgibt. Die Anzeige aenderte sich nie, und im Release-Build landete nicht einmal das
  Log irgendwo (der `SimpleFileTree` schreibt erst ab WARN). Daneben behauptete "Cache-Details:
  Cache-Statistiken in Log ausgegeben" einen Vorgang, den der Nutzer nirgends einsehen kann.
  **Vor dem Einbauen einer Diagnose-Anzeige pruefen, ob ihr Ergebnis den Nutzer je erreicht** -
  im RELEASE-Build, nicht im Debug-Build.

