# Changelog

Alle nennenswerten Änderungen an CF Alarm for Time Office — in der Sprache der
Nutzerinnen und Nutzer, nicht in Entwickler-Prosa. Format angelehnt an
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/).

**Diese Datei ist die Quelle für die öffentliche Changelog-Seite**
(`docs/changelog.html`). Die Seite wird daraus erzeugt:

```
python tools/changelog/build_changelog.py            # Seite neu schreiben
python tools/changelog/build_changelog.py --pruefen  # nur prüfen, ob sie passt
```

Das HTML zwischen `<!-- CHANGELOG:BEGIN -->` und `<!-- CHANGELOG:END -->` wird
dabei vollständig ersetzt — **niemals dort von Hand editieren**, die Änderung
wäre beim nächsten Lauf weg. Alles außerhalb der Marker bleibt unangetastet.

Schreibkonventionen (der Generator verlässt sich darauf):

- `## Version 1.2.3 (Zusatz)` — ein Klammerzusatz am Ende der Überschrift wird
  zu `<small>(Zusatz)</small>`.
- `**Stand:** August 2026` — die Datumszeile.
- `_Ein Satz._` — die kursive Zusammenfassung unter der Überschrift. Der
  Unterstrich klammert den ganzen Absatz, damit ein *kursives* Wort darin
  eindeutig bleibt.
- `### 🐛 Behoben` — Rubrik, mit Emoji wie bisher.
- `- **Kurzfassung:** Erklärung` — ein Eintrag.

## 🆕 Version 1.37.2 (Aktuell – interne Alpha)

**Stand:** August 2026

_Wenn der Bildschirm kurz hell wird und von allein wieder dunkel, steht jetzt im Protokoll, warum._

### 🔧 Unter der Haube

- **Unterbrechungen des Dimmers werden protokolliert.** Wird die App aktualisiert oder ihr Prozess vom System beendet, verschwindet die Verdunkelung für ein paar Sekunden und kommt dann von selbst zurück — sichtbar als kurz heller Bildschirm. Bisher war davon im Protokoll der App nichts zu finden, weil in diesem Moment gar kein App-Code mehr läuft. Jetzt erkennt der Dimmer beim nächsten Start, dass er unsauber beendet wurde, und hält das fest. Ein Geräteneustart zählt dabei ausdrücklich nicht als Störung.

## Version 1.37.1

**Stand:** August 2026

_Wenn der Dimmer nicht dimmen kann, sagt er es jetzt — statt eine Verdunkelung zu melden, die es nicht gibt._

### 🐛 Behoben

- **Der Dimmer meldet jetzt, wenn er gar nicht dimmen kann.** Die Verdunkelung braucht den Bedienungshilfen-Dienst der App. War der ausgeschaltet, lief trotzdem alles Weitere normal — „Nicht stören" schaltete zur gewohnten Zeit, und die Dimmer-Benachrichtigung zeigte einen Verdunkelungswert an, obwohl der Bildschirm hell blieb. Jetzt steht dort in dem Fall „Dimmt nicht — Bedienungshilfen-Dienst ist aus", und ein Tipp darauf führt in die App, wo sich der Dienst einschalten lässt.

  Der Dienst schaltet sich bei jeder Neuinstallation der App von selbst ab. Wer den Schicht-Dimmer nutzt, sollte ihn danach im Status-Tab wieder aktivieren.

### 🎨 Feinschliff

- **Angeschnittene Buchstaben in den Status-Karten.** Brauchte ein Verweis-Link unter einer Statuskarte zwei Zeilen — etwa „Bedienungshilfen-Dienst aktivieren" bei großer Schrift —, fehlte am linken Rand jeweils ein Stück vom ersten Buchstaben. Der Text steht jetzt vollständig da.

## Version 1.37.0

**Stand:** August 2026

_Wenn dein Gerät den Weck-Bildschirm wegdrängt, sagt die App dir das jetzt — statt dich rätseln zu lassen._

### 🆕 Neu

- **Hinweis, wenn der Weck-Bildschirm verdrängt wird.** Auf manchen Geräten erscheint der Weck-Bildschirm beim Klingeln nur kurz und verschwindet wieder — der Wecker läuft weiter, lässt sich aber erst nach dem Entsperren stoppen oder schlummern. Passiert das zweimal hintereinander, steht im Status-Tab jetzt eine Karte, die erklärt, was los ist und was hilft. Sie verschwindet von allein, sobald wieder ein Wecker normal durchläuft.

  Ursache ist auf dem Fairphone 6 die Gesichtsentsperrung des Geräts: sie legt sich als eigenes Fenster über den Weck-Bildschirm. Das betrifft **jede** Wecker-App auf diesem Gerät — mit der vorinstallierten Uhr nachgestellt — und lässt sich aus einer App heraus nicht beheben. Wer davon betroffen ist, kann das eingelernte Gesicht löschen; der Fingerabdruck ist nicht betroffen.

## Version 1.36.2

**Stand:** August 2026

_Die Karte im Regel-Editor heißt jetzt so wie das, was du ausgewählt hast._

### 💅 Verbessert

- **Überschrift folgt der Auswahl.** Tippst du „Manuell", heißt die Karte darunter „Manuell" – vorher stand dort „Zielauswahl", also gar nicht die gewählte Betriebsart. Bei „Sonnenaufgang" genauso. „Szene" hieß schon immer „Szene"; jetzt sind alle drei gleich. Die Beschriftung kommt aus derselben Quelle wie der Umschalter und das Abzeichen in der Regelliste, kann also nicht auseinanderlaufen.

## Version 1.36.1

**Stand:** August 2026

_Der Regel-Editor sieht in allen drei Betriebsarten gleich aus._

### 💅 Verbessert

- **Eine Karte je Betriebsart.** Bisher brachte „Szene" eine einzige Karte mit, die Raum und Licht enthielt – bei „Manuell" und „Sonnenaufgang" schob sich die Lampenauswahl dagegen als eigene Karte zwischen den Umschalter und die eigentliche Einstellung. Derselbe Gedanke sah damit in drei Modi verschieden aus. Jetzt steht unter dem Umschalter überall genau eine Karte: erst wohin, eine Trennlinie, dann wie.

## Version 1.36.0

**Stand:** August 2026

_Eine Regel darf jetzt mehrere Räume mit je einer Szene schalten._

### ✨ Neu

- **Mehrere Szenen in einer Regel.** Du kannst das Schlafzimmer auf „Nachtlicht" und gleichzeitig das Wohnzimmer auf „Konzentrieren" setzen – in derselben Regel. Pro Raum bleibt es bei einer Szene: zwei Szenen auf demselben Raum würden sich gegenseitig überschreiben. Die Auswahl zeigt oben als Chips, was du bereits gewählt hast, und im Raum-Menü steht ein Haken bei jedem Raum, in dem schon etwas gesetzt ist.
- **Hinweis bei überlappenden Bereichen.** Teilen sich zwei gewählte Räume oder Zonen eine Lampe, sagt die App das – für diese Lampe gilt dann die zuletzt gesetzte Szene.

### 🐛 Behoben

- **„Wohnzimmer· Auto-Aus"** hat wieder ein Leerzeichen, und bei mehreren Szenen steht der Auto-Aus-Hinweis auf einer eigenen Zeile statt an der letzten Szene zu kleben.
- **„Ausgeschaltet wird der ganze Raum «A», «B»"** heißt bei mehreren Räumen jetzt „werden die ganzen Räume".

## Version 1.35.2

**Stand:** August 2026

_Zwei schiefe Sätze in der Regel-Vorschau geradegerückt._

### 🐛 Behoben

- **„Sunrise an 3 Lichter" heißt jetzt wieder „an 3 Lichtern".** Beim internen Umbau der Texte war in der Regel-Vorschau zweimal die falsche Beugung gelandet. Betrifft nur die Anzeige – die Regeln selbst haben immer richtig geschaltet.

## Version 1.35.1

**Stand:** August 2026

_Ein von Hand angelegter Wecker schaltet jetzt auch dein Licht._

### 🐛 Behoben

- **Manueller Wecker ließ das Licht aus.** Wer einen Wecker über „Manueller Alarm" anlegte, bekam seine Hue-Regel für dieselbe Schicht nicht ausgeführt – der Wecker klingelte, das Licht blieb dunkel, und nichts wies darauf hin. Ursache war der Zusatz „(Manuell)" im Schichtnamen, an dem die Zuordnung scheiterte.
- **Aktualisieren meldet jetzt, wenn es nicht klappt.** Das Symbol in der Lampen- und Szenenauswahl tat bei nicht erreichbarer Bridge sichtbar nichts. Jetzt sagt es Bescheid. Deine bereits geladene Liste bleibt dabei wie bisher stehen.
- **„Ausschalten nach: 1 Minuten"** heißt jetzt „1 Minute".

## Version 1.35.0

**Stand:** August 2026

_Deine Hue-Szenen aus der Hue-App lassen sich jetzt direkt als Weckbeleuchtung auswählen._

### ✨ Neu

