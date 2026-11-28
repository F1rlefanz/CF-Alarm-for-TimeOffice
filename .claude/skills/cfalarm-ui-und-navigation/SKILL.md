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
- `reference/typ-seite.md` — Umbau Schichttyp: Typ-Liste, Typ-Seite, Einordnen, „Ohne Kalendereintrag", Tagesleisten-Karte
- `reference/pflichtregeln-aus-claude-md.md` — die Pflichtregeln aus der CLAUDE.md im vollen Wortlaut, mit Begründung

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
- **`HueRuleConfig` braucht `cameFromSettingsList`**, nicht nur `returnToTab`: System-Back und
  screen-eigener Zurück-Pfeil MÜSSEN für denselben Einstiegspfad zum selben Ziel führen. (Der
  Zwilling `DimmerRuleConfig` ist mit dem Dimmer-Bereich entfallen, #226.)
- **Das Menü hat fünf Ziele** (`MainTab`: Übersicht, Wecker, Philips Hue, System-Status,
  Einstellungen; SPEC 3.7, Test `MainTabZieleTest`). `MainTab.DIMMER` und die Zustände
  `DimmerSettings`, `DimmerRuleConfig`, `DimmerPreview` samt Aktionen sind seit #226 entfallen;
  Dimm-Fenster stellt man am Typ ein, die Geräte-Werte unter Einstellungen. Ein gespeicherter Reiter
  existiert nicht (nach Prozesstod startet die App auf der Übersicht).
- **„Später“ setzt die Gate-Kette SOFORT fort** (seit 1.46.0): erst das Dismissed-Flag abgewartet
  schreiben, dann weiterlesen; ein `SPAETER_*`-Einstieg schaut nur nach vorne (keine Schleife, auch
  bei degradiertem Read). Die Entscheidung ist die reine Funktion `naechsterGateSchritt()` in
  `navigation/OnboardingGates.kt`; gelesen wird nur in `MainScreen.leseGateLage()`.
- **Der Rückweg aus dem Hue-Regel-Editor wird NUR in `NavigationViewModel.navigateBackFrom()` aufgelöst.**
- **`OrtFuerTageslicht` (#140) traegt sein `zurueckZu`** (seit #226 zwei Einstiege: Einstellungen
  und Hue-Regel-Editor)
  und hat einen eigenen Zweig in `rueckwegZiel`. Die Editoren ueberleben den Umweg ueber den
  `SaveableStateHolder` in `MainScreen`, verschluesselt mit `sitzung` (`editorFormularSchluessel`);
  jedes frische Oeffnen bekommt eine neue `sitzung`, beim endgueltigen Verlassen wird der Zustand
  entfernt (`verworfenesEditorFormular`). Wer einen weiteren Unterscreen aus einem Editor heraus
  oeffnet, haengt sich an dasselbe Muster - sonst verliert der Nutzer seine Eingabe.
- **Gate-Texte verweisen nicht auf vorige Schritte** („wie eben bei …“): seit „Später“ weiterführt,
  kommt jedes Gate auch direkt nach einem ABGELEHNTEN Vorgänger.
- **`viewmodel/` importiert nichts aus `ui/`** (#147). Ein Zustandstyp, den das ViewModel baut
  (z. B. `OrtBildschirmZustand`), liegt in `viewmodel/`; der Bildschirm importiert ihn von dort.
  Die Kante war zweimal unbemerkt entstanden (#146 `kurzesDatum`, #147); seither prüft sie
  `tools/invarianten/pruefe_code.py` mit. Dieselbe Prüfung bestimmt die Hintergrundpakete als
  Rest (alles außer `ui/`, `viewmodel/`) statt über eine feste Liste: `freietage/` und
  `navigation/` waren dort nie eingetragen und blieben ungeprüft, `tageslicht/` musste von Hand
  nachgezogen werden.

## UI-Texte und Compose-Layout — Kurzregeln

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen** — es erscheint Androids
  Systemdialog, keine Liste. Ablauf und Text ändern sich gemeinsam.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich, NIE eine Position — und muss
  auch die HANDLUNGSRICHTUNG stimmen haben** („ausschalten", nicht der bloße Schaltername).
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen**, die ein Test gegen die echte
  Standardkonfiguration prüft — Drift muss auffallen.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als anderer
  ausgeben.** Insbesondere: **eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.**
  Und **„noch nicht geprüft" ist weder „verloren" noch „OK"**: `calendarAuthorizationValid`
  startete mit `false`, und nach jedem Kaltstart stand 1–2 s „Kalender-Autorisierung verloren"
  samt Erneuern-Knopf (#148, am Emulator vorher/nachher belegt). Seit 1.56.1 dreiwertig; Anzeigen
  lesen `kalenderZugriffVerloren` / `kalenderZugriffUngeprueft`, nie das Rohfeld mit `!`.
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
  (`masterPausePaused` in `rememberNaechsterWecker`, `WeckerTabContent`, `StatusTabContent`;
  in `rememberNaechsterWecker` ebenso `autoAlarmEnabled`) — sonst
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
  (Review 14.10.2026). Kaertchen kommen aus `SchichtFolgen`, keine Nachbildung. Ein Wecker „wie
  <Typ>“ an einem Tag ohne Kalendereintrag ist dort KEIN Dienst (`baueWeckerTage` nimmt
  `AlarmUseCase.istOhneEintragWecker` aus `schichtenDesTages`; nur das Licht-Kaertchen bleibt).
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
- **Die Uebersicht ordnet „Nächster Wecker“ und „Nächste Schicht“ nach Zeit** (#141,
  `uebersichtReihenfolge` in `HomeTabContent.kt`): frueher oben, Gleichstand Wecker. Verglichen
  wird der Zeitpunkt, den die Wecker-Karte NENNT (`NaechsterWeckerStand.zeitpunkt`, eine Rechnung
  fuer Karte und Reihenfolge), mit dem Beginn der Schicht, die die Schicht-Karte ZEIGT. **Fehlt
  einer, bleibt die Schicht oben — darauf stuetzen sich `NO_SHIFT_HINWEIS_PAUSIERT` und der
  `AUTHORIZATION_LOST`-Text** („die Karte darunter“). Wer einen Text auf eine Position stuetzt,
  muss zeigen, dass in SEINEM Zustand ein Zeitpunkt fehlt.
- **Gleichrangige Knoepfe nebeneinander ueber `KnopfZeile` (`ui/components`), nicht `Row` +
  `weight(1f)`**: die Gewichtung kuerzte die Beschriftung mit Auslassung („Bea…“, „Neue R…“ bei
  320 dp + Schrift 1,3, Emulator 14.10.2026). `KnopfZeile` misst die natuerlichen Breiten und
  stellt linksbuendig untereinander, wenn sie nicht passen. Etikett neben einem Namen: `FlowRow`.
- **Ein umbrechender `Text` VOR Icons in einer `Row` braucht `weight(1f, fill = false)`**: ein
  umbrechender Text meldet die volle Restbreite, die ungewichteten Icons danach bekommen 0 dp und
  verschwinden (`Modifier.size` respektiert die Constraints). So fehlten Still-/Rufbereitschafts-
  Marker genau an der AUSGESCHALTETEN Schicht (`ShiftConfigScreen`, Runde 4 zu #140).
- **Titel- und Ueberschriften-Stile tragen `Hyphens.Auto`** (`ui/theme/Type.kt`): deutsche
  Komposita brachen dort ohne Trennstrich mitten im Wort („Benachrichtigunge|n“). Fliesstext nicht.
  **Immer mit `localeList = Deutsch`**: Compose setzt die Trennsprache nur aus `TextStyle.localeList`,
  sonst trennt der Hyphenator nach der SYSTEMSPRACHE (englisches Geraet = englische Muster an
  deutschen Titeln). Abgesichert durch `TypographySilbentrennungTest`.
- **Neue Screens einmal bei 320 dp ansehen** (`adb shell wm density 540` auf 1080 px, danach
  `wm density reset`) — das Gerät des Eigentümers läuft so. Lange zusammengesetzte Hauptwörter
  brechen dort mitten im Wort um.

---
- **Die Statuszeile je Schicht (#70, `viewmodel/SchichtFolgen.kt`) liest, was die Laufzeit liest**:
  seit #219 Dimmen und „Nicht stören" am Typ (`ShiftDefinition.dimmen`/`.nichtStoeren`,
  Rufbereitschaft über die Kategorie), Hue über `passtAufSchicht` (eigene und ALL-Regeln feuern
  gemeinsam). Keine Nachbildung. Hue nur über `IHueConfigRepository`, gelesen nur bei Abo. Umbruch
  nur hinter dem Trenner (NBSP).
- **Die Zeile der Typ-Liste ist EINE Zeile** (#220): `listenKurzform(definition, folgen)`
  (`ShiftConfigScreen.kt`) = `kurzform(…, dimmKurzform(definition))` (`viewmodel/SchichtFolgen.kt`)
  setzt den Dimm-Text an die Stelle von „Dimmen: …". Reihenfolge: `kein Wecker` · Dimm-Text ·
  `Licht: …` (nur mit Wecker) · `Nicht stören: …`. Der Dimm-Wortlaut kommt aus
  `DimFensterAbbildung.kurzform`, nie nachgebaut; „Dimmen: N Fenster" (`alsText()`) steht NUR in der
  Tagesliste. Ein Test der Zeile prüft den GANZEN Text (`TypListeKurzformTest`).
- **Texte, die das Kalenderfenster nennen, lesen `CalendarUiState.vorausschauTage`**, nie eine feste 14.
- **Der Ladetext der Start-Wiederherstellung sagt „Suche nach einer Anmeldung …“**, nicht „wird
  übernommen“ — er steht bei JEDER frischen Installation da.
- **Endlos-Animationen in der Zeichenphase lesen** (`graphicsLayer { }` mit `State` ohne `by`), und
  Listen mit `rememberSaveable` je Eintrag brauchen `key = { it.id }` — sonst wandert ein offener
  Löschdialog zur Nachbarregel.

## Umbau Schichttyp: Typ-Seite, Einordnen, „Ohne Kalendereintrag" — Kurzregeln

Hergang, Textwahrheits-Entscheidungen und Tests: `reference/typ-seite.md`.

- **Ein bildschirmlokaler `BackHandler` darf nur GATEN, nie das Ziel bestimmen** (#187): die
  Typ-Seite hat `BackHandler(enabled = geaendert) { rueckfrageOffen = true }`; ohne Änderungen löst
  der `BackHandler` in `MainScreen` über `navigateBackFrom` auf. Das Ziel steht nur in `rueckwegZiel`.
- **Ein neuer `NavigationState` hat FÜNF Stellen**: Zustand, Aktion, Zweig in `rueckwegZiel`, Zweig in
  `MainScreen` und — für Seiten des Typ-Bereichs — `imTypBereich` (sonst springt der Scroll-Stand der
  Typ-Liste). **`rueckwegZiel` hat `else -> MainTab.HOME`**: ein vergessener Zweig kompiliert und
  landet still auf der Übersicht; fangen kann das nur `NavigationViewModelTest`.
- **Eine Wahl, die die Seite nicht verlässt, ist ein `Dialog`, kein `NavigationState`**
  (`KategorieWahlDialog`, `EinordnenDialog`): Zurück schließt ihn, bevor ein `BackHandler` etwas
  erfährt.
- **Ein Entwurf, der Drehen, Dunkelmodus und Schriftgröße überleben soll, ist EIN Wert mit `Saver`**
  (`TypEntwurf.alsSpeicherListe()`), sein Ausgangsstand wird beim Öffnen eingefroren, nicht auf das
  `ShiftDefinition`-Objekt gekeyt — jede Config-Emission brächte sonst ein neues Objekt und setzte ihn
  zurück. „Geändert" heißt `ausgang != entwurf`. Eingabe-Callbacks sind Umformungen
  (`aendere { it.copy(...) }`), keine fertigen Werte. Gespeichert wird höchstens einmal
  (`TypSeiteSpeicherung`); ein verschwundener Typ wird nie neu angelegt.
- **Ein Teil des Entwurfs, den ein Hintergrund-Schreiber ändern kann, folgt dem Gespeicherten,
  solange er unberührt ist** (#221/#222, `TypSeiteEntwurf.nachgezogen`, `LaunchedEffect`; Dimmen und
  „Nicht stören", Schalter und Fenster je für sich). Sonst hält `anwenden()` nach einer Migration
  unter der offenen Seite die nie angefasste Karte für geändert, und „Speichern" löscht die Fenster.
- **Dimmen und „Nicht stören" reisen im Bundle als EIN JSON-Element an achter Stelle**
  (`EntwurfTeile`, beide Teile einzeln `null` = gespeicherter Stand); ein weiteres strukturiertes
  Feld kommt dort hinein, nicht an eine neunte Stelle.
- **Neuanlage läuft über die Typ-Seite** (#190): `navigateToNeuerTyp(vorlage, fundCode, tab)`, Vorlage und
  Fund-Code reisen im `NavigationState.TypSeite`, geschrieben wird NUR mit „Erstellen"
  (`TypSeiteSpeicherung.erstelle`, höchstens einmal für BEIDE Wege).
- **„Ohne Kalendereintrag" ist eine EIGENE Seite** (`NavigationState.OhneEintragSeite`), keine
  `TypSeite` mit Sonderkennung; ihre Wahl schreibt beim Tippen (`waehleOhneEintrag`, einziger
  Aufruf je Tipp) — die einzige Ausnahme vom Entwurf mit Speichern, und seit #223 gilt sie für die
  GANZE Seite: jede Änderung (auch Dimmen, „Nicht stören", „Weitere Optionen") geht sofort als
  UMFORMUNG an `aendereOhneEintrag`, gerechnet wird auf `ShiftUiState.ohneEintragStand` (beauftragt,
  noch nicht gespeichert), nie auf `currentShiftConfig`, und armiert gegen den Vorgänger-AUFTRAG.
- **Die Schichttypen-Liste ist NUR in der Anzeige nach Kategorien gruppiert** (`schichttypBloecke`):
  die gespeicherte Reihenfolge wird nie umsortiert (die Erkennung nimmt den ersten Treffer),
  Bearbeiten und Löschen arbeiten per `id`, nie per Index einer Anzeigeliste.
- **Marke · Text · Knopf in einer Zeile: die Textbreite bei 320 dp nachrechnen** — Marke links und
  Papierkorb als eigene Spalte ließen 146 dp, die NBSP-Abschnitte der Statuszeile brachen mitten
  im Wort; der Papierkorb steht deshalb in der Namenszeile.
- **Auch ein leerer Platzhalter-`Scaffold { }` benutzt seinen `padding`-Parameter** — Lint
  `UnusedMaterial3ScaffoldPaddingParameter` ist ein Fehler, die Schleuse blockiert.
- **Ein Text unter einem Editor-Feld wird gegen ALLE Kategorien geprüft, in denen das Feld sichtbar
  ist** (nicht nur gegen Dienst), und **eine Meldung, die einen Knopfzustand erklärt, liest dieselbe
  Größe wie der Knopf** (`TypAbschnittLogik.fehltMuster` = Chips MIT dem Text im „+"-Feld).
- **WAS ein Sonderfall-Kasten sagt, entscheidet EINE Funktion: `ShiftDefinitionTexte.sonderfall()`**;
  ein ausgeschalteter Typ hat keinen Kasten, Krank und Ignorieren haben keine Karte „Wecker".
- **Kein Text am Typ verspricht, was der Typ (noch) nicht steuert**: seit Etappe 3 stehen Dimmen und
  „Nicht stören" am Typ, Licht hängt bis Etappe 4 an Regeln; ein stummer Wecker startet kein Licht.
  **Wer Etappe 4 baut, tauscht die vorgemerkten Sätze** (Liste in `reference/typ-seite.md`).
- **Die Karte „So läuft ein Tag" ist nur Verdrahtung** der Adapter aus `tagesleiste/` und zeigt nur,
  was zur Laufzeit wirklich passiert: ein ausgeschalteter Typ hat keinen Ablauf, Tageslicht-
  Bedingungen werden übergeben. Weckzeit/Wecker/Kategorie, Dimmen und „Nicht stören" kommen aus dem
  ENTWURF (#221/#222, als Parameter von `TagesleisteKarteLogik.einstellungenAus`, nie als vorab
  veränderte `TagesleisteQuellen`), Licht und Spannen hängen am GESPEICHERTEN Namen;
  `tagesleisteQuellen == null` heißt „wird gelesen", nie leere Leiste.
- **„Weitere Optionen" ist ein bildschirmfüllender `Dialog` auf demselben Entwurf** (#224, kein
  `NavigationState`; offen-Zustand `rememberSaveable` in `TypSeiteFormular`). So ein Dialog braucht
  `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`, setzt die
  Symbolfarbe der Systemleisten am Dialogfenster selbst, und seine Liste braucht
  `consumeWindowInsets(padding)` plus `imePadding()`.
- **Was nur unter „Weitere Optionen" einstellbar ist und die Aussage der Karte ändert, steht auch auf
  der Karte** (`DimmenAbschnittLogik.blattHinweis`): ein Nachlauf (Titel und Schalter sagen sonst
  „bis Wecker") und der Weg zu einem Fenster ohne „Wann".
- **Ein Regler, dessen Wert sofort gespeichert wird, gibt ihn erst mit `onValueChangeFinished`
  weiter** (#238; Zwischenstand lokal) — `onValueChange` feuert bei jedem Zwischenwert, und an der
  festen Zeile ist jeder Schreibaufruf ein Speichern samt Alarm-Abgleich.
- **Ein `OutlinedButton`, der einen WERT zeigt (Uhrzeit), bekommt Fläche und Textfarbe ausdrücklich**
  (`ZeitKnopf`): mit den Material-Vorgaben sah „22:00" gesperrt aus.
- **Wer den Text eines `AlertDialog` verlängert, prüft ihn bei 320 dp UND Schrift 1,3**; ein langer
  Text braucht `Modifier.verticalScroll(rememberScrollState())` im `text`-Slot.
- **Ein Sammelhinweis über mehreren Schaltern wird gegen JEDEN Schalter geprüft**, auch gegen bedingt
  sichtbare Unteroptionen mit umgekehrter Polung (#225, „Wiederholte Anrufer durchlassen").
- **Die Play-Pflicht-Offenlegung der Bedienungshilfe gibt es EINMAL** (#225):
  `BedienungshilfeOffenlegung` in `StatusPermissionCards.kt`, gerufen von der Status-Karte und der
  Zeile „Bedienungshilfe" in den Einstellungen; nur sie ruft `openAccessibilitySettings`.
