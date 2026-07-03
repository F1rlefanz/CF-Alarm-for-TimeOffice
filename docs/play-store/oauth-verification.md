# Google OAuth Verification – `calendar.readonly` (sensitive scope)

> `https://www.googleapis.com/auth/calendar.readonly` ist ein **sensitiver Scope**.
> Ohne verifizierten OAuth-Consent-Screen gilt: max. **100 Test-Nutzer** über die
> Projekt-Lebenszeit **und** ein „Google hat diese App nicht überprüft"-Warnbildschirm
> beim Login. Für den offenen Test ist die Verifizierung daher ein harter Blocker mit
> **Wochen** Vorlaufzeit → möglichst früh einreichen.
>
> Diese Datei bündelt die Scope-Justification (paste-ready) und die Vorbedingungen.

---

## Genutzter Scope (im Code belegt)

```kotlin
// auth/manager/OAuth2TokenManager.kt
val scope = "oauth2:${CalendarScopes.CALENDAR_READONLY}"
```

- **Nur lesend** (`calendar.readonly`) – kein Schreibzugriff, kein `profile`/`contacts`.
- **Kein `AD_ID`**, keine Analytics-/Tracking-SDKs.

---

## Paste-ready scope justification (English → Google OAuth verification)

**Requested scope:** `.../auth/calendar.readonly`

> CF-Alarm for TimeOffice is an alarm clock for shift workers (nurses, healthcare,
> emergency services, manufacturing). Its core function is to read the user's work
> shifts from their Google Calendar and automatically set exact alarms so the user
> reliably wakes up for safety-critical shift start times.
>
> **Why read-only Calendar access is required:** The app needs to read upcoming
> calendar events to detect shifts (by matching user-configured keywords in event
> titles) and compute the correct wake-up time for each shift. It only ever **reads**
> events; it never creates, edits, or deletes calendar data, which is why we request
> the read-only scope rather than full calendar access.
>
> **How the data is used and stored:** Calendar events are fetched directly from the
> Google Calendar API over HTTPS and held only in a short-lived in-memory cache on the
> device. The app does not upload calendar data to any server (the developer operates
> no backend), does not share it with third parties, and does not use it for
> advertising or analytics. Only the derived alarm times (shift name + time) are stored
> locally on the device; the full event contents are not persisted.
>
> **Minimal scope:** We request the least-privileged scope that supports the feature —
> read-only. No additional Google scopes are requested.

---

## Vorbedingungen für die Einreichung (Nutzer-Checkliste)

- [ ] **Google Cloud Console → OAuth-Consent-Screen** von „Testing" auf
      **„In production"** stellen und Verifizierung einreichen.
- [ ] **App-Homepage-URL** hinterlegen (z. B. die GitHub-Pages-Startseite `index.html`).
- [ ] **Privacy-Policy-URL** hinterlegen — MUSS auf einer Domain liegen, die in der
      **Google Search Console verifiziert** ist und die auch als autorisierte Domain im
      Consent-Screen eingetragen ist. (GitHub-Pages-Domain funktioniert, wenn in der
      Search Console verifiziert.)
- [ ] **Autorisierte Domains** im Consent-Screen eintragen (dieselbe Domain wie
      Homepage/Privacy-Policy).
- [ ] **Scope-Begründung** einfügen (englischer Block oben).
- [ ] Ggf. **Demo-Video** bereitstellen: Login → Kalenderzugriff erteilen → Event mit
      Schicht-Keyword anlegen → App erkennt Schicht und setzt exakten Wecker.
- [ ] Sicherstellen, dass die im Consent-Screen und im Play-Store-Eintrag hinterlegte
      Privacy-Policy-URL **identisch** ist.

---

## Übergangslösung, solange die Verifizierung läuft

- Geschlossener Test mit **< 100 Test-Nutzern** ist ohne Verifizierung möglich
  (Consent-Screen im „Testing"-Modus, Nutzer als Test-User eintragen). Der offene Test
  / die 100+-Nutzer-Grenze erfordert die abgeschlossene Verifizierung.
