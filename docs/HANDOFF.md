# Handoff — CF-Alarm for TimeOffice

**Lebendes Dokument.** Erledigtes wird gelöscht, nicht abgehakt oder durchgestrichen — was hier steht, ist offen.
Die Historie steht im Git-Log, nicht hier.

**Stand:** 15.07.2026 · `main` = **v1.10.7 / versionCode 50** · alles gemerged und gepusht.

> **Ungeprüft:** die Regel-Validierung (v1.10.4, greift erst beim Speichern einer Regel) und der
> Log-Kleinkram aus v1.10.6 (inspiziert, nicht am Gerät bestätigt). Der Startup-Fix (v1.10.7,
> HueApiClient-TLS lazy) ist am Emulator gemessen; alles Übrige am Gerät bzw. im Log bestätigt.

---

## Offen

### 1. Der Weckvorgang selbst ist ungetestet ← wichtigster Punkt

Alles andere ist Beiwerk, solange das nicht bewiesen ist.

**Test: Donnerstag, 16.07., Frühdienst, Alarm 05:30** (im Log bestätigt gesetzt:
`System alarm set successfully: Frühschicht at 16.07.2026 05:30`). Auto-Off geplant für 06:00
(+30min, 2 Gruppen).

Zu prüfen:
1. Klingelt es **einmal** statt zweimal? Eine Notification statt zwei?
2. Steht die Uhrzeit vollständig drin („Deine Schicht beginnt um 06:00")?
3. Kommt der Vollbild-Screen **von selbst** hoch? (Nur bei **gesperrtem** Gerät — bei entsperrtem
   Handy zeigt Android absichtlich nur ein Banner. Kein Bug.)
4. Reicht **ein** Stopp?
5. Pausiert ein laufender Podcast und läuft danach weiter?
6. Gehen die Lampen um 06:00 wieder aus?

### 2. Bridge-seitige Zeitpläne

**Idee:** Beim Alarm ist die Bridge nachweislich erreichbar (sonst ginge das Licht nicht an).
**Im selben Atemzug** einen Zeitplan *auf der Bridge* anlegen (`POST /api/<user>/schedules`,
`autodelete: true`) → die Bridge schaltet selbst aus, egal wo das Handy ist. Damit verschwindet
die ganze Fehlerklasse „Handy nicht im Heim-WLAN" für den Auto-Off.

- Die App spricht bereits **V1 lokal** (`/api/<user>/groups/<id>/action`) — keine neue Auth, keine
  Cloud, kein Hue-Entwicklerkonto nötig.
- Signify sagt, V1 werde „langfristig" entfernt (kein Datum). Die App steckt ohnehin komplett auf
  V1 → keine *neue* Schuld.
- Der WorkManager-Retry wird damit vom Haupt- zum Fallback-Mechanismus.
- **Einschränkung:** Das JSON-Format muss gegen die echte Bridge verifiziert werden.
- Zum Testen steht ein **Emulator als Zweitgerät** bereit.
