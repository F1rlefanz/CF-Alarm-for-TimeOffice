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
- **„Später" beim Akku-Gate heißt ERLEDIGT, nicht abgebrochen** (`GateLage.akkuGateErledigt`, gilt für AUTO und NACH_KALENDER) — sonst fällt
  der Nutzer aus jedem Zweig heraus und bekommt den nächsten Schritt NIE angeboten.
- **Die Navigationsschublade gehoert in `MainContentScreen`** - nur dort ist sie waehrend der
  Onboarding-Gates nicht komponiert. Und sie braucht `ModalDrawerSheet(drawerState = ...)`: nur
  diese Ueberladung behandelt Zurueck, sonst beendet ein Zurueck auf dem Home-Tab die App.
- **`HueRuleConfig`/`DimmerRuleConfig` brauchen `cameFromSettingsList`**, nicht nur `returnToTab`:
  System-Back und screen-eigener Zurück-Pfeil MÜSSEN für denselben Einstiegspfad zum selben Ziel führen.
- **„Später“ setzt die Gate-Kette SOFORT fort** (seit 1.46.0): erst das Dismissed-Flag abgewartet
  schreiben, dann weiterlesen; ein `SPAETER_*`-Einstieg schaut nur nach vorne (keine Schleife, auch
  bei degradiertem Read). Die Entscheidung ist die reine Funktion `naechsterGateSchritt()` in
  `navigation/OnboardingGates.kt`; gelesen wird nur in `MainScreen.leseGateLage()`.
- **Der Rückweg aus den Regel-Editoren wird NUR in `NavigationViewModel.navigateBackFrom()` aufgelöst.**
- **Gate-Texte verweisen nicht auf vorige Schritte** („wie eben bei …“): seit „Später“ weiterführt,
  kommt jedes Gate auch direkt nach einem ABGELEHNTEN Vorgänger.

