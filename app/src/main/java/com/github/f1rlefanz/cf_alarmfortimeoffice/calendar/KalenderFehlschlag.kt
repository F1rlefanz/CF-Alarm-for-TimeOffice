package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.WartungTokenFehler

// Die Einstufung eines gescheiterten Kalenderabrufs. Sie lag bis v1.43.6 im CalendarViewModel -
// hier liegt sie, weil die 6h-Wartung dieselbe Frage stellt und aus `service/` nicht in
// `viewmodel/` greifen darf (tools/invarianten/pruefe_code.py). Zwei Einstufungen, die "Funkloch"
// verschieden verstehen, meldeten sonst im Vorder- und im Hintergrund Verschiedenes.

/** Woran ein Kalenderabruf gescheitert ist - daran haengt, WELCHE Warnung die App zeigt. */
internal enum class FehlschlagArt {
    /** Verbindung (Flugmodus, Funkloch, Google nicht erreichbar): kein Beleg fuer irgendetwas. */
    NETZ,

    /** Google antwortet, DIESEN Kalender gibt es fuer dich nicht (mehr): 404, oder 403 ohne Scope-Mangel. */
    KALENDER_FEHLT,

    /** Alles uebrige - abgelehntes oder fehlendes Token, unbekannte Ursache. Im Zweifel die Anmeldung. */
    ANMELDUNG
}

/**
 * PURE, TESTBAR: Woran ist ein Kalenderabruf gescheitert?
 *
 * [AppError.PermissionError] ist hier ein Kalender-Problem: das CalendarRepository bildet
 * 404 ("nicht gefunden oder nicht mehr freigegeben") und das 403 OHNE Scope-Mangel darauf
 * ab. Der Scope-Mangel ist ein [AppError.AuthenticationError] (Anmeldung), Abruf- und
 * Kontingentgrenzen sind ein [AppError.NetworkError] (voruebergehend,
 * `istVoruebergehendeAblehnung`).
 */
internal fun fehlschlagArt(fehler: Throwable?): FehlschlagArt = when {
    istNetzbedingterFehlschlag(fehler) -> FehlschlagArt.NETZ
    fehler is AppError.PermissionError -> FehlschlagArt.KALENDER_FEHLT
    else -> FehlschlagArt.ANMELDUNG
}

/**
 * PURE, TESTBAR: Scheiterte ein Kalenderabruf an der Verbindung (und nicht an der
 * Anmeldung oder an einem einzelnen Kalender)?
 *
 * Zwei Wege fuehren hierher, beide als [AppError.NetworkError]: der Abruf selbst (das
 * CalendarRepository gibt dabei die Ursache NICHT mit) und ein offline gescheiterter
 * Token-Refresh (CalendarUseCase.resolveAccessToken). Die Suche nach einer IOException in
 * der Ursachenkette faengt jede andere Verpackung; sie ist dieselbe Einstufung wie im
 * Token-Schritt der Wartung ([WartungTokenFehler.istNetzursache]).
 */
internal fun istNetzbedingterFehlschlag(fehler: Throwable?): Boolean = when (fehler) {
    is AppError.NetworkError -> true
    // Eine ANTWORT von Google ist nie ein Funkloch - auch wenn ihre Ursache eine
    // IOException ist: GoogleJsonResponseException erbt ueber HttpResponseException von
    // IOException. Ohne diesen Zweig kippte ein 401 in "nicht erreichbar", sobald das
    // CalendarRepository einmal die Ursache mitgibt.
    is AppError.AuthenticationError,
    is AppError.PermissionError,
    is AppError.CalendarAccessError -> false
    else -> WartungTokenFehler.istNetzursache(fehler)
}