- **Szenen statt Farbe von Hand einstellen.** Eine Hue-Regel kann jetzt eine Szene aus deiner Hue-App verwenden – „Nachtlicht", „Entspannen", was immer du dort angelegt hast. Helligkeit und Farbe je Lampe kommen dann aus der Szene selbst; du musst sie nicht mehr näherungsweise nachbauen.
- **Erst der Raum, dann die Szene.** Weil Szenennamen sich pro Raum wiederholen (auf einer echten Bridge gibt es „Nachtlicht" schnell neun Mal), wählst du zuerst den Raum und dann die Szene darin. Damit findet die App deine Auswahl auch nach einem Gerätewechsel wieder.
- **Ein klarer Umschalter im Regel-Editor.** Szene, Manuell oder Sonnenaufgang – drei Wege, die sich gegenseitig ausschließen, mit je einem Satz Erklärung. Der Sonnenaufgang hat dafür seinen eigenen Schalter abgegeben; er hat ohnehin immer die ganze Regel umgestellt.
- **Die Regelliste zeigt, was eine Regel tut.** Jede Regel trägt jetzt ein Abzeichen mit ihrer Betriebsart und darunter eine Zeile im Klartext – etwa „Szene «Nachtlicht» · Badezimmer". Vorher stand dort eine Zahl, die immer 1 war.

### ⚠️ Gut zu wissen

- **Das automatische Ausschalten trifft bei einer Szene den ganzen Raum.** Zu einer Szene gibt es keinen Gegenbefehl – die einzige ehrliche Rücknahme ist „Raum aus". Der Regel-Editor sagt das an Ort und Stelle dazu.
- **Szenen ohne Raum oder Zone werden nicht angeboten.** Ordne sie in der Hue-App einem Raum zu, dann erscheinen sie. Auch das steht direkt in der Auswahl.

## Version 1.34.4

**Stand:** August 2026

_Nur Aufräumen — für dich ändert sich nichts._

### 🧰 Intern

- **Drei ungenutzte Importe entfernt.** Reste der vorangegangenen Aufräumrunden. Kein sichtbarer Effekt; der Eintrag steht hier nur, weil jede ausgelieferte Version einen bekommt.

## Version 1.34.3

**Stand:** August 2026

_Die Regelliste sagt jetzt, wenn der Dimmer-Hauptschalter aus ist — und die Korrektur-Benachrichtigung zeigt auch die Wärme._

### 🐛 Behoben

- **Regeln anlegen, die nichts tun:** Ist der Dimmer-Hauptschalter aus, wirkt keine Regel — auch keine neu angelegte. Die Regelliste verriet das nirgends, sodass man dort (von Hand oder per Schnellstart) eine Regel bauen konnte, die garantiert wirkungslos blieb. Jetzt steht ganz oben ein deutlicher Hinweis mit einem Knopf, der das Dimmen gleich dort einschaltet.
- **„Nicht stören" folgte einer Dimm-Änderung manchmal nicht sofort:** Der Nachzug beider Zeitpläne stand an fünf Stellen einzeln im Code. Er liegt jetzt an einer, damit er nicht an der einen greift und an der anderen vergessen wird. Für dich ändert sich nichts — außer dass es zuverlässig bleibt.

### ✨ Verbessert

- **Korrektur-Benachrichtigung zeigt die Wärme:** In der Benachrichtigung mit „Heller / Dunkler / Pause" stand bisher nur die Verdunkelung. Jetzt steht die Wärme daneben — du stellst sie pro Regel ein, konntest aber nirgends ablesen, welcher Wert gerade wirkt.

### 🧰 Intern

- **Toter Code aus der Werkzeug-Prüfung abgearbeitet:** 71 ungenutzte Konstanten, ein nie gelesenes Hue-Feld, zwei verwaiste Fehlertypen und drei ungenutzte Parameter entfernt. Wo Löschen die falsche Antwort war, wurde stattdessen benutzt: die Fehlermeldung der Hue-Bridge landet jetzt im Protokoll, statt verworfen zu werden.

## Version 1.34.2

**Stand:** August 2026

_Aufräumen hinter dem Dimmer-Umbau — für dich ändert sich nichts._

### 🧰 Intern

- **Reste des alten Dimmer-Aufbaus entfernt:** Mit dem Umstieg auf ein einziges Regel-Modell (1.34.0) hat der Dimmer-Reiter seine drei Karten verloren. Der Code dahinter — globale Verdunkelungs- und Wärme-Regler, die 5-Sekunden-Vorschau des Reiters, die Schichtnamen-Liste — blieb stehen, ohne dass ihn noch etwas aufrief. Er ist jetzt weg. **Sichtbar ändert sich dadurch nichts:** die Vorschau gibt es weiterhin im Regel-Editor, und Verdunkelung und Wärme stellst du dort pro Regel ein. Die abgesicherten Verhaltensweisen wurden nicht mitentsorgt, sondern auf die Regel-Vorschau übertragen.

## Version 1.34.1

**Stand:** August 2026

_Die Status-Karte konnte „Dimm-Dienst läuft" anzeigen, obwohl er es nicht tat._

### 🐛 Behoben

- **„Dimm-Dienst läuft" stimmte nicht immer:** Android kann den Bedienungshilfen-Dienst, der die Verdunkelung zeichnet, vorübergehend abschalten — etwa während eine andere App die Bedienungshilfen belegt. Die App merkte das bisher nur, wenn der Dienst ganz beendet wurde: in der Status-Karte stand weiter ein grünes „läuft", während gar nichts mehr gedimmt wurde. Jetzt wird auch das bloße Abmelden erkannt, und die Karte sagt die Wahrheit.
- **Nachvollziehbar, warum gerade nicht gedimmt wird:** Die App schreibt jetzt in ihr Protokoll, wann die Verdunkelung verschwindet und warum — pausiert, ausgeschaltet, gerade kein Zeitfenster, oder der Dienst wurde abgemeldet. Für dich ändert sich dadurch nichts; es hilft, wenn du eine Beobachtung meldest und wir hinterher wissen wollen, was wirklich passiert ist.

## Version 1.34.0

**Stand:** August 2026

_Der Dimmer hat nur noch einen Schalter: alles Dimmen läuft jetzt über Regeln, die du sehen und ändern kannst._

### ✨ Neu

- **Ein Schalter statt drei:** Der Dimmer-Reiter hatte drei gleichrangige Karten mit eigenen Schaltern — „Wellness/Wind-down", „Nacht-Standard" und „Schicht-Regeln". Sie hingen unsichtbar voneinander ab: der Nacht-Standard wirkte nur an Tagen ohne Regel und nur, solange „Regeln" eingeschaltet war. Wer einen Schalter umlegte, änderte damit stillschweigend die Bedeutung der anderen — und ob eine Einstellung gerade überhaupt etwas tat, war nirgends abzulesen. Jetzt gibt es **einen** Schalter „Dimmen an/aus". Wann, wie dunkel und wie warm gedimmt wird, steht ausschließlich in deinen Regeln — und die stehen sichtbar in der Liste, mit Vorschau, änderbar und löschbar.
- **Drei Vorlagen für den schnellen Anfang:** Oben in „Regeln verwalten" legst du mit einem Tipp eine fertige Regel an, die sich danach wie jede andere bearbeiten lässt. **„Nacht-Dimmen"** ist die frühere Nachtruhe als eine Regel: ab 22:00 bis zu deiner Weckzeit, spätestens 7:00 — an jedem Tag das Passende, auch vor einem Spätdienst. **„Nachtdienst-Rhythmus"** dimmt den Tagschlaf nach dem Dienst statt der Nacht. **„Schicht ausnehmen"** nimmt eine Schicht ganz vom Dimmen aus (das ersetzt die früheren Ausnahme-Chips am Nacht-Standard). Liegt für dieselbe Schicht schon eine Regel vor, sagt die App das und bietet an, sie zu öffnen, statt eine zweite anzulegen, die nie wirken würde.
- **Die Weckzeit als Fenster-Anfang:** Im Regel-Editor kannst du ein Fenster jetzt auch **vor** deiner Weckzeit beginnen lassen — „ab 60 Minuten vor dem Aufstehen bis zur Weckzeit" ist damit eine gewöhnliche Regel. Das ist der Ersatz für die frühere Einschlafhilfe „Wellness".

### 🔄 Geändert

- **Deine bisherigen Dimm-Einstellungen werden beim Update automatisch übernommen** — du musst nichts neu einrichten. Was du eingestellt hattest, wird in Regeln übersetzt: aus dem Nacht-Standard wird die Regel „Nachtruhe (uebernommen)", aus Wellness ein Fenster vor der Weckzeit, aus jeder Ausnahme-Schicht eine Regel ohne Fenster. Übernommen wird dabei nur, was vorher auch wirklich gewirkt hat — Einstellungen, die durch die alten Abhängigkeiten still wirkungslos waren, werden nicht nachträglich scharf geschaltet. **Schau nach dem Update trotzdem einmal in „Regeln verwalten"**: dort steht ab jetzt schwarz auf weiß, was der Dimmer tut. Sollte etwas fehlen, legst du es mit einer der drei Vorlagen in wenigen Sekunden neu an. Die Übernahme läuft auch dann, wenn du die App nach dem Update gar nicht öffnest — und ebenso, wenn du eine mit der Vorversion gesicherte Konfiguration einspielst.
- **Drei Dinge verhalten sich jetzt bewusst anders** (alle in die hellere Richtung): Eine späte Weckzeit verlängert die Nacht nicht mehr bis mittags. Ein selbst gestellter Wecker ohne Dienst bekommt keine eigene Einschlafhilfe mehr — er kann ein laufendes Fenster nur noch beenden. Und an einer Schicht, die du vom Dimmen ausgenommen hast, entfällt auch die Einschlafhilfe davor; beides zugleich lässt sich in einer Regel nicht ausdrücken.
- **Die globalen Regler für Verdunkelung und Wärme sind entfallen.** Sie färbten nur die abgelösten Quellen; jede Regel bringt ihre eigenen Werte mit, und die Probe-Verdunkelung sitzt jetzt dort, wo sie etwas aussagt — im Regel-Editor.

## Version 1.33.0

**Stand:** August 2026

_Neues Fenster-Ende „Weckzeit, spätestens" — dimmt bis zu deinem Wecker, aber nie über die eingestellte Uhrzeit hinaus._

### ✨ Neu

- **Fenster-Ende „Weckzeit, spätestens":** Im Regel-Editor kannst du ein Dimm-Fenster jetzt so enden lassen: *bis zu deiner Weckzeit — und falls in diesem Fenster gar kein Wecker liegt, zur eingestellten Uhrzeit*. Bisher gab es nur entweder/oder: „Feste Uhrzeit" dimmte stur bis zur Uhrzeit und damit über einen früheren Wecker hinweg, „Zur Weckzeit" dimmte bis zum Wecker — egal wie spät der ist. Vor einem Spätdienst mit Weckzeit 12:30 hieß „Nacht-Dimmen" damit faktisch „bis mittags". Mit dem neuen Ende stellst du „22:00 bis Weckzeit, spätestens 7:00" als **ein** Fenster ein, und es tut an jedem Tag das Erwartete: Frühdienst → Ende um 5:30, Spätdienst → Ende um 7:00, freier Tag → Ende um 7:00. Ein selbst gestellter Wecker ohne Dienst beendet das Fenster ebenfalls.

## Version 1.32.1

**Stand:** August 2026

_„Nicht stören" blieb nach einer Änderung an den Dimm-Einstellungen stundenlang hängen._

### 🐛 Behoben

- **„Nicht stören" folgte einer geänderten Dimm-Einstellung erst Stunden später:** Wenn „Nicht stören" auf „folgt dem Dimmer" steht, holt es sich seine Zeiten aus dem Dimm-Zeitplan. Änderte man diesen Zeitplan — Nacht-Standard oder Regeln an- und ausschalten, Zeiten verstellen, eine Regel speichern oder löschen —, rechnete der Dimmer sofort neu, „Nicht stören" aber nicht: es blieb auf dem alten Plan stehen, bis es das nächste Mal von selbst nachsah. Real gemessen waren das knapp drei Stunden, in denen der Bildschirm längst wieder hell war und das Telefon trotzdem stumm blieb. Jetzt zieht „Nicht stören" jede solche Änderung sofort mit nach. Reines Verstellen von Verdunkelung oder Wärme lässt es weiterhin unberührt — das ändert nur die Farbe, nicht die Zeiten.

## Version 1.32.0

**Stand:** August 2026

_Neu: „Tag freigeben" für Tage, an denen der Dienst ausfällt — und eine Erklärung, wann du das statt „Überspringen" nimmst._

### ✨ Neu

- **„Tag freigeben":** Wenn dein Chef dir einen Tag freigibt, du tauschst oder krank bist, kannst du diesen Kalendertag jetzt als dienstfrei markieren. Für den Tag wird kein Wecker gestellt, „Nicht stören" bleibt während der eigentlichen Dienstzeit aus, und der Abend verhält sich wie an jedem anderen freien Tag. Der Termin in deinem Dienstplan-Kalender bleibt dabei unangetastet. Der Knopf sitzt im Wecker-Tab direkt neben „Überspringen" und betrifft den Tag des nächsten Weckers; über das Kalender-Symbol daneben gibst du jeden anderen Tag frei. Freigegebene Tage stehen darunter und lassen sich jederzeit wieder aufheben — dann legt die App den Wecker aus dem Dienstplan neu an.
- **Erklärung „Wann was benutzen?":** Direkt bei beiden Knöpfen steht jetzt, worin sie sich unterscheiden. Kurz: **Überspringen** lässt genau einen Weckruf aus und lässt den Dienst bestehen — „Nicht stören" und das Dimmen richten sich weiter nach der Schicht. **Tag freigeben** streicht den Dienst selbst.

### 🐛 Behoben

- **„Nicht stören" ging an einem freien Tag trotzdem an:** Wer bisher an einem dienstfreien Tag den Wecker übersprang, bekam pünktlich zum Schichtbeginn trotzdem „Nicht stören" — denn ein übersprungener Wecker ändert nichts daran, dass laut Kalender Dienst wäre. Das war so gewollt (es hilft an dem Morgen, an dem du ohne Wecker wach bist), aber für den umgekehrten Fall fehlte schlicht die passende Geste. Die gibt es jetzt, und die Oberfläche sagt auch, welche wofür da ist.

## Version 1.31.0

**Stand:** August 2026

_Der Status-Tab sagt jetzt in einer ruhigen Zeile, wann der Dienstplan-Kalender zuletzt neu eingelesen wurde._

### ✨ Neu

- **„Dienstplan-Kalender zuletzt neu eingelesen":** Google liest einen abonnierten Kalender alle paar Tage neu ein und vergibt dabei intern neue Kennungen. Seit 1.30.1 erkennt die App ihre Wecker dabei zuverlässig wieder — und weil das völlig geräuschlos abläuft, war für dich nicht mehr nachvollziehbar, ob und wann es passiert ist. Der Status-Tab zeigt es jetzt: Datum, Anzahl der wiedererkannten Wecker und der ausdrückliche Hinweis, dass sich am Dienstplan dadurch nichts geändert hat. Ohne Benachrichtigung, ohne Warnfarbe, ohne Knopf — die Zeile erscheint nur, wenn es überhaupt schon einmal vorkam, und nur für Fälle, in denen wirklich nichts anderes passiert ist.

## Version 1.30.3

**Stand:** August 2026

_Wer einen Schichttyp umbenennt, verliert damit nicht mehr stillschweigend seine „Nicht stören"- und Dimmer-Einstellungen._

### 🐛 Behoben

- **Umbenennen legte die Rufbereitschaft-Einstellung lahm:** Seit 1.30.0 ziehen Dimmer- und Hue-Regeln beim Umbenennen eines Schichttyps mit. Drei weitere Einstellungen tun das ebenfalls über den Namen, wurden dabei aber übersehen: die Auswahl unter „Rufbereitschaft", die Ausnahmen von „Nicht stören während der Dienstzeit" und die Ausnahmen vom Nacht-Standard des Dimmers. Nach einer Umbenennung zeigte die gespeicherte Auswahl ins Leere — „Nicht stören" endete dann in der Nacht vor der Rufbereitschaft **nicht** mehr zur eingestellten Zeit, und man war früh morgens nicht erreichbar. Zu sehen war davon nichts, weil die Auswahlfelder immer die aktuellen Namen zeigen. Alle drei ziehen jetzt mit.
- **Auch eine geänderte Groß-/Kleinschreibung zählt:** Wer „abrufdienst" zu „Abrufdienst" korrigierte, löste denselben Schaden aus — für die Regeln war die Änderung folgenlos, für diese drei Einstellungen nicht.
- **Beim Tausch zweier Namen bleibt nichts falsch stehen:** Tauschen zwei Schichttypen ihre Namen, gehörte die gespeicherte Auswahl danach der jeweils anderen Schicht — die Einstellung wirkte also am falschen Tag. Solche Einträge werden jetzt entfernt und benannt; sind beide Namen ausgewählt, bleibt alles unangetastet, weil die Auswahl dann weiterhin stimmt.
- **Die Zeitpläne werden zum richtigen Zeitpunkt neu gestellt:** Dimmer und „Nicht stören" wurden nach einer Umbenennung neu berechnet, bevor die Schichtzeiten den neuen Namen trugen — für die Nacht direkt danach konnte dabei ein Plan ohne die eigene Ausnahme stehenbleiben.

## Version 1.30.2

**Stand:** August 2026

_Der Erklärtext auf dem Schichttypen-Bildschirm nimmt den Schichten nicht mehr den Platz weg._

### 🎨 Feinschliff

- **Hinweis füllte die halbe Seite und ließ sich nicht wegscrollen:** Auf dem Bildschirm „Schichttypen" stand der Erklärtext zur Erkennung fest verankert über der Liste — je nach Schriftgröße blieb darunter nur noch gut eine Schichtkarte sichtbar, und wegscrollen ließ er sich nicht. Der Text steht jetzt in der Liste und scrollt mit; sichtbar bleibt der entscheidende erste Satz, die Beispiele und Stationskürzel liegen hinter „Beispiele und Stationskürzel". Auf einem Testgerät passen damit alle sechs Schichttypen plus der Zurücksetzen-Knopf auf einen Bildschirm statt zwei.

## Version 1.30.1

**Stand:** August 2026

_Schluss mit „Neue Schicht erkannt" für Dienste, die sich gar nicht geändert haben._

### 🐛 Behoben

- **Immer wieder dieselbe Meldung über denselben Dienst:** Google liest einen abonnierten Dienstplan-Kalender alle paar Tage neu ein und vergibt dabei allen Terminen intern neue Kennungen. Die App erkannte ihre Wecker nur an genau dieser Kennung — für sie war dann schlagartig der ganze Dienstplan neu: sie meldete Änderungen, die es nicht gab, und stellte dabei jeden einzelnen Wecker ab und neu. Ein Wecker gilt jetzt als derselbe, wenn er dieselbe Schicht zur selben Weckzeit meint; wechselt nur die Kennung, passiert im Hintergrund nichts weiter. Echte Änderungen — andere Weckzeit, andere Schicht, gestrichener Dienst — werden weiterhin gemeldet.
- **Ein Neustart zur falschen Zeit konnte alle Wecker löschen:** Auch die Wiederherstellung nach einem Geräteneustart erkannte Wecker nur an der Kalender-Kennung. Fiel der Neustart in das Zeitfenster nach einem solchen Neueinlesen, hielt sie sämtliche Wecker für gelöschte Termine und räumte sie ab.
- **Übersprungener Wecker klingelte trotzdem:** Wurde der Dienstplan zwischen „Nächsten Wecker überspringen" und dem nächsten Abgleich neu eingelesen, verlor die App den Bezug zum übersprungenen Wecker und stellte ihn wieder — geweckt am freien Morgen. Erkannt wird er jetzt an der Weckzeit.
- **Täglich eine Meldung für den neuen Randtag:** Die App schaut 14 Tage voraus, und dieses Fenster wandert täglich weiter. Die Schicht am neu hinzugekommenen Tag galt jedes Mal als „neue Schicht". Ein Termin, der nur ins Blickfeld rutscht, meldet jetzt nichts mehr.

### 🔧 Unter der Haube

- **1076 automatische Tests** (vorher 1045). Gefunden wurde das alles nicht durch eine Prüfrunde, sondern durch die Rückmeldung aus dem täglichen Gebrauch — und belegt am Gerät anhand von neun Tagen App-Protokoll.

## Version 1.30.0

**Stand:** August 2026

_Die App sagt jetzt, wenn sie gerade nicht weckt — und das Abmelden nimmt seine Wecker mit._

### 🐛 Behoben

- **Abmelden ließ die Wecker weiterlaufen:** Wer sich abmeldete, wurde die gestellten Wecker nicht los — sie klingelten bis zu zwei Wochen weiter, auch nach einem Neustart, und die App zeigte danach nur noch den Anmeldebildschirm: keine Weckerliste, kein Pausenschalter, also kein Weg, sie abzustellen. Das Abmelden fragt jetzt vorher nach und entfernt die gestellten Wecker mit.
- **Angekündigte Weckzeit, die nie klingelt:** In der Karte „Nächster Alarm" konnte eine stille Schicht stehen — also eine, bei der die App bewusst nicht klingelt. Sie sah aus wie ein gewöhnlicher Wecker und verdeckte zusätzlich den nächsten, der wirklich klingelt. Stille Schichten sind jetzt als solche erkennbar, und der nächste hörbare Wecker steht daneben.
- **Pausiert, ohne es zu zeigen:** Waren die Hintergrunddienste pausiert, sagte das nur ein Schalter ganz unten in den Einstellungen. Der Wecker-Tab behauptete weiter „Automatische Alarme: an", der Home-Tab nannte die nächste Schicht, und beim Alarm-Status stand ein grundloses „Keine aktiven Alarme". Wer nach dem Urlaub kurz nachsah, hielt alles für in Ordnung. Der Zustand steht jetzt im Status-Tab mit einem Knopf zum Fortsetzen, und die anderen Bildschirme nennen ihn als Grund.
- **Den letzten Kalender abzuwählen ließ seine Wecker stehen:** Wurde der einzige ausgewählte Kalender abgewählt, verschwanden zwar Termine und Anzeige, die Wecker daraus blieben aber gestellt — und kein Hintergrundlauf hat sie je aufgeräumt. Sie werden jetzt entfernt, und der Auftrag dazu überlebt es sogar, wenn die App direkt danach geschlossen wird.
- **Umbenannter Schichttyp legte Dimmer und Licht still:** Wer einen Schichttyp umbenannte, verlor dafür lautlos seine Dimm- und Hue-Regeln: sie zeigten weiter auf den alten Namen, standen in der Liste aber unverändert als aktiv. Beim Umbenennen ziehen die Regeln jetzt mit.
- **Zwei Dienste an einem Tag: eine Regel fiel aus:** Standen an einem Tag zwei Schichten im Plan — etwa Frühdienst und anschließende Rufbereitschaft —, wertete die App nur die erste aus. Ein eigens gesetzter Dimm-Ausschluss für die zweite wirkte nicht, der Bildschirm wurde trotzdem dunkel und „Nicht stören" schaltete in einer Nacht ein, in der Erreichbarkeit der Zweck ist.
- **Mehrere Dienstplan-Änderungen überschrieben sich:** Brachte ein Abgleich mehrere Änderungen auf einmal, blieb nur die letzte Meldung übrig — ausgerechnet der Hinweis „Schicht entfernt" verschwand dabei am ehesten. Alle Änderungen eines Laufs sind jetzt gemeinsam ablesbar.
- **Schlummern konnte stillschweigend misslingen:** Ließ sich der Schlummer-Wecker nicht stellen, schloss sich der Weckbildschirm genauso, als hätte es geklappt — kein Ton, kein neuer Wecker, kein Hinweis. Jetzt sagt die App es, und der Wecker bleibt lieber laut, als lautlos zu verschwinden.
- **Schlummern trotz Pause:** Der Schlummer-Knopf stellte auch dann einen neuen Weckruf, wenn alle Hintergrunddienste pausiert waren — mitten in einer Pause klingelte es also doch. Außerdem lief ein gerade klingelnder Wecker weiter, wenn man die Pause währenddessen einschaltete.
- **Veraltete Weckzeit beim selbst gestellten Wecker:** Wer die Weckzeit eines Schichttyps änderte und danach — ohne die App zu schließen — einen Wecker von Hand stellte, bekam die alte Zeit; die Karte bestätigte sie sogar. Ein neu angelegter Schichttyp fehlte dort umgekehrt ganz.
- **Gespeichert gemeldet, aber nicht gespeichert:** Konnte die App ihre Weckerliste gerade nicht dauerhaft ablegen, meldete sie den Wecker trotzdem als angelegt. Nach einem Neustart war er weg — bei einem von Hand gestellten unwiederbringlich. Sie sagt es jetzt.

### 🔧 Unter der Haube

- **1045 automatische Tests** (vorher 867). Die Prüfrunde hat 74 Verdachtsfälle untersucht, zehn davon bestätigt und behoben; die anschließende Gegenprüfung der eigenen Korrekturen fand über fünf Runden weitere Fehler, darunter zwei, die schwerer wogen als das ursprüngliche Problem — einer davon wurde deshalb wieder zurückgebaut.

## Version 1.29.2

**Stand:** August 2026

_Ein ausgeschalteter Schichttyp gilt jetzt als zugeordnet – und die App sagt endlich, was „ausgeschaltet" wirklich abschaltet._

### 🐛 Behoben

- **Karte verlangte eine Zuordnung, die es längst gab:** Wer ein Kürzel als Schichttyp angelegt und den Schichttyp danach ausgeschaltet hat, bekam es weiterhin unter „Diese Kürzel stehen in deinem Kalender" angeboten – mit dem Hinweis, es gebe dafür noch kein Erkennungsmuster. Das stimmte nicht, und ein Tipp darauf hätte den Schichttyp wieder eingeschaltet. Zugeordnet ist jetzt zugeordnet, auch wenn der Schichttyp aus ist.

### 🎨 Feinschliff

- **„Ausgeschaltet" sagt jetzt, was es kostet:** In der Schichttypen-Liste stand nur „kein Wecker". Tatsächlich wird eine ausgeschaltete Schicht überhaupt nicht mehr erkannt – damit entfallen auch das Dimmer- und das „Nicht stören"-Zeitfenster. Genau das ist der Unterschied zur „Stillen Schicht", die weiterhin erkannt wird und nur nicht klingelt. Der Schalter im Bearbeiten-Dialog erklärt das jetzt, und wer Rufbereitschaft ohne Wecker, aber mit „Nicht stören" möchte, wird zur richtigen Einstellung geführt.

## Version 1.29.1

**Stand:** August 2026

_Wenn die Anmeldung scheitert, sagt die App jetzt, was zu tun ist – statt „bitte noch einmal versuchen"._

### 🐛 Behoben

- **Anmelde-Fehlermeldung ohne Aussagekraft:** Scheiterte die Anmeldung, stand dort nur „Anmeldung gerade nicht möglich. Bitte noch einmal versuchen." Auf einem Gerät, auf dem gar kein Google-Konto eingerichtet ist, hätte wiederholtes Antippen aber nie geholfen – und genau das war der häufigste Fall. Android liefert für beide Ursachen dieselbe Rückmeldung, die App kann sie also nicht unterscheiden. Die Meldung nennt jetzt beide möglichen Gründe und darunter steht ein Knopf, der direkt in die Konto-Einstellungen springt.

## Version 1.29.0

**Stand:** August 2026

_Der Weckbildschirm kommt jetzt auch auf Geräten zurück, die die App schon lange nutzen — und die Schlummer-Knöpfe sagen endlich die Wahrheit über ihre Dauer._

### 🐛 Behoben

- **Weckbildschirm blieb auf langjährigen Installationen aus:** Wer die App seit dem Frühjahr nutzt, bekam beim Klingeln womöglich nur eine leise Benachrichtigung statt des großen Weckbildschirms mit den Knöpfen „Stopp" und „Schlummern" — und der Wecker durchbrach „Nicht stören" nicht. Grund war eine Einstellung der Weckmeldung, die Android einmal angelegt und danach nie wieder angehoben hat, egal wie oft aktualisiert wurde. Die App legt die Weckmeldung jetzt neu an, damit sie wieder die höchste Stufe bekommt.
- **„5 Min später" schlummerte in Wirklichkeit anders lang:** Die beiden Schlummer-Knöpfe waren fest mit „5 Min" beschriftet, obwohl 3, 10 oder 15 Minuten einstellbar sind — geschlummert wurde die eingestellte Dauer. Die Knöpfe zeigen jetzt genau den Wert, mit dem auch wirklich geplant wird.
- **Die Reparaturhilfe im Status schickte in die Irre:** Der Hinweis nannte eine Stufe, die die eigene Prüfung gar nicht bestanden hätte — wer ihm folgte, sah weiter das Warndreieck. Der Text beschreibt jetzt die Wirkung statt einer Bezeichnung, die je nach Android-Version und Hersteller anders heißt.
- **Knöpfe unter der Navigationsleiste:** Auf Geräten mit den drei Navigationsknöpfen konnten der „Später"-Knopf der Einrichtungsschritte und der „Verstanden"-Knopf der Hersteller-Warnung ganz oder teilweise unerreichbar sein. Alle Bildschirme halten jetzt Abstand zu den Systemleisten, und die betroffenen lassen sich zusätzlich scrollen.
- **Hue-Aktionen scheiterten stumm:** Bridge prüfen, Lampentest und Regeltest fragten außerhalb des Hue-Tabs nie nach der Netzwerk-Freigabe, die Android 17 verlangt — sie meldeten nur einen allgemeinen Netzwerkfehler, ohne dass je der Systemdialog erschien. Alle diese Knöpfe laufen jetzt über dieselbe Abfrage.
- **Übersprungener Wecker konnte trotzdem klingeln:** Ließ sich der Wecker beim Überspringen nicht aus der Liste entfernen, meldete die App trotzdem „übersprungen" — nach einem nächtlichen Neustart klingelte er dann doch. Ein solcher Fehlschlag führt jetzt nicht mehr stillschweigend zu „übersprungen".
- **Skip-Anzeige konnte einfrieren:** Nach einem Lesefehler blieben die Knöpfe „Überspringen" und „Aufheben" bis zum Neustart der App ausgegraut. Die Anzeige nimmt den Betrieb jetzt von selbst wieder auf.

### 🎨 Feinschliff

- Die Schichterkennung rechnet nicht mehr auf der Anzeige-Ebene: Beim Laden vieler Termine und nach dem Speichern der Schicht-Einstellungen ruckelt die Oberfläche nicht mehr.

## Version 1.28.0

**Stand:** August 2026

_Der Rat der App selbst führte in einen Wecker, der angezeigt wurde und trotzdem nicht klingelte — das und zehn weitere Wege, auf denen der Wecker still danebenlag._

### 🐛 Behoben

- **Ein Wecker, der angezeigt wurde und trotzdem nicht klingelte:** Ließ sich „Aufheben" nicht ausführen, riet die App selbst dazu, den übersprungenen Wecker „einfach neu anzulegen". Der neu angelegte Wecker galt dem System aber weiterhin als übersprungen — die App meldete „Wecker gestellt" samt Uhrzeit, gestellt war er nicht. Wer denselben Wecker neu anlegt, will ihn: das Überspringen wird jetzt aufgehoben. Und wenn ein Wecker nicht gestellt werden kann, verschwindet er wieder, statt als Karteileiche mit Anzeige stehen zu bleiben.
- **Ein Schalter in den Systemeinstellungen konnte die App dauerhaft stilllegen:** Auf Android 12 löscht das Abschalten von „Alarme & Erinnerungen" alle gestellten Wecker — auch den einen, an dem die 6-Stunden-Aktualisierung hängt. Danach lief nichts mehr an, ohne Hinweis und ohne Rückweg. Die App merkt es jetzt, sagt es im Status-Tab, kommt von allein zurück und stellt beim Wiedereinschalten die Wecker sofort neu.
- **Fehlte die Berechtigung für exakte Wecker, wäre der Wecker stumm geblieben statt nur verspätet:** Der Ausweichweg durfte den Weckdienst gar nicht starten. Jetzt gibt es einen zweiten Weckweg, der ohne diesen Dienst auskommt.
- **Kalender mit vielen Terminen:** Ab dem 51. Termin in den nächsten zwei Wochen sah die App den Rest der Liste nicht — und hielt die abgeschnittene Liste für vollständig. Für Schichten im nicht gesehenen Teil meldete sie „Schicht entfernt" und löschte den Wecker. Sie liest die Liste jetzt vollständig.
- **Die Dimm-Vorschau konnte den Bildschirm dauerhaft verdunkelt lassen:** Beendete Android die App im Vorschau-Fenster, blieb die systemweite Verdunkelung stehen — bis zu sechs Stunden, in jeder App. Das Ende der Vorschau ist jetzt fest hinterlegt und wird auch dann durchgesetzt.
- **Erteilte Kalender-Freigabe wurde als Ablehnung gemeldet:** Beendete Android die App, während der Google-Zustimmungsdialog offen war, meldete sie danach „Zugriff verweigert" — mit dem Rat, es in den Einstellungen zu ändern, wo es dafür nichts gibt.
- **Nach dem Erteilen der Akku-Ausnahme meldete die App „Kalenderzugriff wurde verweigert":** Zwei verschiedene Systemdialoge trugen dieselbe Kennung, die Antwort des einen wurde dem anderen zugeordnet.
- **Nach einem Neustart schrieb die App kein Fehlerprotokoll mehr:** Und zwar so lange, bis sie das nächste Mal von Grund auf neu startete — ausgerechnet nach dem Ablauf, in dem die Wecker wiederhergestellt werden. Genau dort fehlten damit die Angaben, mit denen sich ein ausbleibender Wecker überhaupt aufklären lässt.
- **Beim Wecker-Abgleich wurde in einem Fall gelöscht, bevor abgestellt wurde:** Endete die App genau dazwischen, blieb ein gestellter Wecker zurück, den die App selbst nicht mehr kannte.
- **Jeder Kalender-Abruf lud alles neu:** Die eingebaute Zwischenspeicherung konnte nie greifen, weil ihr Schlüssel die Uhrzeit enthielt. Das kostete bei jedem Lauf unnötig Daten und Akku.
- **Sicherung und Übertragung auf ein neues Gerät:** Zwei Werte, die nur auf das jeweilige Gerät gehören, wanderten mit — darunter der gesicherte Stand eines übersprungenen Weckers, der auf dem neuen Gerät einen fremden Wecker gestellt hätte.

### ✨ Neu

- **Die App ist kleiner geworden:** 10,1 statt 11,0 MB — zwei Regeln hatten die Verkleinerung bisher praktisch aufgehoben.

## Version 1.27.0

**Stand:** August 2026

_Nach einem nächtlichen Neustart, bei dem das Handy gesperrt blieb, konnte die App den Rest des Tages mit leeren Einstellungen weiterlaufen — das ist der Kern dieser Version._

### 🐛 Behoben

- **Neustart in der Nacht, gesperrtes Handy:** Startete das Handy neu, ohne dass jemand es entsperrte, konnte die App danach ihre eigenen Einstellungen nicht mehr sehen — und zwar dauerhaft, bis sie das nächste Mal komplett neu startete. Sie hielt dann die Kalenderauswahl für leer, legte keine neuen Wecker mehr an und arbeitete mit Standard-Schichtzeiten statt mit den eingestellten. Wer an so einem Tag seine Schichten bearbeitete, überschrieb sich die echten Einstellungen. Behoben — die App wartet jetzt, bis das Gerät entsperrt ist, und holt alles nach.
- **Abgeschalteter Benachrichtigungs-Kanal blieb unbemerkt:** Android erlaubt es, einzelne Benachrichtigungsarten mit zwei Tipps abzuschalten oder leiser zu stellen. Traf das den Wecker-Kanal, erschien der Weckbildschirm nicht mehr — die App meldete im Status-Tab aber weiterhin „Erlaubt". Sie schaut jetzt genau hin und sagt, was abgeschaltet ist und wo man es wieder einschaltet.
- **Warnung „Kalender nicht abrufbar" kam nur einmal — auch wenn sie niemand gesehen hat:** Waren Benachrichtigungen blockiert, merkte sich die App die Warnung trotzdem als „schon gesagt". Sie kam danach nie wieder, auch nicht nach dem Wiedereinschalten. Jetzt gilt sie erst als gesagt, wenn sie wirklich ankommen konnte.
- **„Bridge vergessen" legte die Hue-Planung still lahm:** Nach dem Trennen und erneuten Verbinden der Bridge wurden für später angelegte Schichten kein Lichtstart und keine Vorab-Prüfung mehr geplant — bis die App das nächste Mal komplett neu startete.
- **Zwei gleichzeitige Wartungsläufe konnten sich gegenseitig abwürgen:** Läuft ein erzwungener Abgleich (etwa nach einem Zeitzonenwechsel) noch, während der regelmäßige Lauf schnell fertig ist, brach der erzwungene mittendrin ab — im ungünstigsten Moment zwischen dem Löschen und dem Neusetzen eines Weckers.
- **„Aufheben" holte einen von Hand gestellten Wecker nicht zurück:** Wer einen manuell gestellten Wecker übersprang und es sich anders überlegte, bekam ihn nicht wieder — im Gegensatz zu Weckern aus dem Dienstplan ließ er sich aus nichts wiederherstellen. Jetzt wird er beim Überspringen gesichert und kommt zurück. Geht das gerade nicht (Pause aktiv, ein anderer manueller Wecker steht, Weckzeit verstrichen), bleibt das Überspringen bestehen und die App sagt, woran es liegt — statt den Wecker stillschweigend zu verlieren.

### ✨ Neu

- **Die Hue-Bridge wird wiedergefunden, wenn der Router ihr eine neue Adresse gibt:** Bisher war die Lichtsteuerung nach einem Routerneustart dauerhaft tot und musste von Hand samt Knopfdruck an der Bridge neu verbunden werden. Die App sucht die bekannte Bridge jetzt selbständig unter ihrer neuen Adresse — ohne Knopfdruck, die bestehende Freigabe bleibt gültig.
- **Ein gescheiterter Verbindungsversuch blockiert nicht mehr alle weiteren:** Bisher hielt sich die App nach einem Fehlversuch für verbunden und probierte es bis zum nächsten App-Start kein zweites Mal.

## Version 1.26.2

**Stand:** August 2026

_„Überspringen" lässt sich jetzt wirklich rückgängig machen – und vier weitere Wege, auf denen der Wecker still danebenlag._

### 🐛 Behoben

- **„Überspringen" war endgültig:** Der Knopf „Aufheben" hob nur eine Markierung auf, holte den Wecker aber nicht zurück – und war der übersprungene der einzige Wecker, verschwand der Knopf sogar ganz aus der Anzeige. Ein Antippen von „Überspringen" löschte den Wecker damit unwiderruflich. Jetzt wird der Wecker beim Aufheben aus dem aktuellen Dienstplan neu erstellt (hat sich die Schicht inzwischen verschoben, bekommst du die neue Zeit), und der Knopf bleibt sichtbar, solange etwas zum Aufheben da ist.
- **Nach „Später" im Einrichtungs-Assistenten lief die Hintergrund-Aktualisierung nicht an:** Wer beim Akku-Hinweis auf „Später" tippte oder zurückging, bekam die 6-Stunden-Aktualisierung nie gestellt. Neue Schichten wurden dann nur noch beim Öffnen der App zu Weckern – genau das, was die App eigentlich abnehmen soll. Erst ein Neustart des Handys reparierte es.
- **Nach einem Zeitzonen-Wechsel blieb der Wecker auf der alten Zeit:** Die App holt in diesem Fall bewusst frische Termine, fragte den Server aber trotzdem „hat sich was geändert?" – und weil sich am Kalender nichts geändert hatte, bekam sie die alten Zeiten zurück. Der Wecker ging danach um den vollen Zeitunterschied falsch.
- **Zweimal im Jahr lag die Hue-Lichtrampe eine Stunde daneben:** An den Umstellungstagen auf Sommer- bzw. Winterzeit startete der Sonnenaufgang zu früh oder erst nach dem Klingeln. Auch die Bridge-Prüfung vor dem Wecker lief an diesen Tagen zur falschen Zeit.
- **Zwei Lesefehler-Lücken geschlossen:** Ein Fehler beim Lesen der Einstellung „Schicht-Änderung" konnte mitten in der Wecker-Erstellung abbrechen. Und die neue Warnung „Kalender nicht abrufbar" kam nach dem Aus- und Wiedereinschalten nicht mehr.

## Version 1.26.1

**Stand:** August 2026

_Aufräumen unter der Haube – für dich ändert sich nichts._

### 🧹 Aufgeräumt

- **Irreführende Code-Reste entfernt:** Ein projektweiter Prüflauf hat 344 ungenutzte Stellen im Code gefunden. Beseitigt wurden die drei, die im Fehlerfall echte Zeit gekostet hätten – darunter eine Datei, die eine Wecker-Überwachung beschrieb, die es gar nicht gibt, und zwei Vibrationsmuster, die niemand gelesen hat (der Wecker vibriert nach einem dritten). Keine Funktion ändert sich, aber die nächste Fehlersuche läuft nicht mehr in Sackgassen.

## Version 1.26.0

**Stand:** August 2026

_Wenn ein Kalender dauerhaft ausfällt, sagt die App es jetzt – vorher versiegten die Wecker stillschweigend._

### ✨ Neu

- **Warnung, wenn ein gewählter Kalender nicht mehr antwortet:** Hast du mehrere Kalender ausgewählt und einer davon verschwindet dauerhaft (gelöscht, Freigabe zurückgezogen, abonnierter Feed abgeschaltet), legt die App aus Sicherheitsgründen keine neuen Wecker mehr an – sie kann dann nämlich nicht mehr unterscheiden, ob eine Schicht wirklich gestrichen wurde oder nur der Kalender fehlt. Bisher passierte das *lautlos*: die gestellten Wecker klingelten der Reihe nach ab, es kam nichts nach, und nach etwa zwei Wochen war der Wecker weg, ohne dass irgendwo etwas stand. Jetzt zeigt die Status-Karte „Kalender", welcher Kalender betroffen ist und was das bedeutet – mit einem Knopf, um ihn aus der Auswahl zu entfernen. Danach läuft alles sofort wieder normal.
- **Und eine Benachrichtigung dazu**, denn genau in diesem Fall öffnet man die App ja gerade nicht. Sie kommt erst, wenn derselbe Kalender zweimal hintereinander nicht antwortet – ein einzelner Aussetzer im Funkloch löst also keinen Fehlalarm aus. In den Einstellungen unter „Benachrichtigungen" lässt sie sich getrennt von der Schicht-Änderungs-Meldung abschalten.

### 🔎 Gut zu wissen

- **Deine bereits gestellten Wecker bleiben in dieser Lage unangetastet** – das war schon vorher so und bleibt so. Neu ist nur, dass du davon erfährst.
- Die App entfernt einen Kalender **nie von selbst** aus deiner Auswahl. Ein vorübergehend nicht erreichbarer Kalender sieht für die App genauso aus wie ein gelöschter, und deine Auswahl stillschweigend zu ändern wäre der schlechtere Fehler.

## Version 1.25.3

**Stand:** August 2026

_Fünf Wege, auf denen der Wecker still hätte ausfallen können – gefunden, bevor sie jemanden getroffen haben._

### 🐛 Behoben

- **Nach einem Neustart konnten die Wecker verschwinden, wenn ein Kalender gerade nicht antwortete:** Direkt nach dem Hochfahren ist der Dienstplan-Kalender manchmal noch nicht erreichbar. Die App stellte die gespeicherten Wecker dann zwar korrekt wieder her – räumte sie im selben Durchgang aber gleich wieder ab, weil sie die halbe Terminliste für die ganze hielt. Jetzt wird in dieser Lage gar nicht aufgeräumt.
- **Ein Lesefehler in den Einstellungen konnte den Wecker komplett stumm schalten:** Beim Klingeln liest die App die eingestellte Schlummerdauer. Ließ sich der Wert einmal nicht lesen (voller Speicher, kurzzeitiger Dateifehler), brach der ganze Weckvorgang ab – kein Ton, keine Anzeige. Jetzt wird in dem Fall einfach die Standard-Schlummerdauer verwendet und der Wecker klingelt.
- **„Nicht stören" konnte stundenlang hängen bleiben:** Derselbe Fehlertyp beim Lesen der DND-Einstellungen ließ die Zeitsteuerung stehen – „Nicht stören" blieb dann bis zu sechs Stunden über das Fensterende hinaus an oder eine Nacht lang aus. Die Kette läuft jetzt auch bei einem Lesefehler weiter.
- **Dasselbe beim Schicht-Dimmer:** Auch die Dimm-Regeln konnten beim Lesen einen Fehler durchreichen. Jetzt wird in dem Fall schlicht nicht gedimmt, statt die Zeitsteuerung abreißen zu lassen.
- **Der Weck-Bildschirm zeigte nach dem Schlummern die falsche Schicht:** Klingelte der Schlummer-Wecker, während der Weck-Bildschirm noch im Hintergrund lag, standen dort weiterhin Name und Beginn der *vorherigen* Schicht – während Ton und Knöpfe längst zum neuen Wecker gehörten. Wer im Halbschlaf die falsche Uhrzeit liest, legt sich womöglich wieder hin. Anzeige und Bildschirm-Wachhaltung werden jetzt mitgezogen.

## Version 1.25.2

**Stand:** August 2026

_„Nicht stören" hält jetzt wirklich die ganze Dienstzeit durch – und der Schichtbeginn wird nicht mehr als „Schicht entfernt" gemeldet._

### 🐛 Behoben

- **„Während der Dienstzeit" schaltete sich zum Dienstbeginn wieder ab:** Die Dienstzeit-Fenster wurden aus den gestellten Weckern abgeleitet – und ein Wecker verschwindet, sobald er geklingelt hat. Damit war „Nicht stören" ausgerechnet während der Schicht aus. Am Emulator nachgemessen: mitten in der Frühschicht (Termin 06:00–14:12, Wecker 05:30) war „Nicht stören" um 08:00 abgeschaltet. Die Dienstzeiten werden jetzt eigenständig gemerkt und überleben das Klingeln.
- **Jeden Schichtmorgen eine falsche „Schicht entfernt"-Meldung:** Sobald die Weckzeit vorbei war, meldete die App den Dienst, den man gerade antrat, als aus dem Dienstplan entfernt. Die Meldung kommt jetzt nur noch, wenn der Termin wirklich verschwunden ist.
- **Dasselbe beim Schicht-Dimmer:** Dimm-Fenster, die am *Schichtende* hängen, verschwanden aus demselben Grund mitten in der Schicht. Auch sie stützen sich jetzt auf die gemerkten Dienstzeiten.

## Version 1.25.1

**Stand:** August 2026

_Ein Wecker verschwindet nicht mehr, nur weil die App gerade nicht alle Termine geladen hatte._

### 🐛 Behoben

- **Die spätesten Wecker konnten verschwinden:** Beim Öffnen lädt die App zunächst nur die nächsten Termine und den Rest beim Weiterblättern. Wurde in diesem Moment die Schicht-Konfiguration gespeichert, hielt die App die noch nicht geladenen Termine für abgesagt und löschte deren Wecker – bei einem Dienstplan mit mehr als zehn Terminen in zwei Wochen der Normalfall. Am Emulator mit einem echten Dienstplan nachgestellt: aus acht Weckern wurden sechs. Jetzt wird nur noch abgeglichen, wenn der vollständige Terminbestand vorliegt.
- **Vorab-Abgleich drei Stunden vor dem Wecken:** Antwortete dabei einer von mehreren Kalendern nicht – etwa der Dienstplan-Feed bei schlechter Verbindung –, wurden dessen Wecker als „abgesagt" entfernt, ausgerechnet kurz vor dem Klingeln. Der Abgleich wird jetzt ausgelassen, bis wieder alle Kalender antworten; bestehende Wecker bleiben unverändert stehen.

## Version 1.25.0

**Stand:** August 2026

_Hue-Regeln überleben jetzt einen Bridge-Wechsel: nach einem Konfigurations-Import oder einer neuen Bridge finden sie ihre Lampen über den Namen selbst wieder – und was übrig bleibt, wird benannt statt verschwiegen._

### 🐛 Behoben

- **Hue-Regeln zeigten nach einem Wechsel der Bridge ins Leere:** Eine Regel merkt sich ihre Lampen über eine Nummer, die nur auf *der einen* Bridge gilt. Nach einem Konfigurations-Import oder mit einer neuen Bridge sah die Regel vollständig aus, schaltete aber nichts oder die falsche Lampe – und das merkt man erst morgens. Jetzt merkt sich jede Regel zusätzlich den **Namen** der Lampe bzw. Gruppe und ordnet sich beim ersten Kontakt mit der Bridge automatisch wieder zu.

### ✨ Neu

- **Sichtbarer Hinweis auf unbekannte Ziele:** Was sich nicht eindeutig wiederfinden lässt – etwa weil eine Lampe umbenannt wurde oder zwei gleich heißen –, wird im Hue-Bereich, in der Regel-Liste und im Regel-Editor namentlich angezeigt, statt still zu scheitern. Die App ordnet dabei bewusst *nichts* auf Verdacht zu: eine falsch zugeordnete Lampe wäre schlimmer als eine, die man selbst neu auswählt.
- **Rückmeldung beim Import:** Ist beim Einlesen einer Konfigurationsdatei bereits eine Bridge verbunden, steht direkt in der Erfolgsmeldung, wie viele Ziele auf diesem Gerät unbekannt sind.
- Ist die Bridge gerade nicht erreichbar (unterwegs, fremdes WLAN), passiert **nichts**: keine Regel wird umgeschrieben und keine als fehlerhaft markiert.

## Version 1.24.2

**Stand:** August 2026

_Wartungsversion ohne sichtbare Änderung. Der Weck-Bildschirm ist jetzt zusätzlich automatisch abgesichert._

### 🔧 Unter der Haube

- Eine Android-Grundbibliothek aktualisiert, an der ausgerechnet der Weck-Bildschirm hängt. Damit so ein Update nicht unbemerkt am Wecken vorbeigeht, prüft ein automatischer Test jetzt bei jeder Änderung, dass der Weck-Bildschirm erscheint, seine Farben trägt und beide Schaltflächen – „Alarm stoppen“ und „Schlummern“ – da und bedienbar sind, auch bei gesperrtem Bildschirm.

## Version 1.24.1

**Stand:** August 2026

_Kleine Nachlese zu 1.24.0: ein Fehler, der die Verbindung zur Hue-Bridge in manchen Heimnetzen von vornherein verhindert hat, plus die üblichen Aktualisierungen im Unterbau._

### 🐛 Behoben

- **Die Hue-Bridge wurde in manchen Netzen abgelehnt, obwohl sie erreichbar war:** Die App hat vorab geschätzt, ob sich Handy und Bridge im selben Netzabschnitt befinden – und bei Zweifeln gar nicht erst gefragt. In einem Gast-WLAN, einem getrennten Netz, hinter einem Mesh-Repeater mit eigenem Adressbereich oder bei doppeltem Router traf die Schätzung nicht zu, und das Einrichten schlug fehl, obwohl die Bridge geantwortet hätte. Jetzt entscheidet der tatsächliche Verbindungsversuch; die Schätzung bestimmt nur noch, wie lange gewartet wird.

### 🔧 Unter der Haube

- Oberflächen-Bibliothek (Jetpack Compose) und das Build-Werkzeug aktualisiert. Keine sichtbare Änderung an der App – geprüft wurde, dass Größe, Start und Anzeige unverändert sind.

## Version 1.24.0

**Stand:** August 2026

_Aufräum-Runde mit einer kleinen, lang überfälligen Neuerung. Die Prüfung am Ende lief bewusst über Code, an dem in dieser Runde *nichts* geändert wurde – und fand dabei vier echte Fehler, die alle schon länger drinsteckten._

### ✨ Neu

- **Lichtregeln für „Alle Schichten" (Hue-Tab → Regel anlegen):** Bisher musste jede Lichtregel an einen bestimmten Schichttyp gebunden werden. Jetzt gibt es die Auswahl „Alle Schichten“ – die Regel gilt dann für jeden erkannten Dienst. Praktisch für „Licht an, egal welche Schicht“. Die Funktion war intern längst vorhanden, ließ sich aber über keine Ansicht einstellen.

### 🐛 Behoben

- **Ein Lesefehler an den Schichttypen konnte die Weckzeiten auf die Vorgabewerte zurückwerfen:** Ließ sich die gespeicherte Konfiguration einmal nicht lesen, war das für die App nicht von „noch nichts eingerichtet“ zu unterscheiden. Sie arbeitete dann eine halbe Minute lang mit den Standard-Weckzeiten weiter – und Schichten mit eigenen Erkennungsmustern wurden in dieser Zeit nicht mehr erkannt und ihre Wecker gelöscht.
- **Nach einem Neustart konnte die gesamte Wiederherstellung ausfallen:** Ein einzelner fehlgeschlagener Lesezugriff direkt nach dem Hochfahren beendete die App, bevor sie Wecker, 6-Stunden-Wartung, Dimmer und „Nicht stören“ wieder in Gang setzen konnte.
- **Ein Lesefehler an den Dimmer-Einstellungen konnte die App beenden** – also ausgerechnet die App, die den Wecker hält.
- **Der Bildschirm konnte nach der Dimmer-Vorschau dauerhaft abgedunkelt bleiben:** Wer die App innerhalb der fünf Sekunden verließ, behielt eine geräteweite Verdunkelung, die sich von selbst unter Umständen gar nicht mehr löste. Betraf beide Vorschau-Knöpfe (Dimmer-Tab und „Regel testen“).
- **Ein Schlummer-Wecker konnte beim Neustart aus der Merkliste fallen** und war danach weder abbrechbar noch wiederherstellbar.

### 🔧 Unter der Haube

- Bedienungshilfen verbessert: Alle Symbole wurden einzeln daraufhin geprüft, ob ein Screenreader sie vorlesen muss – bedienbare Schaltflächen haben jetzt eine Beschriftung, rein dekorative bleiben bewusst stumm, damit nicht alles doppelt vorgelesen wird.
- Der Hue-Regel-Editor und die interne Sonnenaufgangs-Steuerung wurden entflochten; Bildschirme aktualisieren sich jetzt nur noch, solange sie sichtbar sind (schont den Akku).

## Version 1.23.1

**Stand:** August 2026

_Reine Zuverlässigkeits-Runde. Zwei Fehler haben nach einem nächtlichen Neustart verhindert, dass die Wecker vor dem ersten Entsperren zurückkamen — beide waren nur mit gesetzter Bildschirmsperre überhaupt sichtbar._

### 🐛 Behoben

- **Nach einem Neustart kamen die Wecker erst zurück, wenn das Handy entsperrt wurde:** Beim Start vor der ersten Entsperrung stürzte die App unbemerkt ab, noch bevor sie die gespeicherten Wecker wiederherstellen konnte. Startet das Gerät nachts neu (Systemupdate, leerer Akku am Ladegerät) und niemand entsperrt es, klingelte am Morgen nichts. Zwei unabhängige Ursachen, beide behoben und am Testgerät mit PIN nachgewiesen.
- **Die App konnte „keine Wecker" für wahr halten, obwohl Wecker gespeichert waren:** Vor der ersten Entsperrung ist der gespeicherte Bestand nicht lesbar — das sah für die App aber aus wie „es gibt keine". Der nächste Abgleich hielt daraufhin jede Schicht für neu und überschrieb den Bestand; ein selbst gestellter Wecker war damit weg. Der Bestand wird jetzt nach dem Entsperren nachgeladen, statt die Leere zu glauben.
- **Ein selbst gestellter Wecker konnte bei „keine passende Schicht" mitgelöscht werden** — obwohl er nichts mit dem Kalender zu tun hat. Er bleibt jetzt erhalten; nur ausdrückliches Ausschalten (Master-Pause, „Automatische Alarme aus") räumt weiterhin alles.
- **„Automatische Alarme" aus: ein manuell gestellter Wecker verschwand stillschweigend wieder.** Jetzt sagt die App beim Anlegen, dass in diesem Zustand alle Wecker gelöscht werden.
- **Ein Zeitzonenwechsel im Hintergrund konnte die App beenden** — und damit ausgerechnet die Neuberechnung der Weckzeiten verhindern, für die dieser Mechanismus existiert.
- **Ein Fehler beim Lichtsteuerungs-Zugriff konnte die App beenden.** Für eine Wecker-App die falsche Reihenfolge der Wichtigkeit: das ist jetzt abgefangen, und die selbstständige Wiederverbindung zur Hue-Bridge überlebt einen einzelnen Fehlversuch.
- **Der Schlummer-Wecker war nach einem Neustart nicht immer wiederherstellbar** (der Merker konnte bei gleichzeitigen Zugriffen verloren gehen), und das Start-Protokoll behauptete Wiederherstellungen, die nicht stattgefunden hatten.
- **Anzeigen, die etwas anderes behaupteten als die App tut:** ein ausgeschalteter Schichttyp zeigte weiter eine Weckzeit an (obwohl er nie einen Wecker stellt), die Startseite meldete „Schichttypen werden noch geladen" für einen dauerhaft gescheiterten Ladeversuch und „Zeige 5 von N Terminen" auf einer Karte, die überhaupt keine Termine listet, und die Dimmer-Karte meldete einen „aktiven Fehler", obwohl der Dimmer nie eingeschaltet war.
- **Im Kürzel-Zuordnungsdialog war die letzte Schicht auf schmalen Geräten nicht antippbar** — und der Dialog ist der einzige Weg, ein Kürzel zuzuordnen.

