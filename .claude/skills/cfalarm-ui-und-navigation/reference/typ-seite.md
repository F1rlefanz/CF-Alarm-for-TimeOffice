# Umbau Schichttyp: Typ-Liste, Typ-Seite, Einordnen, „Ohne Kalendereintrag" — Hergang

> Hergang zu den Kurzregeln im Abschnitt „Umbau Schichttyp" der `SKILL.md` daneben: welcher Befund
> die Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde. Stand:
> Etappe 2 abgeschlossen (1.58.0). Die Datenseite (Kategorie, Ignorieren, Auswahllisten,
> Einordnen-Logik, `tageOhneEintrag`) steht im Skill `cfalarm-kalender-und-schichten`.

## Inhalt

- Navigation: Gate-`BackHandler`, fünf Stellen je Zustand, Dialog statt Zustand (#187, #188, #191)
- Der Entwurf der Typ-Seite (#187)
- Neuanlage über die Typ-Seite (#190)
- „Ohne Kalendereintrag" als eigene Seite (#177, #191)
- Die Schichttypen-Liste (#170)
- Einordnen: „Im Kalender gefunden, noch nicht eingeordnet" (#176)
- Karte „Typ" (#188)
- Karte „Wecker" und Sonderfall-Kasten (#174, #189)
- Karte „So läuft ein Tag" (#197, Gegenprüfung Etappe 2)
- Textwahrheit statt Prototyp-Wortlaut — und die vorgemerkten Satztausche
- Layout-Funde

## Navigation: Gate-`BackHandler`, fünf Stellen je Zustand, Dialog statt Zustand (#187, #188, #191)

- **Die Typ-Seite (`ui/screens/typ/TypSeite.kt`) gatet nur.** `BackHandler(enabled = geaendert) {
  rueckfrageOffen = true }`: solange es ungespeicherte Änderungen gibt, fängt er Zurück ab und fragt
  („Änderungen verwerfen?"); ohne Änderungen ist er aus, und der unveränderte `BackHandler` in
  `MainScreen` löst über `navigateBackFrom(state)` auf. Zurück-Pfeil, „Verwerfen" und „Speichern"
  gehen über dasselbe `onZurueck`. Das Ziel steht nur in `rueckwegZiel`:
  `TypSeite(definitionId, returnToTab)` → `ShiftConfig(returnToTab)`. Am Emulator belegt
  (17.10.2026): Taste und Pfeil ohne Änderung → Liste; mit Änderung → Rückfrage, „Weiter
  bearbeiten" bleibt, „Verwerfen" → Liste, erneutes Öffnen zeigt den gespeicherten Stand.
- **`rueckwegZiel` hat ein `else -> MainTab.HOME`.** Ein neuer `NavigationState` ohne eigenen Zweig
  kompiliert und landet still auf der Übersicht — die einzige der Stellen, die kein Compiler fängt;
  es fängt sie nur `NavigationViewModelTest` („Zurueck aus der Typ-Seite …", am 17.10.2026 rot
  belegt: Zweig auf `MainContent(HOME)` umgelenkt → 2 von 35 Tests rot). Seit #191 sind es FÜNF
  Stellen: Zustand, Aktion, `rueckwegZiel`, Zweig in `MainScreen` und `imTypBereich` in `MainScreen`
  (sonst springt der Scroll-Stand der Typ-Liste beim Öffnen der Seite nach oben). Ein Test, der den
  `rueckwegZiel`-Zweig prüft, nimmt einen Tab ungleich der Vorgabe (WECKER), sonst fiele das
  Fehlen nicht auf.
- **Kategorie-Wahl und Einordnen sind `Dialog`e** (`KategorieWahlDialog`, `EinordnenDialog`): ein
  eigenes Fenster, Zurück schließt ihn, bevor ein `BackHandler` etwas erfährt (am Emulator belegt:
  nach Zurück steht `MainActivity` oben und die Liste ist offen). Ein eigener `NavigationState` hätte
  `rueckwegZiel` und den Gate-Handler der Seite mitbedenken müssen.
- **Zurück aus „„<Name>" öffnen" (feste Seite) führt in die Typ-Liste, nicht zurück zur festen
  Seite:** „öffnen" ist `navigateToTypSeite(id, returnToTab)`, eine gewöhnliche Typ-Seite; ein
  Rückweg zur festen Seite bräuchte ein Feld im `TypSeite`-Zustand (wie `cameFromSettingsList` bei
  den Regel-Editoren) und wurde bewusst nicht gebaut — der Dialog davor schloss sich beim Öffnen
  ebenso. Tests: `NavigationViewModelTest` („Zurueck aus der festen Seite …", „Typ oeffnen von der
  festen Seite …").

## Der Entwurf der Typ-Seite (#187)

- **EIN Wert mit `Saver`** (`TypEntwurf.alsSpeicherListe()`), Ausgangsstand beim Öffnen eingefroren.
  Jede Config-Emission — auch die eigene nach dem Speichern, auch fremde Schreiber wie
  `observeExternalConfigChanges` — liefert ein neues `ShiftDefinition`-Objekt und setzte einen
  darauf gekeyten Entwurf zurück. „Geändert" heißt `ausgang != entwurf` (zurückgetippt = nichts zu
  verwerfen).
- **Speichern** über `TypSeiteSpeicherung` höchstens einmal (Doppeltipp), auf den GERADE
  gespeicherten Typ (`speichernIn`), und ein fehlender Typ wird nie angehängt (`TYP_FEHLT`; der
  frühere Dialog tat das bei der Neuanlage).
- **Ein Eingabe-Callback ist eine Umformung** (`aendere { it.copy(...) }`): zwei Eingaben im selben
  Frame überschrieben sich sonst mit einem veralteten Stand.
- Am Emulator belegt: der Entwurf samt offener Rückfrage übersteht `cmd uimode night yes` und
  `wm density 540`. Tests: `TypSeiteEntwurfTest`.

## Neuanlage über die Typ-Seite (#190)

Es gibt keinen Schicht-Dialog mehr. „+" in der Liste und „Oder als neuer Typ" im Einordnen-Dialog
rufen `NavigationViewModel.navigateToNeuerTyp(vorlage, fundCode, tab)`; die Vorlage (ein NICHT
gespeicherter `ShiftDefinition`, `definitionId = vorlage.id`) und der Fund-Code reisen im
`NavigationState.TypSeite` (`vorlage`, `fundCode`), in keinem zweiten Speicher.

- **Geschrieben wird NUR mit „Erstellen"** über `TypSeiteSpeicherung.erstelle`: mit Fund-Code über
  `ShiftViewModel.legeTypAusCodeAn` (räumt den Code bei anderen Typen ab, stellt `isEnabled` zurück),
  sonst hinten angehängt über `updateShiftConfig`; höchstens einmal für BEIDE Wege. `speichernIn`/
  `TYP_FEHLT` bleibt, wie es war — die Neuanlage ist ein eigener Zweig, kein aufgeweichter.
- Im Neuanlage-Modus gibt es kein „verschwunden" (der Typ steht nie in der Konfiguration), keinen
  Umbenennungs-Hinweis (`bestehenderName = null`), den Titel „Neuer Typ" (bei „+" wäre
  `definition.name` leer) und „Erstellen" aktiv OHNE Änderung (die Vorlage aus dem Fund ist schon
  ein gültiger Typ; beim „+" bleibt er bis Name und Muster stehen gesperrt). Die leere Vorlage des
  „+" ist `ShiftEditFormular.leererTyp()`; `TypEntwurf.aus(leer) == TypEntwurf.aus(null)` hält ein Test.
- **Banner, Textwahrheit statt Prototyp:** „Neuer Typ aus dem Kalender-Code X als „Kat". Alles
  darunter ist eine Voreinstellung … Angelegt ist der Typ erst mit „Erstellen"." — NICHT „Neu
  angelegt" (Issue und Prototyp): der Prototyp legt sofort an, die App erst mit dem Knopf, und die
  Rückfrage („Dieser Typ ist noch nicht angelegt …") stünde sonst im Widerspruch. Er nennt die
  GERADE gewählte Kategorie, verspricht kein Dimmen und kein Licht (Festlegung des Betreuers) und
  erscheint nur mit Fund-Code. Die Rückfrage der Neuanlage nennt, wo der Code bleibt.
- Tests: `TypSeiteNeuanlageTest`, drei Ablaufszenarien in `EinordnenAblaufTest`, zwei in
  `NavigationViewModelTest` (Mutationsprobe 17.10.2026, fünf Mutationen, jede rot: `erstelle`
  ignoriert den Fund-Code · ohne Einmal-Sperre · `NavigateToNeuerTyp` verliert die Vorlage · Banner
  sagt „Neu angelegt" · `isEnabled` aus dem Entwurf geht verloren).

## „Ohne Kalendereintrag" als eigene Seite (#177, #191)

- **Eigene Seite** (`NavigationState.OhneEintragSeite(returnToTab)`, Composable `OhneEintragSeite` in
  `ui/screens/typ/TypSeite.kt`, Inhalt in `OhneEintragAbschnitt.kt`), keine `TypSeite` mit
  Sonderkennung: die feste Zeile ist kein Eintrag in `definitions` — eine `TypSeite` mit Kennung
  ließe die `verschwunden`-Prüfung sofort feuern und brächte Entwurf, „Speichern" und den
  Verwerfen-`BackHandler` mit, die hier nichts zu tun haben.
- **Die Wahl schreibt beim Tippen** (`ShiftViewModel.waehleOhneEintrag`, genau ein Aufruf je Tipp —
  `OhneEintragAbschnittLogik.tippe`), Festlegung des Betreuers. Gelöscht wird ein Typ nur über
  `ShiftViewModel.loescheSchichttyp()` (Hergang zur Ein-Schreibvorgang-Regel: Kalender-Skill).
- In Etappe 2 hat die Seite weder Tagesleiste noch Wecker-Abschnitt. Der Kasten nimmt den Warnton des
  Neuanlage-Banners (hell Schrift #4D2E00, dunkel `tonPaar(WARNUNG)`), nicht `kategorieFarben(KRANK)`.
- **Textwahrheit:** „Eigene Einstellungen" sagt „Kein Wecker an diesen Tagen." (Prototyp: „Wecker,
  Dimmen usw. direkt hier einstellen" — die Oberfläche gibt es erst am Typ); „kein Wecker" steht in
  der Zeile und an Typen, die an leeren Tagen keinen stellen (`OhneEintragWahl.weckt()`); die
  Erklärung der festen Zeile sagt, dass bei einer ganz leeren Kalender-Vorausschau kein Wecker kommt
  (Leerlisten-Sperren #173/#175).
- Tests: `OhneEintragAbschnittTest` (Mutationsprobe, fünf Mutationen, jede rot: „öffnen" nimmt den
  ersten statt des gewählten Typs · `tippe` schreibt zweimal · Wahl bietet Ignorieren an · „Eigene
  Einstellungen" nie gehakt · kein Zweig in `rueckwegZiel`). Am Emulator belegt (17.10.2026): Tipp auf
  „Wie Frühschicht (F)" → Kasten + Knopf, Zurück von „öffnen" → Typ-Seite → Liste → Einstellungen,
  nie Übersicht; Wahl zurück auf „Eigene Einstellungen" → Liste zeigt „kein Wecker".

## Die Schichttypen-Liste (#170)

- **Nur in der Anzeige gruppiert** (`schichttypBloecke`): die Erkennung nimmt den ersten Treffer in
  Listenreihenfolge, eine alphabetisch oder nach Kategorie sortierte Speicherung hätte stumm
  geändert, welcher Wecker gilt. Der Code einer Zeile ist das erste nicht leere Muster
  (`listenCode()`), ohne Muster steht „–". Test: `SchichttypGruppenTest`.
- **Die Kurzform eines Typs mit `weckerAn = false` nennt kein Licht** (`alsTextOhneLicht`): Hue-Regeln
  laufen nur zu einem Alarm (`AlarmReceiver` → `executeRulesForAlarm`, `HueSmartScheduler` liest nur
  Alarme), SPEC E8. Dimmen und „Nicht stören" bleiben.
- **Papierkorb in der Namenszeile:** Marke links UND Papierkorb als eigene Spalte rechts ließen der
  Textspalte bei 320 dp 146 statt 208 dp, und die mit geschützten Leerzeichen gebauten Abschnitte
  der Statuszeile (`SchichtFolgen.alsText`, „Dimmen: allgemeine Regel") brachen mitten im Wort.
  Gemessen am uiautomator-Dump (gleiche Daten, Dichte 540: 145,8 → 199,7 dp). Aufgefallen ist es
  dem unabhängigen Prüfer durch Rechnen; belegt hat es erst der Dump.

## Einordnen: „Im Kalender gefunden, noch nicht eingeordnet" (#176)

- Ersetzt die frühere Vorschlagskarte und ihren Zuordnungs-Dialog. Einordnen läuft in
  `EinordnenDialog`, drei Schreibwege, alle über `updateShiftConfig()` (Datenseite:
  Kalender-Skill). Die Fund-Zeile nennt „N× im Kalender, zuerst am TT.MM." — bewusst kein „in den
  nächsten Wochen" (die Vorausschau ist eine Einstellung, die Liste kann ein Ausschnitt sein).
- **Website zieht mit:** `tools/doku/pruefe_hilfe.py` verlangt jeden „…"-Begriff der Website-Seiten in
  `app/src/main`; das Entfernen der alten Karte machte die Schleuse rot, die Website wurde in einem
  eigenen `docs(website)`-Commit nachgezogen. `app_texte()` liest auch KDoc — ein Verweis auf den
  alten Text im Kommentar hätte die Prüfung still bestanden.
- Die Kategorie-Marken (`kategorieFarben`, `CodeMarke`, `SonderfallMarke`, `KategorieMarke`) liegen
  seit #186 `internal` in `ui/theme/KategorieMarken.kt`, geteilt von Liste, Dialogen und Typ-Seite.
- Test: `EinordnenAblaufTest` (gehört zu, neuer Urlaub-Typ erst nach dem Speichern ohne Wecker,
  Ignorieren leert den Fund, Muster im Editor entfernt, doppeltes Speichern).

## Karte „Typ" (#188)

Name, Muster und Kategorie (`ui/screens/typ/TypAbschnitt.kt`), zustandslos: Entwurf und der Text im
„+"-Feld gehören `TypSeiteFormular`, die Rechnung steht in `TypAbschnittLogik` (reine Funktionen
`TypEntwurf -> TypEntwurf`). Vier Festlegungen, die man nicht neu erfinden soll:

1. **Der Text im „+"-Feld zählt beim Speichern mit** (`TypAbschnittLogik.mitEingabe` → `kandidat`, von
   „geändert", „speicherbar" und `speichere` gelesen): getippter, nicht mit „+" bestätigter Text ging
   sonst stumm verloren. Ein Duplikat oder ein leeres Feld ändert nichts.
2. **Ein Typ ohne Muster trägt den Platzhalter `listOf("")`** (`TypEntwurf.aus`); Chips zeigen nur
   Nicht-Leeres, „+" ersetzt den Platzhalter, × auf das letzte Muster stellt ihn wieder her — sonst ist
   „hinzugefügt und gleich wieder entfernt" für `hatAenderungen` eine Änderung, und die Seite fragt
   ohne Grund. × entfernt nach WORTLAUT (das erste gleiche), nicht nach Index: der Index der
   sichtbaren Chips stimmt mit dem der Liste nicht überein, solange der Platzhalter dort steht.
3. **Duplikat = `trim()` + `equals(ignoreCase = true)`**, derselbe Maßstab wie die Erkennung. Bei
   Ablehnung bleibt der Text stehen, „Dieses Muster gibt es schon." erklärt warum.
4. **Die Ein-Buchstaben-Warnung steht einmal unter der Chip-Reihe** (ihr Text hängt nur an Kategorie
   und Wecker-Schalter; `musterWarnung` prüft Chips UND das „+"-Feld).

**`KEIN_MUSTER` rechnet mit demselben Stand wie der Knopf** (`TypAbschnittLogik.fehltMuster` = Chips
MIT dem Text im „+"-Feld): der unabhängige Prüfer fand die Meldung zunächst am Entwurf OHNE Feld — sie
stand rot da, während „Speichern" aktiv war (Typ mit Muster F, ×, „FD" ins Feld). Der Muster-Hinweis
nennt die Beschriftung des Namensfelds wortgleich (`ShiftDefinitionTexte.musterHinweis(namensfeld)`).
Tests: `TypAbschnittTest` (Mutationsprobe 17.10.2026, jede Mutation rot: × schneidet ab dem Muster
ab · Duplikat unterscheidet Groß/klein · Platzhalter nicht wiederhergestellt · Kategoriewechsel
setzt Weckzeit zurück bzw. löscht Muster · `mitEingabe` verschluckt das Feld · `fehltMuster` nur am
Entwurf · `aktiviertHinweis` ignoriert Krank).

## Karte „Wecker" und Sonderfall-Kasten (#174, #189)

- **Reihenfolge der Seite:** Karte „Typ" → Kasten → Karte „Wecker" (Prototyp 328–357). Karte
  `ui/screens/typ/WeckerAbschnitt.kt` (Rechnung `WeckerAbschnittLogik`), Kasten `SonderfallKasten.kt`.
- **Was ein Kasten sagt, entscheidet `ShiftDefinitionTexte.sonderfall(kategorie, code, erkannt)`.** Bei
  Krank und Ignorieren gibt es keine Karte „Wecker" (`zeigtWeckerKarte` =
  `ShiftEditFormular.zeigtWeckerBlock`): beide ergeben nie einen Wecker (#142, #167), der Kasten trägt
  dort den Hinweis, was der Typ stattdessen tut. Die Werte bleiben im Typ stehen (E2), ein Wechsel
  zurück zu Dienst bringt sie wieder. Ein ausgeschalteter Typ (`erkannt = false`) hat KEINEN Kasten:
  „wird übergangen" widerspräche dem Schalter „Schichtdefinition aktiviert" einen Absatz darüber.
- **Bei „Wecker aus"** bleiben Weckzeit und „Stummer Wecker" sichtbar, ausgegraut und gesperrt; ein
  kaputtes Feld sperrt das Speichern dann nicht (`zeitZumSpeichern`: bisherige Zeit, sonst 06:00 —
  nie 00:00; `weckzeitFehler` ist nur bei Wecker an ein Fehler).
- **Weckzeit bleibt ein Textfeld, keine Zeitwahl:** `TypEntwurf.weckzeitText` ist bewusst Text („06:"
  ist ein gültiger Zwischenstand); Saver, `speicherbar` und `zeitZumSpeichern` rechnen darauf.
- **Die Ein-Buchstaben-Warnung richtet sich nach Kategorie UND Wecker-Schalter**
  (`einBuchstabeWarnung(muster, kategorie, weckerAn)`): nur ein Typ, der wirklich einen Wecker stellt,
  bekommt „weckt dich dann an freien Tagen", Krank behält seinen Text, Ignorieren und „Wecker aus"
  bekommen `EIN_BUCHSTABE_OHNE_WECKER`. Der Aktiviert-Hinweis hat bei Ignorieren einen eigenen Text
  (`IGNORIEREN_AKTIVIERT_HINWEIS`): der allgemeine verwies auf „Wecker an diesem Tag", den es dort
  nicht gibt. Beides fand der unabhängige Prüfer — daher die Regel, jeden Text unter einem Feld
  gegen ALLE Kategorien zu prüfen, in denen das Feld sichtbar ist.
- **Farben:** hell steht im Kasten die Schrift des Prototyps (`.besonders` #3B1F52, `.banner` #4D2E00),
  nicht das Etiketten-Paar (`tonPaar(WARNUNG)` hatte #8A5300: 5,4:1 statt 10,5:1 und sichtbar orange
  statt dunkelbraun); dunkel gilt das Paar.
- **Bekannte Dublette:** `TypAbschnitt.kt` zeigt unter „Kategorie" weiter `RUFBEREITSCHAFT_HINWEIS`,
  der Ruf-Kasten begründet die stündliche Prüfung noch einmal (#199).
- Tests: `WeckerAbschnittTest`, `ShiftDefinitionTexteTest` (Mutationsprobe 17.10.2026, neun
  Mutationen, jede rot), `ShiftEditFormularTest`.

## Karte „So läuft ein Tag" (#197, Gegenprüfung Etappe 2)

`ui/screens/typ/TagesleisteKarte.kt`, zustandslos; Rechnung in `TagesleisteKarteLogik.rechne`. Nur
Verdrahtung der Adapter aus `tagesleiste/` (#193–#196, Regeln in den Skills Kalender, Dimmer/DND,
Hue, Persistenz); es gibt dort keine zweite Fenster-, Licht- oder Dienstzeit-Logik.

1. **Gemischte Eingabe:** Weckzeit, „Wecker an", stumm und Kategorie kommen aus dem ENTWURF (die
   Leiste rechnet sofort mit), Dimm-Regeln, Hue-Regeln und Schichtspannen hängen am GESPEICHERTEN
   Namen (`gespeicherterName`) — mit dem Entwurfsnamen verschwänden Dimmen und Licht, sobald jemand
   ein Zeichen im Namen ändert.
2. **`tagesleisteQuellen == null` heißt „noch nicht gelesen"** und zeigt „Die Leiste wird gelesen …";
   ein Lesefehler einer Quelle macht NUR ihre Spur unbekannt („Gerade nicht lesbar: …"); sind die
   Spannen unlesbar, wird gar nicht gerechnet (kein erfundener Normaltag).
3. **Der Titel hat nie leere Anführungszeichen:** `anzeigeName` = gespeicherter Name, bei der
   Neuanlage der getippte, sonst „Neuer Typ" (am Emulator gefunden: `So läuft ein Tag „"`).
4. **„Ändere unten den Wecker – die Leiste rechnet sofort mit." steht nur, wo darunter die Karte
   „Wecker" steht** (`WeckerAbschnittLogik.zeigtWeckerKarte`).
5. **Der Link „Im Bereich „Wecker" über die ganze Woche ansehen" verlässt die Seite wie Zurück** und
   fragt bei ungespeicherten Änderungen mit demselben Gate (`rueckfrageZurWoche` wählt nur Text und
   Ziel); Ziel ist `navigateToMainWithTab(MainTab.WECKER)`, kein neuer `NavigationState`.
6. **Ein Hinweis der Leiste nennt bei einem noch nicht angelegten Typ den TYP als Ursache, nie den
   Kalender** (`TagesleisteAnzeige.typNochNichtAngelegt`, `TagesleisteTexte.leistenHinweis`): „Im
   Kalender steht noch kein Termin dieses Typs" stand bei der Neuanlage direkt unter dem Banner „…
   aus dem Kalender-Code N". Der Prüfer fand es; die Tests der Karte hatten es als Soll festgeschrieben.
7. **Die Leiste zeigt nur, was zur Laufzeit passiert** (Gegenprüfung Etappe 2, drei Prüfer
   unabhängig): ein ausgeschalteter Typ hat keinen Ablauf (vorher Weckpunkt, Dimmen und Licht für
   einen Typ, den `ShiftRecognitionEngine` nicht erkennt — und danach der Kalender als Ursache).
   Vierter Befund: `hellUm` und `tageslicht` hatten Vorgabewerte und wurden nie übergeben, die Leiste
   behauptete „Licht an" an Morgen, an denen die Regel entfällt. **Ein Vorgabewert „keine
   Einschränkung" an einem Adapter fällt keinem Test auf, solange kein Test den Aufrufer mit
   gesetztem Wert prüft.**

Die Blockposition (Chips bei Dienst und Rufbereitschaft) gehört der Seite wie das „+"-Feld und wird
nie gespeichert; `jetztMs` und Zone sind beim Öffnen eingefroren. **Farben:** `LeistenFarben` hell =
`FARBE` des Prototyps, dunkel aufgehellt (Test: Kontrast ≥ 3:1 zur Leiste) — eine Farbe je Spur, nicht
„Farbe heißt Zustand": die gilt für Etiketten, hier nennen Legende und Ereignisliste jede Spur beim
Namen. **Achse und Legende** sind `Layout` bzw. `FlowRow`: bei 320 dp brechen sie um, statt sich zu
überdecken. Der Umbruch-Fallback der Achse sprang bei 320 dp an, obwohl alle Beschriftungen Platz
hatten (engste Lücke 13,25 px < 4 dp = 13,5 px bei Dichte 540), und die Zeitangabe stand bis zu 64 px
neben ihrer Linie — Mindestlücke jetzt 2 dp. Der Bewerter fand es durch Pixelmessung im PNG; ein
Urteil nach Augenmaß hätte es übersehen. Tests: `TagesleisteKarteTest` (Mutationsprobe 05.11.2026,
zwölf Mutationen in einer eigenen Worktree-Kopie, jede rot).

## Textwahrheit statt Prototyp-Wortlaut — und die vorgemerkten Satztausche

Gegen den Code geprüft, Festlegungen des Betreuers in den Issues. **Die mit „Etappe" markierten Sätze
tauscht, wer die Etappe baut** — sonst lügt der Text in die andere Richtung.

- **„Stummer Wecker"** sagt „Eine Hue-Lichtregel startet dann nicht" (Prototyp und SPEC 3.3
  versprechen einen Lichtwecker; heute überspringen `AlarmReceiver` und `HueSmartScheduler` bei
  stiller Schicht jede Hue-Regel). **Etappe 4 (E8) tauscht den Satz**, wenn Hue stillen Weckern folgt.
- **Kasten „Ohne Kalendereintrag"** sagt „Der Wecker richtet sich dann nach dem Typ …" (Prototyp:
  Wecker, Dimmen, „Nicht stören" und Licht). **Etappe 3/4 tauscht den Satz.**
- **Einordnen-Hinweis** nennt nur „Wecker und Weckzeit". **Etappe 3/4 tauscht den Satz.**
- **Kein Kasten verspricht Licht** (heute überspringt der `AlarmReceiver` bei stummem Wecker jede
  Hue-Regel). **Etappe 4 tauscht den Satz.**
- **Urlaub-Beschreibung ohne „und Dimmer":** der Dimmer hängt bis Etappe 3 an Regeln, „Wecker aus"
  nimmt ihn nicht weg (eine ALL-Regel gilt auch für Urlaub).
- **Ruf-Kasten:** übernimmt ein nachgetragener Dienst den Tag, entfällt nur der WECKER des Rufs (#172);
  seine Spanne bleibt. Der Prototyp sagt „dessen Wecker und Einstellungen" — Dimmer und DND des
  Ruf-Typs wirken heute weiter, deshalb nur „die Wecker-Einstellung dieses Dienstes".
- **Krank-Kasten:** „der Dienst-Wecker entfällt" ja, „es gilt das hier Eingestellte" nein (Krank hat
  bis Etappe 6 keinen Wecker-Block). **Ignorieren-Kasten:** „sonst nichts im Kalender → gilt ‚Ohne
  Kalendereintrag'" und „schlägt X nicht mehr vor" stimmen; X ist das erste SICHTBARE Muster.
- **„Wecker an diesem Tag"** sagt „Stellt … einen Wecker", nicht „Klingelt" — ob er klingelt, regelt
  der Stumm-Schalter. Der Hinweis unter „Schichtdefinition aktiviert" nennt für „kein Wecker,
  Zeitfenster trotzdem" diesen Schalter (seit #168 bleibt die Spanne), nicht mehr „Stille Schicht".
- **Reset-Rückfrage und Stumm-Symbol** heißen „Stummer Wecker" und nennen Kategorien, „Wecker an
  diesem Tag" und die Wahl bei „Ohne Kalendereintrag" (`resetToDefaultsPreservingAutoAlarm` setzt
  sie zurück). Tests: `SchichttypTexteTest`.
- Der Bewerter zieht für jeden solchen Wortlaut einen Punkt ab, solange er die Festlegungen nicht
  kennt (Skill `cfalarm-ui-pruefen`).

## Layout-Funde

- **Auch ein leerer Platzhalter-`Scaffold { }` muss seinen `padding`-Parameter benutzen**
  (`Box(Modifier.padding(paddingValues))`): Lint `UnusedMaterial3ScaffoldPaddingParameter` ist ein
  Fehler, die Schleuse blockiert (`lintDebug`).
- **Eine Seite mit Textfeldern in einer `LazyColumn` braucht keinen eigenen `imePadding()`**: das
  Fenster schrumpft, der Speichern-Knopf bleibt über der Tastatur (Emulator 17.10.2026, geprüft mit
  `input text` im dritten Musterfeld).
