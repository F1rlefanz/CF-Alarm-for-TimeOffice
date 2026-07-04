# Play Console – Begründung `FOREGROUND_SERVICE_SPECIAL_USE`

> Diese Datei enthält den Begründungstext für die Play-Console-Deklaration der
> `specialUse`-Foreground-Service-Nutzung. Der **englische Block** ist 1:1 in das
> Play-Console-Formular kopierbar (Google-Review erfolgt auf Englisch). Die
> deutschen Abschnitte sind nur interne Erläuterung.

---

## Betroffene Deklaration (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".service.AlarmMaintenanceService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Periodic shift-based alarm synchronization" />
</service>
```

- **Service:** `AlarmMaintenanceService` (kurzlebig, nicht exportiert)
- **Subtyp:** `Periodic shift-based alarm synchronization`
- **Trigger:** Exact Alarm alle 6 Stunden
- **Pipeline:** OAuth-Token-Refresh → Bridge/Health-Check → Google-Calendar-Events laden → Schichterkennung → exakte Alarme (neu) setzen

> Hinweis: Der zweite Foreground-Service `AlarmSoundService` nutzt den
> Standard-Typ `mediaPlayback` (Abspielen des Wecktons) und braucht **keine**
> Special-Use-Begründung.

---

## Paste-ready justification (English → Play Console)

**App category:** Alarm clock / productivity app for **shift workers** (nurses,
healthcare, emergency services, manufacturing).

**Why the app uses `FOREGROUND_SERVICE_SPECIAL_USE`:**

> CF-Alarm for TimeOffice is an alarm app for shift workers. It reads the user's
> Google Calendar, detects upcoming shifts, and schedules exact alarms so the
> user reliably wakes up for safety-critical shift start times.
>
> The `specialUse` foreground service `AlarmMaintenanceService` runs a short,
> infrequent maintenance task — at most once every 6 hours, triggered by an exact
> alarm. During each run it (1) refreshes the Google OAuth token, (2) verifies
> connectivity, (3) fetches the latest Google Calendar events, (4) detects shifts,
> and (5) re-creates or updates the user's exact alarms accordingly. This keeps the
> alarms in sync when shifts are added, moved, or cancelled in the calendar.
>
> A foreground service is required because the task performs network I/O plus exact
> alarm (re)scheduling that must complete reliably even while the app is in the
> background; a backgrounded service would be killed before the alarms are updated,
> causing the user to miss a shift.
>
> **Why not a standard foreground service type:** No standard type matches this use
> case. It is not `mediaPlayback`, `location`, `camera`, `microphone`, `phoneCall`,
> `connectedDevice`, or `health`. It is closest to data synchronization, but it is
> not a continuous/user-initiated `dataSync` — it is a brief, system-scheduled,
> periodic alarm-maintenance job. We therefore declare `specialUse` with the subtype
> "Periodic shift-based alarm synchronization".
>
> **Why not WorkManager alone:** WorkManager is already used for deferrable Hue/light
> tasks. It is not suitable here because the alarm maintenance is driven by an exact
> alarm and must run promptly within a bounded window to guarantee that the next
> shift alarm is correct. WorkManager jobs are deferrable and subject to Doze/standby
> batching, which cannot guarantee timely recreation of safety-critical wake-up
> alarms for shift workers.
>
> **User benefit and transparency:** The run is short-lived, infrequent (≤ every 6
> hours), and shows a foreground-service notification so the user is aware of the
> background maintenance. The service is not exported and performs no advertising,
> tracking, or unrelated work.

---

## Paste-ready Begründung (Deutsch, falls auf Deutsch eingereicht wird)

> CF-Alarm for TimeOffice ist eine Wecker-App für Schichtarbeiter. Sie liest den
> Google-Kalender des Nutzers, erkennt anstehende Schichten und stellt exakte
> Wecker, damit der Nutzer sicherheitskritische Schichtbeginne zuverlässig nicht
> verpasst.
>
> Der `specialUse`-Foreground-Service `AlarmMaintenanceService` führt eine kurze,
> seltene Wartungsaufgabe aus – höchstens alle 6 Stunden, ausgelöst durch einen
> Exact Alarm. Pro Lauf: (1) Google-OAuth-Token erneuern, (2) Konnektivität prüfen,
> (3) aktuelle Google-Calendar-Events laden, (4) Schichten erkennen, (5) exakte
> Wecker neu erstellen/aktualisieren. So bleiben die Wecker synchron, wenn Schichten
> im Kalender hinzugefügt, verschoben oder gestrichen werden.
>
> Ein Foreground-Service ist nötig, weil die Aufgabe Netzwerk-I/O **und** das exakte
> (Neu-)Planen von Weckern umfasst, das auch im Hintergrund zuverlässig abschließen
> muss. Ein normaler Hintergrund-Service würde beendet, bevor die Wecker aktualisiert
> sind – der Nutzer könnte eine Schicht verpassen.
>
> Kein Standard-FGS-Typ passt (kein `mediaPlayback`, `location`, `dataSync` usw.); es
> handelt sich um eine kurze, system-geplante, periodische Wecker-Wartung. Daher
> `specialUse` mit Subtyp „Periodic shift-based alarm synchronization".

---

## Hinweise für den Reviewer / Demo-Schritte

Damit das Google-Review die Funktion nachvollziehen kann:

1. Mit Google-Account anmelden und Kalenderzugriff erteilen.
2. Im verbundenen Google-Kalender ein Event anlegen, dessen Titel ein
   konfiguriertes Schicht-Keyword enthält (z. B. „Frühdienst").
3. In der App wird die Schicht erkannt und ein exakter Wecker gesetzt
   (sichtbar im Status-/Home-Tab).
4. `AlarmMaintenanceService` hält diese Wecker alle 6 Stunden mit dem Kalender
   synchron; der Lauf erscheint kurz als Foreground-Notification.

---

## Checkliste vor Einreichung

- [ ] Subtyp im Manifest und in dieser Begründung identisch
      (`Periodic shift-based alarm synchronization`).
- [ ] Screenshot der Foreground-Notification des `AlarmMaintenanceService` beilegen
      (zeigt Transparenz gegenüber dem Nutzer).
- [ ] Sicherstellen, dass `AlarmSoundService` (mediaPlayback) **nicht** mit dieser
      Special-Use-Begründung vermischt wird – das ist ein separater Standard-Typ.
- [ ] Prüfen, ob die Exact-Alarm-Nutzung (`USE_EXACT_ALARM`) separat im
      „Alarms & reminders"-Formular begründet ist (Zielgruppe: Schichtarbeiter,
      sekundengenaue Weckzeiten).