## Version 1.23.0

**Stand:** August 2026

_Der größte Zuverlässigkeits-Durchgang bisher: der Wecker fällt in einer Reihe von Situationen nicht mehr aus, in denen er es vorher konnte. Dazu zwei Dinge, die die Einrichtung deutlich einfacher machen – die Kürzel deines echten Dienstplans werden vorgeschlagen, und die Konfiguration lässt sich als Datei sichern._

### ✨ Neu

- **Kürzel aus deinem Kalender werden vorgeschlagen (Wecker-Tab → „Schichttypen verwalten"):** Ganz oben steht jetzt die Karte „Diese Kürzel stehen in deinem Kalender" – sie zeigt die Termin-Kürzel, die von keinem Erkennungsmuster getroffen werden, nach Häufigkeit sortiert. Antippen, Schicht auswählen, und das Kürzel wird als Muster ergänzt. Bisher musste man raten, welche Muster die Vorgaben mitbringen; auf einer anderen Station als der, für die sie gedacht waren, hätte man ohne diesen Schritt *nie* einen Wecker bekommen. Zugeordnet wird nur, was du selbst zuordnest – eine falsche Automatik würde einen Wecker auf die falsche Uhrzeit stellen.
- **Konfiguration exportieren und importieren (Einstellungen-Tab → „Konfiguration"):** Sichert Schichttypen, Hue-Regeln, Dimmer-Regeln und „Nicht stören" als Datei – überlebt eine Neuinstallation, funktioniert auch auf demselben Gerät und lässt sich an Kolleginnen und Kollegen weitergeben. Anmeldung, Kalenderauswahl und die Hue-Bridge-Zugangsdaten sind bewusst *nicht* in der Datei; die richtet man auf jedem Gerät selbst ein. Der Import fragt vorher nach, überschreibt den aktuellen Stand und setzt die Wecker sofort neu.
- **Schichterkennung nicht mehr an eine Station gebunden:** Jeder vorgegebene Schichttyp hat jetzt neben dem Stationskürzel eine allgemeine Bezeichnung, und der Name des Schichttyps zählt selbst als Erkennungsmuster (ab zwei Zeichen). An bestehenden Konfigurationen ändert sich nichts, solange du nicht auf Standardwerte zurücksetzt.
- **Rückfrage vor destruktiven Aktionen:** „Auf Standardwerte zurücksetzen" und das Löschen eines Schichttyps fragen jetzt nach – beides wirkte sofort auf die gesetzten Wecker und ließ sich nicht rückgängig machen.
- **Die App ist deutlich kleiner:** rund 10,9 statt 19,8 MB.

### 🐛 Behoben

- **Deaktivierte Schichttypen weckten trotzdem:** Der Schalter „Schichtdefinition aktiviert" nahm den Typ nur aus den Auswahllisten, die Erkennung lief unverändert weiter und stellte weiter Wecker.
- **Ein fehlgeschlagener Kalenderabruf konnte ALLE Wecker löschen:** Eine leere Terminliste war von „du hast frei" nicht zu unterscheiden – ein Netzfehler oder ein Lesefehler wurde damit als „keine Schichten" verstanden und räumte die gesetzten Wecker ab. An mehreren Stellen behoben, unter anderem auch in der Prüfung 3 Stunden vor dem Wecker.
- **Die 6-Stunden-Wartung erkannte verschobene und gestrichene Schichten nicht:** Solange der Dienstplan weit im Voraus gepflegt war, sah sie den Kalender praktisch nie an. Der Wecker klingelte dann zur alten Zeit oder für eine Schicht, die es nicht mehr gab – bei einer Krankschreibung Ende Juli rund vier Tage lang unbemerkt.
- **Ein gestellter Schlummer-Wecker war durch nichts abzubrechen:** Er klingelte durch „Automatische Alarme aus" und durch „Hintergrunddienste pausieren" hindurch – also mitten in einer gerade eingeschalteten Pause.
- **Der Wecker fiel ganz aus, wenn die Berechtigung für exakte Alarme entzogen war** – während die App weiterhin „Alarme aktiv" anzeigte. Jetzt wird in diesem Fall ungenau geplant (ein paar Minuten Abweichung) statt gar nicht.
- **„Wecker aus" und „5 Min später" gleichzeitig getroffen** (die Knöpfe liegen dicht übereinander) stoppte den Wecker *und* stellte zusätzlich einen Schlummer.
- **Ganztägige Kalendertermine wurden um Stunden verrechnet:** „Deine Schicht beginnt um 02:00", Nicht-stören-Fenster ab 02:00, und in einer Konstellation ein Wecker einen ganzen Tag zu früh.
- **„Mehr laden" in der Terminliste zeigte Termine doppelt, ließ einen Block dazwischen aus und konnte beim Scrollen abstürzen** – bei mehr als einem ausgewählten Kalender. Dadurch erschienen auch Schichten doppelt auf der Startseite.
- **Schicht-Dimmer und „Nicht stören" verpassten ganze Nächte:** An den Zeitumstellungstagen verschoben sich die Fenster um eine Stunde; ein über Mitternacht laufendes Fenster wurde nach 00:00 fälschlich für beendet gehalten und abgeschaltet; und nach einer Woche ohne Schichten blieb „Während der Dienstzeit" bis zum nächsten Geräte-Neustart wirkungslos.
- **Verdunkelung und Wärme wirkten erst am nächsten Morgen:** Ein mitten in der Nacht verstellter Regler änderte am laufenden Dimmen nichts.
- **Hue: die Bridge meldet auch eine Ablehnung als Erfolg** – die App hielt daraufhin Licht für eingeschaltet, das nie anging, legte trotzdem das Auto-Aus an, zeigte „Keine Lampen gefunden" statt eines Hinweises auf die nötige Neukopplung, und die Sonnenaufgangs-Rampe konnte komplett ausfallen. Die Antworten der Bridge werden jetzt wirklich ausgewertet.
- **Beschädigte gespeicherte Daten überschrieben stillschweigend echte Einstellungen:** Ein Lesefehler galt als „leer" bzw. „Standardwerte", und der nächste Speichervorgang machte das dauerhaft – betraf Schichtkonfiguration, Weckerliste und Anmeldedaten. Jetzt wird der Fehler gemeldet, der ursprüngliche Stand gesichert und nicht überschrieben.
- **„Auf Standardwerte zurücksetzen" hob die ausgeschaltete Wecker-Automatik auf** – wer nur seine Schichttypen aufräumte, hatte im selben Moment wieder Wecker für alle erkannten Termine. Der Automatik-Schalter bleibt jetzt unangetastet. Außerdem war der Knopf bei fünf Schichttypen aus dem Bildschirm geschoben und dadurch nicht erreichbar.
- **Nach einer Wiederherstellung auf einem neuen Gerät fragte die App nie wieder nach der Akku-Ausnahme und „App bei Nichtnutzung pausieren"** – obwohl auf dem neuen Gerät naturgemäß keine erteilt war; genau die zwei Einstellungen, die in diesem Projekt nachweislich Wecker verschluckt haben. Ebenso wird ein aktives „Hintergrunddienste pausieren" nicht mehr auf das neue Gerät mitgeschleppt, wo es den Wecker stumm gehalten hätte.
- **Ein Neustart des Geräts während des Schlummerns ließ den Wecker verschwinden:** Android verliert bei einem Neustart alle gestellten Wecker, und der ursprüngliche Wecker war zu dem Zeitpunkt schon abgelaufen — wer schlummerte und dessen Handy in den Minuten danach neu startete (Systemupdate, leerer Akku am Kabel), wurde nie wieder geweckt. Der Schlummer wird jetzt beim Start wiederhergestellt, auch schon vor der ersten Entsperrung.
- **Ein Kürzel einer ausgeschalteten Schicht zuzuordnen tat garantiert nichts:** Die Erkennung beachtet nur eingeschaltete Schichttypen. Die Zuordnung schaltet den Typ jetzt mit ein und entfernt das Kürzel bei allen anderen Schichten — sonst hätte die Reihenfolge der Liste darüber entschieden, wann geweckt wird.
- **Eine beschädigte oder von Hand bearbeitete Konfigurationsdatei wird beim Import nicht mehr blind übernommen:** Unsinnige Werte (etwa eine Schlummer-Dauer von 0 Minuten — der Wecker hätte sich nicht mehr wegdrücken lassen) und unlesbare Regelwerke werden benannt abgelehnt, statt still als „keine Regeln" durchzugehen.
- **Hinweistexte, die etwas anderes behaupteten als die App tut,** sind richtiggestellt – unter anderem der Erkennungs-Hinweis in der Schicht-Konfiguration und der Weg zurück zum Kalender-Zugriff.

## Version 1.22.1

**Stand:** August 2026

_Die Dimmer-Korrektur-Benachrichtigung erschien praktisch nie, obwohl sie eingeschaltet war._

### 🐛 Behoben

- **Der Schalter für die Dimmer-Korrektur-Benachrichtigung wirkte erst beim nächsten Fensterwechsel:** Wer ihn mitten in einer laufenden Dimm-Phase einschaltete, sah die Benachrichtigung für genau diese Phase nicht mehr – der nächste Wechsel ist üblicherweise das Ende des Fensters am Morgen. Jetzt wirkt der Schalter sofort.
- **Ohne erteilte Benachrichtigungs-Berechtigung blieb die Korrektur-Benachrichtigung lautlos aus**, ohne jeden Hinweis.

## Version 1.22.0

**Stand:** August 2026

_Schlummer-Dauer ist jetzt einstellbar, dazu ein größerer Rundum-Check mit mehreren behobenen Rand- und Fehlerfällen._

### ✨ Neu

- **Schlummer-Dauer einstellbar (Wecker-Tab):** Bisher fest 5 Minuten – jetzt per Dropdown wählbar (3/5/10/15 Minuten), gilt gleichermaßen für den Vollbild- und den Benachrichtigungs-Schlummer-Knopf.

### 🐛 Behoben

- **Zwei Schichten mit gleichem Namen konnten die Schicht-Verwaltung zum Absturz bringen** oder beim Bearbeiten leise zu einer einzigen Schicht verschmelzen.
- **Ein beschädigtes Anmelde-Datenpaket konnte die App bei jedem Start abstürzen lassen** – erholt sich jetzt selbstständig, statt in einer Absturzschleife hängenzubleiben.
- **In seltenen Fällen forderte dich die App unnötig zur erneuten Google-Anmeldung auf**, obwohl die Sitzung eigentlich noch gültig war (passierte, wenn z. B. Wartung und ein manuelles Aktualisieren gleichzeitig liefen).
- **Eine beschädigte Kalenderauswahl-Datei ließ die Auswahl dauerhaft leer erscheinen**, statt sich zurückzusetzen.
- **Zurück-Knopf und Speichern in der Hue-/Dimmer-Regelbearbeitung führten je nachdem, wie man den Bildschirm geöffnet hatte, an unterschiedliche Stellen zurück.**
- **Der Hinweis zur TimeOffice-Synchronisation erschien nicht automatisch**, wenn du die App schon vor diesem Feature eingerichtet hattest.
- **Schnelles Doppel-Antippen zweier Ausnahme-Chips beim Dimmer-Nacht-Standard konnte eine der beiden Auswahlen stillschweigend verwerfen.**
- **Sehr schnell aufeinanderfolgende Kalender-Aktualisierungen** (z. B. Auswahl ändern und sofort „Aktualisieren" tippen) **konnten die ältere, überholte Kalenderliste die neuere überschreiben lassen.**
- **Neu angelegte oder umbenannte Schichten tauchten in der Dimmer-Regel-Bearbeitung erst nach einem App-Neustart auf.**

## Version 1.21.1

**Stand:** August 2026

_Dimmer und Nicht-stören konnten in einer seltenen Kalenderkonstellation eine ganze Nacht überspringen._

### 🐛 Behoben

- **Nacht-Standard übersprang eine ganze Nacht:** Folgte auf einen Arbeitstag mit Wecker am Nachmittag/Abend ein Tag ganz ohne Kalendertermin, blieben Dimmer und „Nicht stören" für die dazwischenliegende Nacht komplett aus – auch wenn danach wieder ein normaler Frühwecker anstand.

## Version 1.21.0

**Stand:** August 2026

_Ein eigener Wecker-Tab bündelt, was bisher verstreut war, und ein neuer Schalter pausiert bei Bedarf wirklich alles._

### ✨ Neu

- **Neuer Wecker-Tab:** Der Schalter „Automatische Alarme", die Liste der aktiven Wecker samt „Nächsten Alarm überspringen" und die Verwaltung der Schichttypen leben jetzt gemeinsam an einem Ort in der Tableiste – vorher verteilt auf Start, Status und Einstellungen.
- **„Hintergrunddienste pausieren" (neuer Bereich unten in den Einstellungen):** Ein einziger Schalter für längere Abwesenheit – legt Wecker, Dimmer, Nicht-stören und Hue-Automatik gemeinsam mit der 6-Stunden-Wartung selbst still, inklusive über einen Neustart des Geräts hinweg. Beim Wiedereinschalten läuft alles automatisch wieder an.

### 🐛 Behoben

- **„Automatische Alarme" ausschalten löschte bereits gesetzte Wecker nicht:** Der Schalter verhinderte bisher nur neue Wecker – ein schon gestellter klingelte trotzdem. Aus- und Wiedereinschalten wirkt jetzt sofort in beide Richtungen.

## Version 1.20.1

**Stand:** August 2026

_Die Wecker-Anzeige zeigte die falsche Uhrzeit für den Schichtbeginn._

### 🐛 Behoben

- **Vollbild-Wecker und Benachrichtigung zeigten die Weckzeit statt des tatsächlichen Schichtbeginns:** Bei „Deine Schicht beginnt um …" stand bisher die Uhrzeit, zu der der Wecker klingelt (z. B. wegen Anfahrtszeit früher als die Schicht) – nicht die laut Kalender tatsächliche Startzeit der Schicht. Betraf auch das erneute Klingeln nach „5 Min später".

## Version 1.20.0

**Stand:** August 2026

_Rufbereitschaft bekommt eine eigene Nicht-stören-Schiene, Schichten können jetzt "still" sein, und du erfährst per Benachrichtigung, wenn sich eine Schicht ändert oder der Schicht-Dimmer korrigiert werden soll._

### ✨ Neu

- **Rufbereitschaft in „Nicht stören" (neue Karte „Rufbereitschaft"):** Du markierst bestimmte Schichten als Rufbereitschaft und legst eine feste Uhrzeit fest, ab der du erreichbar sein musst – Nicht-stören endet an diesen Tagen automatisch spätestens zu dieser Uhrzeit, unabhängig davon, wie lange dein reguläres Schlaf-Fenster sonst laufen würde.
- **Stille Schicht (neuer Schalter in der Schicht-Konfiguration):** Für Schichten wie Rufbereitschaft, bei denen du keinen lauten Wecker brauchst – Ton, Vibration und Vollbild-Wecker bleiben aus, die Weckzeit dient weiterhin als Anker für Nicht-stören/Dimmer.
- **Benachrichtigung bei Schichtänderung (neuer Bereich „Benachrichtigungen" in den Einstellungen, standardmäßig an):** Ändert, entfernt oder ergänzt TimeOffice eine Schicht, bekommst du das jetzt direkt gemeldet, statt es erst beim Blick in die App zu bemerken. Zusätzlich prüft die App ab jetzt automatisch noch einmal 3 Stunden vor jedem Wecker, ob sich am Dienstplan etwas geändert hat.
- **Dimmer-Korrektur-Benachrichtigung (abschaltbar in den Einstellungen, standardmäßig aus):** Erscheint, solange der Schicht-Dimmer aktiv ist, mit Heller/Dunkler/Pause – für den Fall, dass er zu früh oder zu stark dimmt.

## Version 1.19.1

**Stand:** Juli 2026

_Mehrere Status-Karten führen jetzt direkt zur Lösung statt nur ein Problem zu melden._

### ✨ Neu

- **Direkter Sprung zur Lösung im Status-Tab:** Die Karten „Kalender" (kein Kalender gewählt bzw. Autorisierung verloren) und „Schicht-Konfiguration" (keine Konfiguration verfügbar) bieten jetzt einen Knopf, der direkt zur passenden Aktion führt. Die Karte „Letzter Sync" bekommt bei langer Pause einen „Jetzt synchronisieren"-Knopf.

## Version 1.19.0

**Stand:** Juli 2026

_Neu: eine Status-Karte behält jetzt auch im Blick, ob TimeOffice selbst zuverlässig im Hintergrund läuft._

### ✨ Neu

- **TimeOffice-Zuverlässigkeit im Status-Tab:** CFAlarms Alarme hängen an einem Kalender, den die TimeOffice-App liefert. Wird TimeOffice selbst von Android im Hintergrund eingeschränkt, bleibt der Dienstplan-Sync stehen, ohne dass CFAlarm selbst etwas davon merkt. Eine neue Karte zeigt den Akku-Optimierungs-Status von TimeOffice und verlinkt direkt auf dessen Einstellungen. Erscheint einmalig auch als Hinweis bei der Ersteinrichtung, falls TimeOffice installiert ist.

## Version 1.18.2

**Stand:** Juli 2026

_Der Hinweis „Nächsten Alarm überspringen" konnte tagelang hängen bleiben, auch wenn der übersprungene Alarm längst vorbei war._

### 🐛 Behoben

- **„Aufheben"-Zustand blieb stehen, obwohl der übersprungene Alarm längst vorbei war:** Nach einem einmaligen „Nächsten Alarm überspringen" sollte die Karte automatisch wieder in den normalen Zustand (grünes Symbol, „Überspringen") zurückkehren, sobald dieser Zeitpunkt verstrichen ist. Stattdessen blieb sie im „Aufheben"-Zustand (bräunliches Symbol) hängen, bis man selbst manuell auf „Aufheben" tippte – teils über mehrere Tage, obwohl der eigentliche Wecker in der Zwischenzeit ganz normal geklingelt hat. Die App setzt den Hinweis jetzt von selbst zurück, sobald der übersprungene Zeitpunkt vorbei ist.

## Version 1.18.1

**Stand:** Juli 2026

_Neu: Android's „Nicht stören" lässt sich jetzt automatisch nach Schicht steuern – und du bestimmst selbst, was davon betroffen ist._

### ✨ Neu

- **Nicht stören automatisch schalten (neuer Bereich in den Einstellungen):** Zwei unabhängig zuschaltbare Auslöser – „Schlaf-Fenster folgt dem Dimmer" (Nicht stören ist an, während der Schicht-Dimmer dimmt, ohne eigene Zeiten pflegen zu müssen) und „Während der Dienstzeit" (Nicht stören ist an von Schichtbeginn bis Schichtende, laut Kalender-Termin). Einzelne Schichten lassen sich von „Während der Dienstzeit" ausnehmen, z. B. Rufbereitschaft.
- **Was genau stummgeschaltet wird, entscheidest du:** eigene Schalter für Anrufe, Nachrichten, Gespräche, Erinnerungen, Termine, System-Töne, Medien und Wecker anderer Apps. Medien (Musik/Podcasts) und fremde Wecker sind standardmäßig ausgenommen – eine laufende Wiedergabe bleibt unangetastet, außer du schaltest das bewusst ein.
- **Dein eigener Wecker klingelt immer** – unabhängig von all diesen Einstellungen.
- **Erreichbar bleiben im Notfall:** Wiederholte Anrufer (ein zweiter Anruf kurz nacheinander) kommen auch bei aktivem Nicht-stören durch.
- **Transparent statt überschreibend:** Die App legt eine eigene, für dich sichtbare Regel unter Einstellungen → Ton → Nicht stören → Zeitpläne an, statt deine manuellen Nicht-stören-Einstellungen stillschweigend zu überschreiben.
- Benötigt Android 11 oder neuer sowie eine einmalige Freigabe (Benachrichtigungszugriff), auf die beim ersten Aktivieren hingewiesen wird.

## Version 1.17.0 / 1.17.1

**Stand:** Juli 2026

_Nacht-Dimmen ohne eigene Regel, eine Vorschau-Zeitleiste für den Schicht-Dimmer und eine Hue-Bridge, die sich nach Netzwerkverlust von selbst wieder verbindet._

### ✨ Neu

- **Nacht-Standard (Schicht-Dimmer):** Dimmt ab einer festen Uhrzeit bis zum nächsten Wecker – ganz ohne dass dafür eine Schicht-Regel angelegt werden muss. Einzelne Schichten lassen sich per Antippen direkt an dieser Karte von der Nacht-Dimmung ausnehmen (z. B. Nachtdienst), ohne Umweg über eine leere Regel. Verdunkelung und Wärme sind für den Nacht-Standard eigens einstellbar, unabhängig von Wellness.
- **Dimm-Vorschau:** Ein neuer Vorschau-Bildschirm zeigt für die nächsten Tage konkret, wann tatsächlich gedimmt wird – berechnet aus der echten Konfiguration, keine Simulation im Kopf mehr nötig.
- **Vorschau-Knopf im Regel-Editor:** zeigt Stärke/Wärme einer Regel kurz an, auch bevor sie gespeichert ist.

### 🐛 Behoben

- **Hue-Bridge verband sich nach Netzwerkverlust nicht von selbst wieder:** War die Bridge unterwegs nicht erreichbar, zeigte die App das korrekt an – aber auch nach der Rückkehr ins Heim-WLAN blieb die Meldung stehen, bis manuell „Erneut versuchen" gedrückt wurde. Die App erkennt eine Netzwerk-Wiederherstellung jetzt selbst und verbindet sich automatisch neu.
- **Zurück-Pfeil im Dimmer- und im Hue-Regel-Editor** führte zum jeweiligen Haupt-Tab statt zurück zur Regel-Liste.

## Version 1.16.2

**Stand:** Juli 2026

_Neu: der Schicht-Dimmer – dunkelt den Bildschirm rund um deine Schichten automatisch ab, damit du leichter herunterfährst._

### ✨ Neu

- **Schicht-Dimmer (neuer Reiter „Dimmen"):** Legt vor deiner nächsten Schicht eine sanfte, warme Verdunkelung über den Bildschirm und blendet sie zur Weckzeit wieder auf – als Herunterfahr-Hilfe. Verdunkelung und Wärme (Amber, weniger Blaulicht) sind einstellbar. *Nutzt einen Android-Bedienungshilfen-Dienst ausschließlich zum Abdunkeln – er liest keine Bildschirminhalte und erfasst keine Eingaben; du aktivierst ihn selbst und kannst ihn jederzeit wieder abschalten.*
- **Zwei unabhängige Modi:** „Wellness" dunkelt eine einstellbare Weile vor jeder Weckzeit ab – ganz ohne Regeln. „Schicht-Regeln" dimmt nach frei definierbaren, an deine erkannten Schichten gekoppelten Regeln.
- **Regeln, die deine Schichten kennen:** Du kannst z. B. „jede Nacht von 22 bis 7 Uhr dimmen, außer in Nachtdienst-Nächten" einstellen – lückenlos jede Kalendernacht, und die Arbeitsnächte deiner Nachtdienste werden automatisch ausgenommen. Etwas, das ein gewöhnlicher Bildschirmdimmer ohne Kalenderwissen nicht kann. Jede Regel gilt für eine bestimmte Schicht (oder für freie Tage bzw. alle Schichten) und hat ein oder mehrere Zeitfenster.
- **Flexible Zeitfenster:** Fenster-Grenzen wahlweise als feste Uhrzeit, relativ zur Weckzeit oder relativ zum Schichtende (z. B. „ab Schichtende + 1 Stunde" für den Tagschlaf nach einer Nachtschicht). Verdunkelung und Wärme lassen sich zusätzlich pro Regel festlegen.
- **Dienst-Status im Status-Tab:** Eine neue Karte zeigt, ob der Bedienungshilfen-Dienst aktiv ist, und aktiviert ihn auf Wunsch.

## Version 1.15.2 (interne Alpha)

**Stand:** Juli 2026

_Ein irreführender Log-Eintrag beim Verlassen des Status-Tabs ist verschwunden._

### 🐛 Behoben

- **Falscher Fehler-Log beim „Nicht verwendete Apps"-Check:** Verließ man den Status-Tab, während im Hintergrund geprüft wurde, ob die Android-Einschränkung aktiv ist, wurde der dabei normale Abbruch der Prüfung fälschlich als Fehler geloggt (samt zweier Compose-interner Stacktraces). Funktional ohne Auswirkung – die App verhielt sich schon vorher korrekt –, aber im Log sah es nach einem echten Problem aus. Jetzt wird ein Abbruch als das erkannt, was er ist.

## Version 1.15.1

**Stand:** Juli 2026

_Der WLAN-Check vor dem Hue-Bridge-Zugriff erkennt jetzt auch ein falsches WLAN._

### 🐛 Behoben

- **Verbindungsversuch zur Hue-Bridge trotz falschem WLAN:** Der bestehende Schutz (v1.9.5) prüfte nur, ob überhaupt ein WLAN aktiv ist – in einem fremden WLAN (Arbeit, Hotspot, Gäste-Netz) galt das fälschlich als „erreichbar", und die App versuchte trotzdem, die Bridge zu kontaktieren und wartete den vollen 10-Sekunden-Timeout aus. Der Check vergleicht jetzt die tatsächliche Subnetzzugehörigkeit statt nur den Verbindungstyp.
- **Derselbe blinde Fleck bestand auch beim Wecker selbst:** Bei einer bereits zwischengespeicherten Bridge-Verbindung (der Normalfall dank 30-Minuten-Cache) griff der WLAN-Check bisher gar nicht – jetzt wird auch dort vorab geprüft, bevor ein echter Netzwerkzugriff versucht wird.

## Version 1.15.0

**Stand:** Juli 2026

_Zeitzonen-Fix, ein einheitlicher Herstellerhinweis und ein Aufräumen-Button für Debug-Logs._

### ✨ Neu

- **Alarme überleben jetzt einen Zeitzonen-Wechsel:** Bisher wurde die Weckzeit beim Stellen fest in der damals aktiven Zeitzone verankert – nach einem Zeitzonenwechsel (z. B. auf Reisen) klingelte der Wecker zur falschen Uhrzeit. Die App erkennt den Wechsel jetzt und berechnet die Alarme automatisch neu.
- **„Alte Logs löschen"-Button im Status-Tab:** löscht auf Wunsch alle Debug-Log-Dateien außer der von heute – die noch aktive, heutige Datei bleibt dabei immer erhalten.
- **App-Version steht jetzt direkt im Log:** jede neue Tagesdatei beginnt mit einer Kopfzeile, die App-Version und -Code festhält – auch wenn man die Datei ohne den „Senden"-Weg abruft.

### 🐛 Behoben

- **„Später" bei der Akku-Freigabe hielt nicht über einen Neustart:** Nach jedem App-Neustart kam derselbe Hinweis erneut, obwohl man ihn schon einmal übersprungen hatte.
- **Herstellerhinweis (Xiaomi/OnePlus/Huawei/…) kam an bis zu vier verschiedenen Stellen**, teils wiederholt, teils mit unterschiedlichem (mal generischem, mal konkretem) Text. Jetzt gibt es genau einen Hinweis, mit den echten, herstellerspezifischen Schritten, einmalig pro Gerätetyp.
- **Eine Weckzeit knapp nach Schichtbeginn** konnte fälschlich einen Tag zu früh wecken (die Logik für Nachtschichten kannte keine Obergrenze). Betraf in der Praxis nur ungewöhnlich knapp konfigurierte Schichten.
- Aufräumen der Debug-Logs lief bisher nur beim App-Kaltstart – bei durchgehend laufender App griff das kaum. Läuft jetzt zusätzlich alle 6 Stunden mit.

## Version 1.14.0

**Stand:** Juli 2026

_Neuer Onboarding-Schritt gegen einen Android-Mechanismus, der Wecker lautlos löschen kann._

### ✨ Neu

- **Hinweis auf „App bei Nichtnutzung pausieren":** Android kann Apps, die eine Weile nicht geöffnet wurden, per Force-Stop pausieren – dabei gehen alle bereits gesetzten Wecker-Alarme verloren, ohne jeden Hinweis. Für eine App, die bewusst nicht täglich geöffnet werden muss, ist genau das ein reales Risiko: live nachgewiesen als Ursache eines ausgebliebenen Weckers. Die App führt jetzt beim Einrichten aktiv zu der passenden Einstellung, genau wie schon bei der Akku-Ausnahme.
- **Neue Statuskarte „Nicht verwendete Apps":** im Status-Tab, direkt neben der Akku-Ausnahme – zeigt jederzeit, ob der Schalter aktuell ein Risiko darstellt, mit direktem Sprung zur Einstellung.

## Version 1.13.2

**Stand:** Juli 2026

_Der Hue-Tab sagt nicht mehr dreimal dasselbe, wenn die Bridge nicht erreichbar ist._

### 🐛 Behoben

- **Dreifacher Warnhinweis bei nicht erreichbarer Bridge:** Warst du unterwegs oder außerhalb deines WLANs, stapelten sich im Hue-Tab ein Warnbanner und die Statuskarte mit derselben Aussage – samt drei Warnsymbolen übereinander. Jetzt steht es einmal da: „Nicht verbunden", mit der IP-Adresse und dem Hinweis, dass Lichtaktionen für Alarme ausfallen könnten.
- **„Prüfen" war da, wo es nichts zu prüfen gab:** Der Knopf zum erneuten Verbindungsversuch erschien nur bei bereits verbundener Bridge – ausgerechnet bei „Nicht verbunden" fehlte er. Jetzt ist er immer erreichbar, sobald eine Bridge eingerichtet ist.
- **Statusanzeige aktualisiert sich von selbst:** Verlierst du bei geöffnetem Hue-Tab die Verbindung zur Bridge (z. B. beim Verlassen des WLANs), zeigt die Karte das jetzt sofort an, statt auf „Verbunden" stehen zu bleiben.

## Version 1.13.1 (interne Alpha)

**Stand:** Juli 2026

_Der Weck-Bildschirm soll bei gesperrtem Gerät jetzt zuverlässig oben bleiben._

### 🐛 Behoben

- **Vollbild-Wecker verschwand bei gesperrtem Gerät sofort wieder:** Auf dem gesperrten, dunklen Gerät kam der Weck-Bildschirm zwar hoch, wurde aber einen Sekundenbruchteil später wieder verdeckt – der Ton lief weiter, aber der Bildschirm ging nicht richtig an bzw. dozte gleich zurück. Ursache: Die App hielt zwar die CPU wach, aber nicht den Bildschirm selbst. Jetzt hält der Weckvorgang den Bildschirm aktiv hell, damit das Vollbild oben bleibt. (Falls der Weck-Bildschirm auf deinem Gerät weiterhin nicht dauerhaft erscheint, hilft ein Blick auf die „Vollbild-Wecker"-Karte im Status-Tab.)

## Version 1.13.0 (interne Alpha)

**Stand:** Juli 2026

_Schlummern geht jetzt auch direkt aus der Benachrichtigung – und der Weck-Bildschirm ist ruhiger gestaltet._

### ✨ Neu

- **„5 Min später" direkt in der Benachrichtigung:** Bisher gab es das Schlummern nur auf dem großen Weck-Bildschirm. Kommt der auf manchen Geräten am Sperrbildschirm nicht von selbst hoch, blieb als einziger Knopf „Wecker aus". Jetzt hat die Wecker-Benachrichtigung selbst zwei Knöpfe – „5 Min später" und „Wecker aus" – sodass du auch dann schlummern kannst, wenn nur die Benachrichtigung erscheint. Beide Wege legen denselben Schlummer-Wecker an.

### 🎨 Verbessert

- **Ruhigerer Weck-Bildschirm:** Der Vollbild-Wecker war komplett rot – das wirkte beim Aufwachen etwas alarmierend („als ob die Welt untergeht"). Jetzt ist der Hintergrund hell mit roten Akzenten: Wecker-Symbol, Schicht und der „Alarm stoppen"-Knopf bleiben klar rot, aber ohne die geflutete rote Fläche. Genauso eindeutig als Wecker erkennbar, nur angenehmer für die Augen um 5 Uhr früh.

## Version 1.12.0 (interne Alpha)

**Stand:** Juli 2026

_Der Status-Tab zeigt jetzt auf einen Blick, ob die App im Hintergrund laufen darf._

### ✨ Neu

- **Statuskarte „Akku-Ausnahme":** Damit die App auch nach Tagen ohne Öffnen zuverlässig weckt, muss Android sie von der Akku-Optimierung ausnehmen – sonst darf das System sie einfrieren, und dann werden keine neuen Schichten mehr abgeholt. Ob diese Ausnahme aktiv ist, stand bisher nur an einer Stelle, die verschwand, sobald alles in Ordnung war. Der Status-Tab zeigt es jetzt dauerhaft – grün mit Haken, wenn alles passt (direkt neben dem „Vollbild-Wecker"), und rot mit einem Knopf „Ausnahme erlauben", falls nicht. So siehst du den einen wirklich kritischen Punkt für die Dauer-Zuverlässigkeit jederzeit auf einen Blick, ohne in die Android-Einstellungen zu müssen.

## Version 1.11.6 (interne Alpha)

**Stand:** Juli 2026

_Beim Öffnen zeigt der Startbildschirm jetzt „Wird geladen …“ statt kurz „nichts gefunden“._

### ✨ Verbessert

- **Kein irritierendes Aufblitzen mehr beim Öffnen:** Die App gleicht bei jedem Öffnen deinen Kalender frisch mit Google ab. In dem Sekundenbruchteil, bis die Termine da sind, stand auf der Startseite unter „Nächste Schicht“ und „Alarm-Status“ kurz ein leerer bzw. warnend wirkender Zustand – obwohl nichts fehlte, die Daten waren nur noch unterwegs. Jetzt steht dort während dieser kurzen Zeit ein neutrales „Wird geladen …“, das sich dann in die erkannte Schicht und die aktiven Wecker auflöst. Rein optisch – an den Weckern selbst ändert sich nichts.

## Version 1.11.5 (interne Alpha)

**Stand:** Juli 2026

_Das Regel-Formular ist aufgeräumter, und die Bridge-Suche zeigt sich nicht mehr doppelt._

### ✨ Verbessert

- **Einschalten und Sonnenaufgang stehen jetzt gleichrangig nebeneinander:** Im Regel-Formular überschrieb ein Titel „Aktionskonfiguration“ die Karte fürs normale Einschalten – während der Sonnenaufgangs-Lichtwecker als eigene Karte daneben stand. Das wirkte, als sei das eine der Oberbegriff und das andere ein Anhängsel, obwohl es zwei gleichwertige Wege sind, wie eine Regel das Licht ansteuert. Jetzt sind es zwei schlichte, gleichrangige Karten: „Einschalten“ und „Sunrise-Lichtwecker“. Schaltest du den Sonnenaufgang ein, verschwindet die Einschalt-Karte ganz (er bestimmt dann Farbe und Helligkeit selbst), statt als leere Hinweiskarte stehen zu bleiben.
- **Die Bridge-Suche erscheint während des Suchens nicht mehr doppelt:** Solange die App nach einer Hue-Bridge suchte, zeigte sie den laufenden „Netzwerk-Scan“ – und direkt darunter trotzdem noch die Karte „Bridge-Suche“ mit dem Knopf zum Starten einer Suche. Einen zweiten Suchlauf anzustoßen, während der erste läuft, ergibt keinen Sinn. Die Start-Karte blendet sich jetzt aus, solange gesucht wird, und kommt zurück, sobald der Lauf fertig ist – bei einem Treffer mit der gefundenen Bridge, ohne Treffer mit dem Knopf für einen neuen Versuch.

## Version 1.11.4 (interne Alpha)

**Stand:** Juli 2026

_Das automatische Ausschalten hat jetzt einen eigenen, klaren Platz im Regel-Formular._

### ✨ Verbessert

- **„Automatisch ausschalten“ ist jetzt eine eigene Einstellung:** Bisher steckte der Schalter mitten in der Karte fürs normale Einschalten – dabei gilt das Ausschalten genauso für den Sonnenaufgangs-Lichtwecker. Das war missverständlich: Es sah aus, als gehörte es nur zum einfachen Anschalten. Jetzt steht „Automatisch ausschalten“ als eigener Abschnitt unter beiden Möglichkeiten und gilt sichtbar für beide – egal ob die Regel das Licht direkt anschaltet oder sanft hochdimmt. Ein kurzer Hinweis sagt außerdem, ab wann die Zeit läuft (ab der Weckzeit bzw. ab dem Ende des Sonnenaufgangs).

## Version 1.11.2 (interne Alpha)

**Stand:** Juli 2026

_Der Lampentest reagiert jetzt sofort – und blinkt kurz statt eine gefühlte Ewigkeit._

### 🐛 Behoben

- **Der erste Klick auf „Test“ tat nichts:** Direkt nach dem Verbinden mit der Bridge passierte beim ersten Antippen des Test-Knopfes gar nichts – erst der zweite Klick ließ die Lampen blinken. Grund: Die App stieß das Laden der Lampenliste an, wartete aber nicht darauf und schaute sofort in die noch leere Liste. Der erste Klick lud also nur nach, ohne etwas zu tun. Jetzt wartet der Test auf die Liste und blinkt schon beim ersten Antippen.
- **Der Lampentest blinkt jetzt kurz statt 15 Sekunden:** Das Blinksignal der Bridge läuft von sich aus eine Viertelminute – deutlich zu lang für eine Rückmeldung, auf die man wartet. Der Test beendet es jetzt nach etwa vier Sekunden von selbst.
- **Wirklich nur ein Blinken pro Lampe:** In 1.11.1 war der doppelte Blitz nur halb behoben. Lampen können gleichzeitig in mehreren Gruppen liegen (eine Deckenlampe etwa in „Wohnzimmer“, „Deckenlampe“ und „Zuhause“) – über die Gruppen zu blinken traf dieselbe Lampe daher mehrfach. Der Test spricht jetzt direkt die Lampen an, wodurch jede genau ein Signal bekommt, egal wie die Gruppen geschnitten sind.

## Version 1.11.1 (interne Alpha)

**Stand:** Juli 2026

_Die Zurück-Taste tut endlich das, was sie überall sonst tut – und drei Merkwürdigkeiten im Hue-Bereich sind weg._

### 🐛 Behoben

- **Die Zurück-Taste führt zurück, statt die App zu schließen:** Wer in den Kalender-Events, der Kalender-Auswahl oder der Schicht-Konfiguration die Zurück-Taste drückte, landete unvermittelt auf dem Android-Startbildschirm – die App war zu. Der Pfeil oben links war der einzige Weg zurück. Jetzt führt Zurück eine Ebene höher: aus einem Unterbildschirm zurück in den Bereich, aus dem du gekommen bist, und aus Status, Einstellungen oder Hue zurück auf Home. Nur auf Home schließt Zurück weiterhin die App – so wie man es erwartet.
- **Der Lampentest blinkt jetzt in einem Stück:** Der Test ließ die Lampen kurz aufleuchten, dann kam eine Pause, und ein, zwei Sekunden später fing das Blinken an. Der Grund: Die App sprach jede Lampe doppelt an – einmal über ihre Gruppe und dann noch einmal einzeln. Da jeder Befehl einzeln zur Bridge geht, kamen die Signale zeitversetzt an. Jetzt wird jede Lampe genau einmal angesprochen, und sie blinken gemeinsam für ein paar Sekunden.
- **Die Regel-Vorschau lässt das Licht nicht mehr an:** Der „Regel testen“-Knopf schaltete das Licht ein – und ließ es an, wenn die Regel kein automatisches Ausschalten eingestellt hatte (bei einer neuen Regel ist das der Normalfall). Ausschalten ging dann nur noch über die Hue-App. Die Vorschau räumt jetzt immer hinter sich auf: Licht an, kurz hinschauen, Licht wieder aus. Der Hinweis auf dem Bildschirm sagt dazu, ob das Ausschalten zur Regel gehört oder nur zum Test – deine echte Regel bleibt unverändert.
- **Sonnenaufgang in der Vorschau wird nicht mehr abgewürgt:** Beim Testen einer Sonnenaufgangs-Regel ging das Licht mitten im Aufblenden wieder aus, statt die Rampe zu Ende laufen zu lassen.
- **Weniger Wiederholung bei der Bridge-Suche:** Über der laufenden Bridge-Suche stand weiterhin „Noch keine Bridge eingerichtet – suche unten nach deiner Hue-Bridge“, obwohl genau das gerade passierte. Die Karte verschwindet jetzt, sobald die Suche losgeht, und die gefundene Bridge steht im Mittelpunkt. War schon einmal eine Bridge verbunden, bleibt der Status sichtbar – dann ist „nicht verbunden“ eine echte Information.

## Version 1.11.0 (interne Alpha)

**Stand:** Juli 2026

_Das Auto-Aus fürs Licht liegt jetzt auf der Hue Bridge – es funktioniert damit auch dann, wenn du längst aus dem Haus bist._

### ✨ Neu

- **Die Lampen gehen auch aus, wenn du nicht da bist:** Bisher musste das Handy das Ausschalten selbst erledigen – und erreichte die Bridge nur aus dem heimischen WLAN. Wer nach dem Wecken direkt zur Arbeit ging, nahm das Handy mit, und die Lampen brannten weiter. Jetzt bekommt die Bridge zur Weckzeit den Auftrag „in 30 Minuten ausschalten“ direkt mitgeteilt und führt ihn selbst aus. Wo das Handy dann ist, spielt keine Rolle mehr.
- **Kein verspätetes Ausschalten mehr:** Der frühere Notbehelf versuchte es bis zu zweieinhalb Stunden lang immer wieder – im ungünstigen Fall gingen die Lampen erst mittags aus, während jemand im Raum saß. Das entfällt: Die Bridge schaltet zur richtigen Zeit oder gar nicht.

### 🐛 Behoben

- **Regel-Formular: die ganze Zeile ist antippbar:** Beim Anlegen einer Licht-Regel reagierte nur der kleine Auswahlkreis links – ein Tipp auf „S2“ oder auf einen Gruppennamen tat schlicht nichts. Jetzt trifft die ganze Zeile, und Screenreader lesen sie als ein zusammenhängendes Bedienelement statt als namenlosen Knopf neben losem Text.
- **Licht-Regeln liefen für die falsche Schicht – oder gar nicht:** Beim Wecken suchte die App die passende Schicht anhand ihrer Kürzel und nahm den ersten Treffer. Weil die Spätschicht das Kürzel „S“ trägt und dieser eine Buchstabe in fast jedem Schichtnamen steckt („S2“, „Nacht*s*chicht“, „Zwi*s*chendienst“), gewann die Spätschicht praktisch immer. Eine Regel für S2 wurde also nie ausgeführt, und eine Regel für die Spätschicht schaltete bei jeder dieser Schichten das Licht an – die falschen Lampen zur falschen Zeit. Zuverlässig war nur die Frühschicht, und das auch nur, weil sie zufällig an erster Stelle steht. Die Schicht wird jetzt exakt zugeordnet.

## Version 1.10.5 (interne Alpha)

**Stand:** Juli 2026

_Der Akku-Hinweis beim Einrichten erklärte einen Ablauf, den es gar nicht gibt._

### 🐛 Behoben

- **Akku-Hinweis beschrieb den falschen Weg:** Der Bildschirm zeigte eine vierstufige Anleitung („Einstellungen öffnen sich“ → „CF Alarm in Liste finden“ → „App antippen“ → „Uneingeschränkt wählen“) und einen Knopf „Zu Einstellungen“. Tatsächlich öffnen sich gar keine Einstellungen – Android stellt schlicht eine Frage, die man mit einem Tipp beantwortet. Der Ablauf war also nie zu sehen. Der Bildschirm sagt jetzt, was wirklich passiert; der Weg selbst bleibt der bequeme Ein-Tipp-Weg.
- **Weniger Erklärung, dafür die richtige:** Der Kernpunkt stand nirgends und wurde stattdessen dreifach umschrieben. Jetzt steht er einmal und deutlich: Ohne die Freigabe darf Android die App einfrieren – dann werden keine neuen Schichten mehr abgeholt und der Wecker bleibt still. Wer mehr wissen will, tippt auf „Warum ist das nötig?“.
- **Kein Dialog-Stapel mehr auf OEM-Geräten:** Wer den Hersteller-Hinweis (OnePlus, Xiaomi & Co.) wegtippte, bekam sofort den nächsten Erklär-Dialog vorgesetzt.

## Version 1.10.4 (interne Alpha)

**Stand:** Juli 2026

_Zwei stille Fehler aus dem Geräte-Log: doppelte Arbeit beim App-Start, und eine Regel-Prüfung, die nie etwas geprüft hat._

### 🐛 Behoben

- **Hue-Verwaltung startete doppelt:** Beim App-Start richtete sich die Bridge-Verwaltung zweimal ein und verwarf dabei die gerade angelegten Hintergrund-Aufträge, um sie sofort neu anzulegen – vier Licht-Prüfungen wurden binnen zwei Sekunden dreimal umgeplant. Kein sichtbarer Schaden, aber unnötige Arbeit und unnötiger Akkuverbrauch bei jedem Start. Jetzt richtet sie sich genau einmal ein.
- **Regel-Prüfung ließ ungültige Regeln durch:** Beim Speichern einer Hue-Regel meldete die Prüfung intern „ungültig“, die Regel wurde aber trotzdem gespeichert – die Abfrage sah an der falschen Stelle nach. Die Prüfung greift jetzt wirklich, und wenn sie ablehnt, steht auch der Grund dabei statt eines nichtssagenden „Validation failed“.
- **Kurze Regelnamen sind ausdrücklich erlaubt:** Intern galt eine Mindestlänge von drei Zeichen, die nie wirksam war. Mit der reparierten Prüfung hätte sie schlagartig gegriffen – und bestehende Regeln mit kurzen Namen wie „FS“ wären weder speicher- noch bearbeitbar gewesen. Die Bedingung lautet jetzt schlicht: Der Name darf nicht leer sein.

## Version 1.10.3 (interne Alpha)

**Stand:** Juli 2026

_Die Hintergrund-Wartung startete nach dem Anmelden doppelt und kam sich dabei selbst in die Quere._

### 🐛 Behoben

- **Wartung lief nach dem Anmelden doppelt:** Die Rückmeldung der Kalender-Freigabe wurde zweimal ausgelöst, wodurch der Wartungsdienst zweimal startete. Zwei Durchläufe liefen dann gleichzeitig – und der erste, der fertig wurde, beendete den Dienst und brach den anderen mitten in der Arbeit ab. Diesmal folgenlos, weil gerade nichts zu tun war; hätte der abgebrochene Durchlauf gerade Alarme gesetzt, wären sie verloren gewesen. Die Rückmeldung kommt jetzt genau einmal, und der Dienst räumt sich erst ab, wenn wirklich alle Durchläufe fertig sind.

## Version 1.10.2 (interne Alpha)

**Stand:** Juli 2026

_„Wiederholen“ und „Aktualisieren“ tun jetzt wirklich etwas Sichtbares._

### 🐛 Behoben

- **„Wiederholen“ nach einem Ladefehler blieb wirkungslos:** Der Knopf in der Fehlermeldung lud die Termine zwar neu, aktualisierte aber eine andere Stelle als die, die der Start-Bildschirm anzeigt – dort änderte sich nichts. Schlug der zweite Versuch ebenfalls fehl, wurde das nicht einmal gemeldet: Man landete stumm im selben Zustand wie vorher.
- **Der „Aktualisieren“-Knopf auf dem Start-Bildschirm ebenfalls:** Gleiche Ursache. Er lud im Hintergrund tatsächlich Termine (die Schichterkennung bekam sie auch), zeigte aber weder Ladeanzeige noch die neue Terminliste noch einen Fehler – er wirkte schlicht tot.

## Version 1.10.1 (interne Alpha)

**Stand:** Juli 2026

_Aufgeräumte Hue-Oberfläche: keine zerrissenen Wörter und keine Schalter mehr am Kartenrand. Und „Abmelden“ meldet jetzt wirklich ab._

### 🐛 Behoben

- **„Abmelden“ ließ den Kalender-Zugriff zurück:** Beim Abmelden wurden zwar die Anmeldedaten gelöscht, die Kalender-Freigabe selbst blieb aber bestehen. Die App konnte dadurch im Hintergrund weiter den Kalender des abgemeldeten Kontos lesen – und wer sich danach mit einem *anderen* Google-Konto anmeldete, wurde kurzzeitig noch mit den Daten des alten Kontos bedient. Abmelden räumt jetzt beides ab.
- **Schalter klebten am Text und verschwanden hinter dem Kartenrand:** Betraf mehrere Stellen in den Hue-Regeln (u. a. „Regel aktivieren“, „Einschalten“, „Automatisch ausschalten“, „Sunrise-Lichtwecker“). Die Beschreibungstexte beanspruchten die gesamte Breite und drängten den Schalter aus dem Bild.
- **Zerrissene Beschriftungen:** „Bearbeiten“ wurde zu „Bea/rbei/ten“, „Einstellungen“ zu „Einstell/ungen“, „Farbe“ zu „Farb/e“, und „Erste Hue-Regel erstellen“ sowie „Regel testen“ brachen unnötig um. Die Knöpfe hatten schlicht zu wenig Platz für ihre eigene Schrift.
- **Farbauswahl lief über den Rand:** Die Farbfelder waren fest auf vier pro Zeile eingestellt – zu viele für die verfügbare Breite. Sie ordnen sich jetzt nach dem tatsächlich vorhandenen Platz und passen sich auch großer Systemschrift an.

### ✨ Neu & Verbessert

- **Kein Fehler-Rot mehr vor der Ersteinrichtung:** Der Hue-Tab meldete „Nicht verbunden“ mit rotem Fehlersymbol, bevor man überhaupt die Gelegenheit hatte, eine Bridge einzurichten. Jetzt steht dort neutral, dass noch keine Bridge eingerichtet ist – samt Hinweis, was als Nächstes zu tun ist. Rot bleibt echten Problemen vorbehalten.
- **Klarere Bridge-Suche:** Vor der Suche gibt es genau eine Schaltfläche. Ist eine Bridge gefunden, steht sie im Mittelpunkt und die Suche rückt als „Erneut suchen“ in den Hintergrund. Der frühere „Löschen“-Knopf ist entfallen – er warf nur die Trefferliste weg, was eine neue Suche ohnehin tut, klang aber, als würde er die Bridge oder die Regeln entfernen.

## Version 1.10.0 (interne Alpha)

**Stand:** Juli 2026

_Verliert die App den Kalender-Zugriff, meldet sie sich von selbst zurück – statt still darauf zu warten, dass du es merkst._

### ✨ Neu & Verbessert

- **Kalender-Zugriff meldet sich von selbst zurück:** Entzieht Google der App den Zugriff (z. B. weil die Freigabe abgelaufen ist), fragt die App jetzt von sich aus nach der Zustimmung – der Dialog kommt automatisch. Bisher warf sie die tote Freigabe zwar korrekt weg, sagte aber nichts: Der Wecker-Bildschirm sah normal aus, während im Hintergrund keine Schichten mehr gelesen werden konnten. Den Weg zurück musste man selbst finden und antippen.
- **Passiert der Verlust, während die App zu ist,** wartet die Nachfrage, bis du die App das nächste Mal öffnest – sie geht nicht verloren.
- **Du behältst die Kontrolle:** Brichst du den Zustimmungsdialog ab, landest du auf dem Bildschirm „Kalender-Zugriff erforderlich“ und entscheidest per Knopf selbst, wann du es erneut versuchst. Die App fragt nicht in einer Schleife nach.

## Version 1.9.8 (interne Alpha)

**Stand:** Juli 2026

_Großer Aufräumer beim Wecken: nur noch ein Weckton statt zwei, ein einziger Stopp-Weg, laufende Medien pausieren, und die Lampen gehen endlich wieder aus._

### 🐛 Behoben

- **Nur noch ein Wecker statt zwei:** Der Alarm klingelte doppelt und belegte zwei Einträge in der Benachrichtigungsleiste. Grund: Zwei unabhängige Tonquellen liefen parallel. Es gibt jetzt genau eine Benachrichtigung und einen Ton.
- **„Deine Schicht beginnt um …“ ist wieder vollständig:** Die Uhrzeit fehlte in der Benachrichtigung komplett, weil sie unter einem anderen Namen abgelegt als abgeholt wurde.
- **Wecker nur noch einmal stoppen:** Bisher musste man ihn zweimal ausschalten – einmal in der Benachrichtigung, danach noch einmal im Weck-Bildschirm. Der Weck-Bildschirm schließt sich jetzt von selbst, sobald der Wecker aus ist.
- **Podcast und Musik pausieren während des Weckers:** Laufende Medien redeten bisher einfach über den Wecker hinweg. Sie werden jetzt pausiert und laufen nach dem Ausschalten oder Schlummern automatisch weiter.
- **Lampen gehen nach dem Wecken wieder aus:** Bisher blieben sie an – teils den ganzen Tag. Zwei Ursachen: Der Ausschalt-Auftrag galt schon nach einem einzigen Versuch als erledigt (auch wenn die Bridge gar nicht erreichbar war, etwa unterwegs), und er wurde außerdem versehentlich gelöscht, sobald der Wecker geklingelt hatte. Beides behoben; bei unerreichbarer Bridge wird jetzt rund 2,5 Stunden lang erneut versucht.
- **Erneute Anmeldung funktioniert wieder:** Wer der App im Google-Konto den Zugriff entzogen hatte, kam nicht mehr zurück – die App verlangte eine Neuanmeldung, bot aber keinen Weg dorthin. Half nur noch eine Neuinstallation. Jetzt führt die App sauber in den Anmelde-Vorgang zurück.
- **Kalender-Zugriff erholt sich selbst:** Lehnte Google den Zugriff ab (abgelaufene Freigabe oder fehlende Kalender-Berechtigung), blieb die App daran hängen und lud endlos nach. Sie erkennt beide Fälle jetzt, verwirft die alte Freigabe und fragt sauber neu nach. Hintergrund: Ein Teil der Anmeldedaten liegt in den Google-Play-Diensten und überlebt sogar eine Neuinstallation der App – deshalb half selbst das Neuinstallieren nicht.
- **Falsche Meldung „Kein Google-Konto gefunden“:** Beim ersten Anmeldeversuch nach einer Neuinstallation behauptete die App, es sei kein Google-Konto vorhanden – ein zweiter Tipp fand es dann sofort. Der erste Versuch wird jetzt automatisch wiederholt.
- **Bestätigung für übersprungene Alarme erscheint wieder:** Sie wurde bisher stillschweigend verworfen und war nie zu sehen.
- **Schlummern ist zuverlässiger:** Ein geschlummerter Wecker konnte im Hintergrund abgeräumt werden, ohne je wieder zu klingeln.
- **Hue-Bridge wird jetzt zuverlässig gefunden:** Die App fand die Bridge im eigenen WLAN oft nicht, obwohl sie erreichbar war – ein interner Fehler bei der lokalen Suche warf jeden Fund weg. Betroffen war insbesondere, wer den Online-Suchdienst von Philips nicht erreichen konnte (z. B. durch Netzwerk-/DNS-Einstellungen).
- **Veraltete Hintergrund-Aufträge werden jetzt korrekt aufgeräumt:** Nach Änderungen an den Alarmen blieben teils überzählige Hue-Prüfaufträge im Hintergrund aktiv, statt gelöscht zu werden.

### ✨ Neu & Verbessert

- **Hue-Bridge-Suche deutlich schneller:** Von bis zu 20 Sekunden auf rund 1–2 Sekunden, indem zuerst im eigenen Netzwerk gesucht wird statt zuerst online.
- **Weck-Bildschirm im Corporate Design:** Er war als einziger Bildschirm der App noch im alten Standard-Blau. Jetzt passt er zum Rest.
- **Warnung „Vollbild-Wecker nicht erlaubt“:** Neue Status-Karte, die meldet, wenn Android der App die Vollbild-Anzeige entzogen hat – dann erscheint der Wecker nämlich nur als Banner und der Weck-Bildschirm kommt nicht von selbst hoch. Ein Knopf führt direkt in die passende Systemeinstellung. *Hinweis: Solange das Handy entsperrt und in Benutzung ist, zeigt Android absichtlich nur ein Banner – das ist normal. Der Vollbild-Wecker ist für das gesperrte Gerät gedacht.*
- **Weck-Bildschirm zeigt die Systemleisten korrekt aus:** Ein interner Fehler verhinderte das bisher bei jedem Alarm.

## Version 1.9.7 (interne Alpha)

**Stand:** Juli 2026

_Zuverlässigerer Kalender-Login und ein frisches App-Logo._

### 🐛 Behoben

- **Kalenderfreigabe schließt jetzt zuverlässig ab:** Nach dem Erteilen der Kalender-Berechtigung wird der Zugriff jetzt korrekt hergestellt und gespeichert. Vorher konnte es passieren, dass die Freigabe zwar bestätigt war, die App danach aber keinen gültigen Zugriff hatte („No token available").

### ✨ Neu

- **App-Logo im Anmelde-Bildschirm.**

## Version 1.9.5 (interne Alpha)

**Stand:** Juli 2026

_Optimierung der Hintergrundprozesse: Zuverlässigere Hue-Anbindung im heimischen WLAN, verbesserte Warnungen bei fehlender Kalender-Erlaubnis und ein neues, langfristiges Protokollsystem für die Fehleranalyse._

### ✨ Verbessert

- **Batterieschonende Hue-Verbindung:** Die App prüft nun im Hintergrund zuerst, ob du dich in einem WLAN befindest, bevor sie versucht, die Hue-Bridge zu erreichen. Unterwegs im Mobilfunknetz wird der Versuch sofort übersprungen, was Akku spart und unnötige Fehlermeldungen reduziert.
- **Erweiterte Diagnose-Protokolle (8 Tage):** Die App speichert Fehlerprotokolle jetzt tagesweise und hebt diese für eine volle Woche (8 Tage) auf, bevor sie automatisch bereinigt werden. Beim Versand an den Support werden automatisch alle Protokolle der letzten Woche angehängt. So können Hintergrundfehler über längere Zeiträume sauber nachvollzogen werden.
- **Aktualisierte Status-Warnungen:** Falls im Hintergrund festgestellt wird, dass keine Kalender ausgewählt sind, wird dies nun übersichtlicher in den Protokollen festgehalten.

## Version 1.9.4 (interne Alpha)

**Stand:** Juli 2026

_Aufgeräumte Oberfläche: Status- und Einstellungs-Bereich neu sortiert, der manuelle Wecker wandert in ein aufklappbares Fenster, und die Sync-Anzeige stimmt jetzt._

### ✨ Verbessert

- **Manueller Wecker aufgeräumt:** Der manuelle Wecker liegt nicht mehr fest auf der Startseite, sondern öffnet sich über den „＋ Manueller Alarm"-Knopf unten rechts in einem eigenen Fenster. Die Startseite bleibt so auf das Wesentliche fokussiert (nächste Schicht, Wecker-Status, Termine).
- **„Letzter Sync" stimmt jetzt:** Die Anzeige im Status-Bereich zeigt jetzt den tatsächlich letzten Abgleich – auch wenn du die App nur geöffnet/aktualisiert hast, nicht nur die 6-Stunden-Hintergrundprüfung. Ein alter Wert bleibt weiterhin ein ehrliches Warnsignal, falls länger gar nichts synchronisiert wurde.
- **Übersichtlicher Status-Bereich:** „Letzter Sync" und das Senden von Diagnose-Protokollen sind jetzt gebündelt im Status-Bereich (vorher teils doppelt und in den Einstellungen).
- **Kleinere Feinschliffe:** Reiter-Beschriftungen brechen nicht mehr um; der neue Knopf verdeckt keine Inhalte mehr.

### 🔒 Datenschutz

- Beim Senden von Diagnose-Protokollen erscheint jetzt ein kurzer Hinweis, dass die Protokolle Diagnosedaten enthalten; die E-Mail ist an die offizielle Support-Adresse (cfischer@csj.de) vorausgefüllt, alternativ lässt sich jede andere App wählen.

## Version 1.9.3 (interne Alpha)

**Stand:** Juli 2026

_Stabilitäts- und Wartungs-Release: die Wecker-Synchronisation läuft jetzt über einen einzigen, gegen gleichzeitige Zugriffe abgesicherten Pfad. Dazu ein großer interner Aufräumschritt und mehr automatische Tests. Keine sichtbaren Funktionsänderungen._

### 🐛 Verbessert

- **Zuverlässigere Wecker-Synchronisation:** Kalender-Aktualisierung, Hintergrund-Wartung und Schicht-Änderungen laufen jetzt durch ein und denselben, gegen gleichzeitige Zugriffe abgesicherten Sync-Vorgang. Das beseitigt Wettlauf-Situationen und doppeltes Setzen von Alarmen und macht das Erstellen, Aktualisieren und Löschen von Weckern robuster.

### 🧰 Intern

- Großer Aufräumschritt: nicht erreichbarer Code entfernt (u. a. ein totes Lifecycle-Cleanup, ein ungenutzter Anmelde-Datenkanal, ein komplettes ungenutztes Hue-Lichtdauer-Modul und toter Kalender-Zwischenzustand).
- Technik vereinheitlicht: zentrale Hue-Komponenten werden nun einheitlich über die Dependency-Injection bezogen.
- Neue automatische Tests für die Wecker-Überspringen-Logik und die Wecker-Speicherung.

## Version 1.9.2 (interne Alpha)

**Stand:** Juli 2026

### 🐛 Verbessert

- **Benachrichtigungs-Abfrage zum richtigen Zeitpunkt:** Die Nachfrage nach der Benachrichtigungs-Berechtigung erscheint jetzt erst, wenn du im Hauptbereich der App angekommen bist – nicht mehr direkt beim allerersten Start vor der Anmeldung.
- **Datenschutzerklärung präzisiert:** Angaben zur Speicherung von Kalenderdaten an das tatsächliche Verhalten der App angeglichen (nur kurzzeitiger Zwischenspeicher; dauerhaft gespeichert werden nur die Weckzeiten, nicht die Termininhalte).

## Version 1.9.1 (interne Alpha)

**Stand:** Juli 2026

_Kleine Verbesserungen für die Testphase: sichtbare Fehlermeldungen, flüssigeres Onboarding, mehr Datenschutz in den Protokollen._

### 🐛 Behoben & verbessert

- **Fehler werden jetzt angezeigt statt verschluckt:** Probleme beim Laden von Kalender, Schichten oder Weckern erscheinen als kurze Einblendung (mit „Wiederholen") – vorher blieb der Bildschirm kommentarlos leer.
- **Akku-Freigabe überspringbar:** Im Einrichtungs-Assistenten lässt sich der Akku-Schritt jetzt mit „Später" überspringen – man kommt nicht mehr in einer Sackgasse fest. Der Hinweis bleibt in den Einstellungen erhalten.
- **Mehr Datenschutz in den Protokollen:** In der veröffentlichten App landen keine sensiblen Inhalte (E-Mail-Adresse, Termintitel) mehr in der Log-Datei – nur noch Warnungen und Fehler für die Diagnose.

### 🧰 Intern

- Automatische Qualitätsprüfung (Tests, Lint, Build) bei jeder Änderung eingerichtet.

## Version 1.9.0 (interne Alpha)

**Stand:** Juli 2026

_Design-Release: Die App erscheint jetzt durchgängig im Corporate Design – klare weiße Karten, Markenrot als Akzent, einheitliche Status-Farben._

### ✨ Neu

- **Neues Erscheinungsbild (Corporate Design):** Durchgängige Marken-Farbwelt (Rot / Off-White / Anthrazit) statt der bisherigen Standard-Optik. Überschriften in der Hausschrift „Mulish", Fließtext in Roboto.
- **Klar abgesetzte weiße Karten:** Inhalte stehen als eigenständige, weiße Karten auf ruhigem Hintergrund – übersichtlicher als die bisherigen getönten Flächen.
- **Einheitliche Status-Farben (Ampel-Logik):** Grün = alles in Ordnung, Rot = Fehler, dezente Akzente für Hinweise. Der Status-Bildschirm markiert nicht mehr fälschlich alles als Warnung.
- **Alarm-Vollbild in Markenrot:** Der Weckbildschirm ist dringlich und zugleich wiedererkennbar.

### 🎨 Feinschliff

- Kopfzeile, Navigation und Einstellungen an das neue Design angepasst.
- Philips-Hue-Bereich (inkl. „Erfolgreich verbunden") auf den neuen Karten-Stil umgestellt.

## Version 1.8.0 (interne Alpha)

**Stand:** Juli 2026

_Zuverlässigkeits-Release: Der Wecker klingelt jetzt auch in kritischen Situationen rund um Neustart, Sperrbildschirm und Bedienung._

### ✨ Neu

- **Wecker nach Neustart – auch im gesperrten Zustand:** Startet das Handy nachts neu (System-Update, Absturz, leerer Akku) und wird nicht mehr entsperrt, klingelt der Wecker trotzdem. Die Weckzeiten werden dafür in einem geschützten Speicher vorgehalten, der schon vor dem ersten Entsperren verfügbar ist.

### 🐛 Behoben (Zuverlässigkeit)

- **Wecker überlebt Neustart & App-Update zuverlässig:** Ein Fehler in der Neustart-Erkennung konnte dazu führen, dass nach einem Reboot oder Update keine Wecker wiederhergestellt wurden.
- **Weckton lässt sich nicht mehr versehentlich abwürgen:** Ein Druck auf die Power-Taste, ein eingehender Anruf oder das Wegwischen aus den letzten Apps beenden den Wecker nicht mehr – er läuft, bis bewusst „Stoppen" oder „Snooze" gewählt wird. Neuer „Wecker aus"-Knopf direkt in der Benachrichtigung.
- **Bestehende Wecker gehen nicht mehr verloren:** Beim automatischen Hintergrund-Abgleich und beim Öffnen des Kalenders konnten andere – auch manuell angelegte – Wecker gelöscht werden.
- **Kein Wecker-Verlust ohne Internet nach Neustart:** Ließen sich die Kalenderdaten direkt nach dem Neustart nicht laden, wurden gespeicherte Wecker fälschlich gelöscht statt wiederhergestellt.

### 🧰 Für Tester / Aufräumen

- Nicht genutzte Berechtigungen entfernt („Über anderen Apps einblenden", „Sperrbildschirm deaktivieren") – schlanker und Play-Store-freundlicher.
- Veralteten UI-Test entfernt.

## Version 1.7.0 (interne Alpha)

**Stand:** Juli 2026

### ✨ Neu

- **Philips Hue – Sicherheit:** Bridge-ID-Pinning (Trust-On-First-Use) zusätzlich zur TLS-Validierung
- **Philips Hue – Bedienung:** „Test" lässt die Lampen kurz blinken; „Verbindung trennen / Bridge vergessen"; Warn-Banner bei Verbindungsverlust
- **Regel-Test:** Vorschau führt Auto-Aus und Sonnenaufgang verkürzt vor (mit Hinweis); Auto-Aus jetzt auch für Sonnenaufgangs-Regeln
- **Datenspeicherung:** interne Konsolidierung auf DataStore

### 🐛 Behoben

- „Erfolgreich verbunden"-Karte zeigte fälschlich gleichzeitig ein Fehler-Banner
- Diagnose-Protokolle: keine sensiblen Daten (E-Mail/Token) mehr in Release-Logs

### 🧰 Für Tester

- Neuer Absturz-Handler + „Logs senden / Problem melden" in den Einstellungen

## Version 1.6.1

**Stand:** 2026

### ✨ Wesentliche Änderungen seit 1.0.x

- **Architektur:** Migration auf Hilt Dependency Injection (Clean Architecture + MVVM)
- **Zuverlässigkeit:** Exact-Alarm-basierte 6-Stunden-Wartung, gehärteter AlarmReceiver
- **Sicherheit:** Verschlüsselte Token-Speicherung (AES-256-GCM via Android Keystore / Tink)
- **Philips Hue:** Sonnenaufgangs-Simulation an echte Weckzeiten gekoppelt
- **Vorbereitung des internen Alpha-Tests**

_Hinweis: Die folgenden Einträge (1.0.x) dokumentieren frühe Entwicklungsstände._

## Version 1.0.4

**Release Datum:** August 2025

### ✨ Neue Features

- **Verbesserte UI:** Material Design 3 Integration
- **Philips Hue:** Erweiterte Szenen-Unterstützung
- **Sicherheit:** Enhanced OAuth 2.0 Implementation

### 🐛 Bug Fixes

- **Calendar Sync:** Bessere Fehlerbehandlung bei Netzwerkproblemen
- **Alarm Logic:** Korrektur der Zeitzonenbehandlung
- **Performance:** Reduzierter Batterieverbrauch

### 🔒 Sicherheit

- **Encryption:** Upgrade auf AES-256-GCM
- **API Security:** Certificate Pinning implementiert

## 🆕 Version 1.0.3

**Release Datum:** Juli 2025

### ✨ Neue Features

- **Smart Scheduling:** KI-basierte Alarm-Optimierung
- **Multi-Calendar:** Unterstützung mehrerer Google Kalender

### 🐛 Bug Fixes

- **Notification:** Bessere Android 14 Kompatibilität
- **Background Tasks:** Stabilere WorkManager Implementation

## 🆕 Version 1.0.2

**Release Datum:** Juni 2025

### ✨ Neue Features

- **Hue Integration:** Erste Philips Hue Bridge Unterstützung
- **Erweiterte Einstellungen:** Benutzerdefinierte Vorlaufzeiten

## 🆕 Version 1.0.1

**Release Datum:** Mai 2025

### 🐛 Bug Fixes

- **Initial Release:** Erste öffentliche Version
- **Google Calendar:** Basic Integration implementiert
- **Alarm System:** Grundfunktionalität