## UI-Texte und Compose-Layout — Kurzregeln

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen** — es erscheint Androids
  Systemdialog, keine Liste. Ablauf und Text ändern sich gemeinsam.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich, NIE eine Position — und muss
  auch die HANDLUNGSRICHTUNG stimmen haben** („ausschalten", nicht der bloße Schaltername).
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen**, die ein Test gegen die echte
  Standardkonfiguration prüft — Drift muss auffallen.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als anderer
  ausgeben.** Insbesondere: **eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.**
- **„Die gestellten Wecker bleiben" nur über `gestellteWeckerBleiben()`** — bei Master-Pause und
  ausgeschalteter Automatik ist keiner gestellt. Drei Offline-Texte hatten den Satz fest verdrahtet
  und widersprachen auf derselben Karte dem Pausenhinweis (Emulator, 01.10.2026).
- **Ein Zustand, der die App dauerhaft nicht wecken lässt, MUSS dort stehen, wo der Nutzer
  nachsieht** — nicht nur an seinem Schalter. Die Master-Pause hat dabei **Vorrang vor dem angezeigten
  nächsten Wecker** (Karte „Nächster Wecker“, bis 1.49 „N aktive Alarme“), und es steht höchstens
  EIN Zusatz-Hinweis gleichzeitig.
- **Eine Meldung erreicht ihren Nutzer nur, wenn sie in GENAU dem Zustand gerendert wird, in dem sie
  gesetzt wird** — Renderstelle UND Zustand bei jedem neuen Meldungsfeld per grep prüfen.
- **Kein `SnackbarDuration.Indefinite`**: es blockiert über den Mutex des `SnackbarHostState` alle
  übrigen Meldungen samt ihrer `clearError()`-Aufrufe. Bleibende Zustände sind Karten im Status-Tab.
- **Auf dem ANZEIGEPFAD bekommt ein sicherheitsrelevanter Compose-Parameter KEINEN Default**
  (`masterPausePaused` in `NaechsterWeckerKarte`, `WeckerTabContent`, `StatusTabContent`;
  in `NaechsterWeckerKarte` ebenso `autoAlarmEnabled`) — sonst
  fällt ein künftiger Aufrufer wortlos in den alten Fehler zurück. Reine Textfunktionen wie
  `noShiftExplanation()` dürfen einen Default haben; dort ist der Wert ein Zusatz, keine Zusage.
- **Hilfe gibt es genau einmal: auf der Website (`docs/`).** Die App verweist nur, über
  `ui/hilfe/HilfeThema` (das „?“ in den Kopfzeilen, „Mehr dazu“ unter Status-Warnungen). Keine
  Anleitungstexte in die App kopieren. Jeder neue Anker braucht eine `id` auf der Seite;
  `tools/doku/pruefe_hilfe.py` prüft das und zusätzlich, dass jeder „Begriff“ der Anleitung in der
  App vorkommt. **Wer einen Knopf umbenennt, zieht die Website mit.** „Was ist neu“ ist die
  Ausnahme: es wird beim Bauen aus `CHANGELOG.md` geschnitten (Gradle `neuigkeitenAusChangelog`),
  weil es zur INSTALLIERTEN Version gehört, die Website aber `main` zeigt.
- **Deutsche Nutzer-Texte in `UITextConstants` ohne Aufrufer löschen, nicht liegen lassen.**
- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.**
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite** — für schmale, geteilte Buttons gibt es
  `CompactButton`/`CompactOutlinedButton`.
- **Eine `LazyColumn` in einer `Column` braucht `weight(1f)`** — sonst wird der Knopf darunter
  unerreichbar (real passiert). Gleiches gilt für lange Dialoge: scrollbar machen.
- **`RadioButton`/`Checkbox` mit `onClick = null` brauchen `heightIn(min = 48.dp)` am Row.**
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`.
- **Eine modale Schublade laesst einen Streifen frei** (`min(360.dp, Bildschirmbreite - 56.dp)`) -
  sonst fehlt auf schmalen Anzeigen die Flaeche zum Danebentippen, der beworbene Schliessweg.
- **Eine Benachrichtigung öffnet die App NUR über `MainActivity.einstiegIntent()`** (MAIN +
  LAUNCHER, NEW_TASK|CLEAR_TOP|SINGLE_TOP, `EXTRA_EINSTIEG`). Ein expliziter Intent ohne
  MAIN/LAUNCHER wird bei fehlendem Task dessen Basis-Intent, und jeder spätere Launcher-Start legt
  eine weitere MainActivity obendrauf (Emulator 30.09.2026). `EinstiegIntentTest` hält beide
  Absender daran.
- **Ein Vorgang, ein Name, an der Stelle, deren Inhalt er veraendert.** Drei Beschriftungen fuer
  einen Aufruf sind drei Versprechen; ein blosses Symbol kann nicht sagen, WAS es tut.
- **Ein Zeitstempel nennt, worauf er sich bezieht** - und wer eine Diagnose-Anzeige einbaut, prueft
  vorher, ob ihr Ergebnis den Nutzer im RELEASE-Build je erreicht. Kennt eine Zeile nur EINEN von
  mehreren Mechanismen, sagt ihr Name welchen ("Nächster regelmäßiger Abgleich", nicht "Nächster
  Abgleich" - an Rufbereitschafts-Tagen stand sie sonst 6 h ueber der stuendlichen Abfrage).
- **Eine Zeile, die das ZIEL eines Knopfs nennt, wird beim Tippen nachgerechnet** (1.48.0,
  "Überspringen", seit 1.50.0 "Nicht wecken"): Anzeigen rechnen nur bei einer Emission, nicht mit der Uhr. Ist das genannte
  Ziel inzwischen vorbei, lehnt der Knopf ab und sagt, dass NICHTS geschah - er trifft nie still
  ein anderes Ziel als das genannte.
- **Text links, Bedienelement rechts: unter 270 dp (Zeilenbreite ÷ Schriftskalierung)
  untereinander** - `InhaltUndBedienung`/`AuswahlRow` mit `UNTEREINANDER_UNTER` in
  `ui/screens/tabs/wecker/WeckerLayout.kt`, Messwerte dort (Emulator 13.10.2026: 320 dp + Schrift 1,3 brach sonst
  mitten im Wort, "überspri|ngen"). Ein geschuetztes Leerzeichen hilft nur, wo die Zeile breit
  genug ist - in einer zu schmalen Spalte macht es aus "17.10. 05:30" ein Wort, das erst recht bricht.
- **Ein Hinweis zu einem Knopf steht direkt unter DIESEM Knopf und nennt ihn beim Namen** - nicht
  unter einer ganzen Knopfreihe, die bei 320 dp umbricht. Und er verspricht nur, was in JEDEM
  Zustand eintritt, den die Karte kennen kann (Pause, fehlende Berechtigung).
- **Keine Karte bekommt `surfaceVariant` als Fläche** — in der hellen Palette ist das derselbe
  Wert wie `background`, die Karte verschwindet. Dezente Karten: `surface` +
  `BorderStroke(1.dp, outline)`. Und ein Zustand wird ADDITIV gezeigt (Rand, Text), nie durch
  Wegnehmen der Fläche.
- **Etiketten (klein, AUF einer Karte) sind gefuellt** — Status als Pille aus `tonPaar()`
  (`ui/theme/Theme.kt`: Erfolg/Warnung/Info, Flaeche + eigene Textfarbe), neutral auf
  `surfaceContainer` + Rand. **Farbe heisst ZUSTAND, nie Funktion:** die Wirkungs-Kaertchen
  (Nicht stören/Dimmen/Licht/Kalender-Abfrage) unterscheidet ein Symbol (dasselbe wie am Tab bzw.
  in den Einstellungen), keine eigene Farbe. Der Eigentuemer vermisste am Fairphone Farbe; eine
  Farbe je Funktion haette Gruen/Blau/Orange zweideutig gemacht, er waehlte das Symbol
  (14.10.2026). Die Ampelfarben selbst erreichen auf ihrer weichen Flaeche KEINE 4,5:1
  (`ThemeKontrastTest` prueft jedes Paar). Knoepfe an Listenzeilen: umrandeter Kompaktknopf
  (`CompactContentPadding`), untereinander LINKSbuendig (`untereinanderAusrichtung = Start`) — ein
  Textknopf allein am rechten Rand sah am Fairphone verloren aus (14.10.2026).
- **Die Wecker-Tagesliste (#139, `viewmodel/WeckerTage.kt`) waehlt den naechsten klingelnden
  Wecker ueber `AlarmSkipUseCase.naechsterKlingelnderWecker` — dieselbe Auswahl wie der Knopf**,
  und nur dieser bekommt „Nicht wecken“ (Variante A, Eigentuemer 14.10.2026: der Skip-Merker
  bleibt EINER; ein spaeterer Dienst geht ueber „Dienst fällt aus“ am Tag). Tagesanker = Kalendertag
  der Weckzeit, wie bei Skip und Freigabe. Ein abgelaufener, noch nicht geraeumter Skip
  (`SkipLage.wirktNoch`) darf die Liste nicht sperren. Die Liste rechnet beim Zurueckkehren in die
  App und bei jedem neuen Ziel nach — sonst haengt der Knopf an einem verstrichenen Wecker
  (Review 14.10.2026). Kaertchen kommen aus `SchichtFolgen`, keine Nachbildung.
  **Ein schwebender Snooze steht als Zeile „Weckt wieder · schlummert“** (Tagesliste) bzw. als
  „… · schlummert“ in „Nächster Wecker“, Quelle ist der Snooze-Merker (`schwebendesSchlummern`).
  Vorher war ein geschlummerter Wecker nirgends zu sehen: sein Eintrag ist nach dem Klingeln aus
  dem Bestand, und die Karte nannte den schon geklingelten Wecker (Emulator, 14.10.2026). Der
  Merker hat keinen Beobachter; `rememberWeckerStand` liest ihn beim Fortsetzen, bei
  Bestandsaenderung, sobald `AlarmSoundService.alarmActive` faellt (Schlummern aus der
  Benachrichtigung bei offener App) und EINMAL nach dem naechsten Wechsel (kein Dauer-Ticker).
  **Die Dienste eines Tages kommen zusaetzlich aus dem `ShiftSpanStore`** (ROH, ueber
  `ShiftUiState.letzterSchichtStand`, bewusst NICHT durch `filtereSpannen` — die Liste will am
  freigegebenen Tag nennen, welcher Dienst ausfaellt; Kaertchen gibt es dort trotzdem keine): sonst
  verliert der Tag nach „Nicht wecken“ und nach dem Klingeln seinen Dienst, und am Krankheitsmorgen
  ist „Dienst fällt aus“ nicht mehr erreichbar. Heute bleibt, solange der Dienst laeuft; ein
  kuenftiger Tag entsteht nie aus einer Spanne allein. Dienstzeiten nur ueber
  `ui/components/Dienstzeit.kt` („ganztägig“ ueber Kalendertage, nicht ueber 24 h — Zeitumstellung).
- **Neue Screens einmal bei 320 dp ansehen** (`adb shell wm density 540` auf 1080 px, danach
  `wm density reset`) — das Gerät des Eigentümers läuft so. Lange zusammengesetzte Hauptwörter
  brechen dort mitten im Wort um.

---
- **Die Statuszeile je Schicht (#70, `viewmodel/SchichtFolgen.kt`) zählt über DIESELBEN Funktionen wie
  die Laufzeit**: `findRuleForShift` (eine wirksame Dimm-Regel, weitere = „+N ohne Wirkung“),
  `passtAufSchicht` (eigene und ALL-Regeln feuern gemeinsam), exakter DND-Abgleich. Keine Nachbildung.
  Hue nur über `IHueConfigRepository`, gelesen nur bei Abo. Umbruch nur hinter dem Trenner (NBSP).
- **Texte, die das Kalenderfenster nennen, lesen `CalendarUiState.vorausschauTage`**, nie eine feste 14.
- **Der Ladetext der Start-Wiederherstellung sagt „Suche nach einer Anmeldung …“**, nicht „wird
  übernommen“ — er steht bei JEDER frischen Installation da.
- **Endlos-Animationen in der Zeichenphase lesen** (`graphicsLayer { }` mit `State` ohne `by`), und
  Listen mit `rememberSaveable` je Eintrag brauchen `key = { it.id }` — sonst wandert ein offener
  Löschdialog zur Nachbarregel.
