# Vorwecken: warum der Wecker den Bildschirm selbst weckt

Seit **1.39.3** (04.09.2026), **ohne Geräte-Unterscheidung seit 1.39.5**. Betrifft
`AlarmSoundService.onStartCommand` und `VorweckEntscheidung`. Vollständige Beweisführung: GitHub
Issue **#36** (geschlossen), Nachlese: **#61**.

## Inhalt

- [Der Defekt, gegen den es existiert](#der-defekt-gegen-den-es-existiert)
- [Die Loesung](#die-loesung)
- [Zwei Fallen, die erst echte Weckvorgaenge zeigten](#zwei-fallen-die-erst-echte-weckvorgaenge-zeigten)
- [Warum das Geraete-Gate gestrichen wurde (1.39.5)](#warum-das-geraete-gate-gestrichen-wurde-1395)
- [Widerlegte Saetze, die hier gestanden haben](#widerlegte-saetze-die-hier-gestanden-haben)
- [Grenzen, die bekannt sind](#grenzen-die-bekannt-sind)

## Der Defekt, gegen den es existiert

Auf dem Fairphone 6 (Android 16) startet SystemUI die herstellereigene Gesichtsentsperrung
`com.android.settings/.anc.unlock.UnlockActivity` **bei jedem Aufwecken des Bildschirms**, sofern
ein Gesicht eingelernt ist. Aus dem `Settings.apk` des Geräts gezogen (`aapt2 dump xmltree`):

```
E: activity (line=7044)
   android:name="com.android.settings.anc.unlock.UnlockActivity"
   android:exported=true
   android:taskAffinity="com.android.settings.unlock"
   android:excludeFromRecents=true
   android:launchMode=1 (singleTop)
```

**Kein `showWhenLocked`, kein `turnScreenOn`** — die Attribute kommen im gesamten Settings-Manifest
nicht vor. Zusammen mit der eigenen `taskAffinity` landet sie in einer eigenen Task oberhalb des
Weckbildschirms. `KeyguardController.updateVisibility()` wertet nur die **oberste** fokussierbare
Root-Task; kann deren Top-Activity nicht über dem Keyguard zeigen, wird `mTopOccludesActivity = null`
und der Keyguard für den **ganzen** `DefaultTaskDisplayArea` un-occluded. Der Weckbildschirm liegt
dann dahinter — nicht zerstört, nur gestoppt.

**Der Auslöser war unser eigener Full-Screen-Intent.** Er war der Weckgrund
(`WAKE_REASON_APPLICATION, details=com.android.systemui:full_screen_intent`), unsere Activity also
zwangsläufig VOR der Gesichtsentsperrung da — und wurde überholt. Die Google Uhr weckt dagegen mit
einem eigenen Wake-Lock und postet erst danach; ihre Activity kommt oben drauf.

Am 04.09.2026 lagen beide Fälle 60 Sekunden auseinander im selben Systemlog:

| | Abstand Wake → eigene Activity | Ergebnis |
|---|---|---|
| CFAlarm 07:00 | 62 ms | verdrängt, 32 s ohne Weckbildschirm |
| Google Uhr 07:01 | 448 ms | nicht verdrängt, stabil bis zum Abstellen |

## Die Lösung

Bildschirm selbst wecken (`SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`), **600 ms warten**,
dann erst `startForeground()` mit der unveränderten FSI-Notification. Die Gesichtsentsperrung zieht
in dieser Zeit vorbei und wird vom Keyguard gestoppt; unser Weckbildschirm landet darüber.

**Der Ton startet unverändert sofort, VOR dem Vorlauf.** Das ist keine Feinheit: der Ton ist der
Wecker. Wer ihn in den verzögerten Zweig zieht, verzögert den Weckruf selbst.

Beleg am Gerät, drei Läufe, Bedingung jeweils VOR der Weckzeit gemessen (`Dozing` +
`isKeyguardShowing`):

| | 16:42 | 17:33 | 17:48 |
|---|---|---|---|
| `🌅 Vorwecken` | `00.071` | `00.103` | `00.134` |
| Ton | `00.214` | `00.499` | `00.381` |
| `startForeground` | `00.680` | `00.707` | `00.738` |
| Weckbildschirm | `01.046` | `01.050` | `01.109` |
| `STOPPED, obwohl…` | keiner | keiner | keiner |
| stabil bis Dismiss | 91 s | 58 s | 76 s |

## Zwei Fallen, die erst echte Weckvorgänge zeigten

**Historie — beide gehörten zum Geräte-Gate, das es seit 1.39.5 nicht mehr gibt.** Sie stehen
hier, weil ihre Lehre über den Einzelfall hinausreicht und weil sie erklärt, warum das Gate am
Ende gestrichen wurde. Beide hätten die Lösung im Alltag wertlos gemacht, und **beide schlagen
erst beim ZWEITEN Wecker zu** — kein Unit-Test und kein Emulator hätte sie gefunden.

**1. Der Erfolg löschte sein eigenes Gate.** Gate war zuerst der Hinweis-Zähler
`anzahl_in_folge`. Der geschützte Lauf war sauber, also lief `meldeSauberenLauf()` und setzte ihn
auf 0 — der nächste Wecker wäre ungeschützt gewesen, also verdrängt, Zähler 1, der übernächste
wieder geschützt. **Jeder zweite Wecker ohne Bedienoberfläche.**

**2. Der neue Merker entstand gar nicht erst.** Der bleibende `je_verdraengt` wurde zunächst nur in
`zaehleVerdraengung()` geschrieben. Auf einem Bestandsgerät kam der Schutz aber über den
Migrationszweig (Zähler ≥ 1, Merker fehlt). Läuft der erste geschützte Wecker sauber, wird nichts
verdrängt, `zaehleVerdraengung()` kommt nie dran — und mit dem Zähler fällt die Migrationsbedingung
weg. Dieselbe Falle eine Ebene tiefer.

**Die verallgemeinerbare Lehre:** *Hängt eine Gegenmaßnahme davon ab, dass der Fehler noch
auftritt, schaltet ihr Erfolg sie ab.* Zwei Fragen brauchen zwei Merker — „passiert es gerade"
(zurücksetzbar, für den Hinweis) und „ist dieses Gerät betroffen" (bleibend, für die Maßnahme).

**Die noch bessere Lehre, einen Tag später:** *Wenn eine Maßnahme so billig ist, dass man sie
immer anwenden kann, braucht sie die Frage „ist dieses Gerät betroffen" gar nicht* — und dann
auch keinen Merker, der falsch stehen kann.

## Warum das Geräte-Gate gestrichen wurde (1.39.5)

Bis 1.39.4 lief das Vorwecken nur, wenn auf diesem Gerät zuvor eine Verdrängung gemessen worden
war (`je_verdraengt`). Gestrichen am 04.09.2026, Entscheidung des Eigentümers. Die drei Kosten,
gegen die abgewogen wurde:

1. **Backup.** Der Merker musste aus Cloud-Backup und Gerätetransfer ausgeschlossen werden, sonst
   erbt ein gesundes Nachfolgegerät die Diagnose des alten.
2. **Verzahnung mit dem Hinweis-Zähler** — die zwei Fallen oben, beide am selben Tag, beide erst
   im echten Betrieb sichtbar.
3. **Direct Boot, und das gab den Ausschlag.** Der Merker lag im CE-Storage und ist vor der ersten
   Entsperrung nicht lesbar. Der erste Wecker nach einem nächtlichen Neustart lief damit
   ungeschützt — ausgerechnet die Lage, in der niemand danebensteht und in der man einen Wecker
   am wenigsten verlieren will.

**Der Preis:** 600 ms später erscheint der Weckbildschirm jetzt auf JEDEM Gerät bei dunklem,
gesperrtem Bildschirm. Er ist nicht der Weckruf — **der Ton startet unverändert sofort**, auf
beiden Pfaden. Zum Vergleich: die Google Uhr postet ihren Full-Screen-Intent ohnehin ~448 ms nach
ihrem eigenen Wake, der Wert ist also nichts Außergewöhnliches.

**Was blieb:** der Hinweis-Zähler (`anzahl_in_folge`) samt Status-Karte — er meldet, wenn es
TROTZ Vorwecken passiert. Sein Backup-Ausschluss bleibt aus demselben Grund wie vorher: ein
geerbter Zähler würde auf einem gesunden Gerät raten, das eingelernte Gesicht zu löschen.

### Am Emulator gemessen (04.09.2026, nach dem Streichen)

`VorweckenAmGeraetTest` lief auf `emulator-5554` (Android 16) in einem Zustand, den kein
Unit-Test herstellen kann: **Direct Boot, Nutzer nie entsperrt, Bildschirm aus und gesperrt.**
Der Test protokolliert den Ausgangszustand ausdrücklich mit — `CE-Storage GESPERRT (Direct Boot)
— der alte Merker wäre hier unlesbar gewesen`. Aus dem Systemlog:

```
PowerManagerService: Waking up from Asleep (uid=10227, reason=WAKE_REASON_APPLICATION,
                     details=CFAlarm:VorweckenFuerWeckbildschirm)
CFAlarm.Alarm: 🌅 Vorwecken: Bildschirm wird 600 ms vor der Wecker-Notification geweckt
```

**Der Weckgrund ist jetzt unser eigener Wake-Lock**, nicht mehr `systemui:full_screen_intent` —
genau die Umkehrung, um die es geht. Unter 1.39.4 wäre in dieser Lage gar nicht vorgeweckt worden.

**Nebenbefund, und der schließt eine offene Lücke:** derselbe Lauf stoppte den Wecker 123 ms nach
dem Start, also **mitten im 600-ms-Vorlauf, bevor `startForeground()` je lief** — das Fenster, das
1.39.4 abgesichert hat und das bis dahin nur *am Code gelesen*, nicht gemessen war. Ergebnis:
kein „did not then call Service.startForeground()", kein Prozesstod, Dienst sauber abgebaut
(`dumpsys activity services` danach leer). Das Nachholen in `stopAlarmAndService()` hält.

**Was das für künftige Änderungen heißt:** `VorweckEntscheidung.vorlaufMillis` liest **nur
Systemzustand**. Wer dort wieder etwas Gespeichertes abfragt, holt sich Punkt 3 zurück — und
`VorweckEntscheidungTest` hält mit einer Typzuweisung dagegen, die dann nicht mehr übersetzt.

## Widerlegte Sätze, die hier gestanden haben

- **„App-seitig ist nichts zu gewinnen."** Falsch. Er galt für das NACHREICHEN des
  Full-Screen-Intents — das bleibt widerlegt (K2, vier Messläufe: das System wertet ihn
  ausschließlich beim ERSTEN Posten aus). Er galt nie für den **Zeitpunkt** des ersten Postens.
- **„Der Sperrbildschirm zeigt vom laufenden Wecker gar nichts."** Falsch. Die Benachrichtigung ist
  da, nur eingeklappt; über den Aufklapp-Pfeil sind „Schlummern" und „Stopp" ohne Entsperren
  erreichbar. Die früheren UI-Abzüge mit 27 Knoten waren unvollständig, spätere haben 142–143.
- **„Der Emulator kann das nicht reproduzieren."** Zu grob. Nicht reproduzierbar ist nur der
  *Auslöser*. Der *Mechanismus* ist reines AOSP: `am start` auf eine beliebige exportierte Activity
  ohne `showWhenLocked` erzeugt bei laufendem Wecker und gesperrtem Bildschirm dieselbe Kette
  (`wm_set_keyguard_occluded [0]` + `wm_stop_activity`). Damit ist eine Prüfbank möglich; Details in
  Memory `env_emulator_trigger_alarm`.

## Grenzen, die bekannt sind

- **Der Gerätedefekt bleibt.** Wir weichen ihm aus; jede andere Wecker-App auf dem FP6 trifft es
  weiter, auch die vorinstallierte Google Uhr.
- **Es hängt an einem Kompat-Schalter.** Dass `ACQUIRE_CAUSES_WAKEUP` ohne die Berechtigung
  `TURN_SCREEN_ON` (`signature|privileged|appop`, für uns unerreichbar) überhaupt weckt, liegt an
  `REQUIRE_TURN_SCREEN_ON_PERMISSION` — am FP6 gemessen `enableSinceTargetSdk=10000`, also noch nicht
  scharf. Schaltet Google das für ein künftiges targetSdk, wartet der Vorlauf umsonst.
- **Der Vorlauf trifft auch gesunde Geräte.** Seit 1.39.5 bewusst so — Begründung im Abschnitt
  darüber. Die frühere Grenze „der erste Wecker nach einem Neustart ohne Entsperrung ist
  ungeschützt" ist damit **weggefallen**, nicht etwa vergessen worden: sie war die Folge des
  Gates im CE-Storage.
