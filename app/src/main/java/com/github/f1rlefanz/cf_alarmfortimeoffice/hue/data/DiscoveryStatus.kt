package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Fortschrittsmeldung der Bridge-Suche. Erzeugt an acht Stellen in
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.OfficialHueDiscoveryService],
 * gelesen in `AnimatedDiscoveryCard` und `HueTabContent`.
 * @Immutable annotation optimizes Compose performance
 *
 * ENTFERNT (14.09.2026): `duration`, `foundBridges`, `isError`. Keinen der drei las irgendeine
 * Stelle im Baum; `duration` wurde ausserdem nie gesetzt und stand auf jeder Meldung auf 0L.
 * Der Fehlerfall steht weiterhin in [stage] ("FAILED") und [message], die beide GELESEN werden —
 * `isError` war eine zweite, stille Wahrheit daneben.
 *
 * [method] bleibt vorerst, OBWOHL sie ebenfalls keinen Leser hat: sie ist der einzige Verwender
 * des Enums [DiscoveryMethod]. Der Blickwinkel "Enum-TYPEN ohne Verwender" (Issue #72) hat das
 * gemessen und NICHT mitgeschnitten: [DiscoveryMethod] hat mit dieser Zeile und den acht
 * Erzeugerstellen in `OfficialHueDiscoveryService` (neun Nennungen — Zeile 155 waehlt ternaer)
 * sehr wohl Verwender, ist also kein Fund jenes Blickwinkels.
 * Tot ist die ganze KETTE (nur geschrieben, nie gelesen) — das ist eine andere Messung und
 * steht als eigenes Issue (#116).
 */
@Immutable
data class DiscoveryStatus(
    val method: DiscoveryMethod,
    // ENTFERNT (25.09.2026): der Enum `DiscoveryStage`, der diese Zeile einmal typisiert haette.
    // Er stand seit dem Initial-Commit `34abec2` im Baum und hatte in der GESAMTEN Historie
    // (974 Commits) nie einen Verwender: `git log --all -G DiscoveryStage -- app/*` nennt genau
    // jenen einen Commit, und keine Datei ausser dieser hat den Namen je enthalten. Sein KDoc
    // ("keeping enum for new implementations") war von Anfang an eine Absicht, kein Zustand.
    // Die sechs Eintragsnamen leben unveraendert als Zeichenketten weiter (gesetzt in
    // `OfficialHueDiscoveryService`, gelesen in `AnimatedDiscoveryCard`) — an DIESEM Feld.
    val stage: String, // Changed from enum to String for backward compatibility
    val message: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val currentMethod: String? = null // Current discovery method being used
)

/**
 * Discovery method being used (Enhanced 2025 Edition)
 */
enum class DiscoveryMethod {
    // Die drei, die es wirklich gibt - je ein Erzeuger in den Discovery-Diensten.
    ONLINE_DISCOVERY, N_UPNP, MDNS
    // ENTFERNT (v1.34.3): LOCAL_NETWORK und IP_TEST waren im Code selbst als deprecated markiert,
    // MANUAL und CACHE hatten nie einen Erzeuger. Ein `when` ueber die Werte gibt es nicht,
    // Exhaustiveness konnte also nicht brechen. MANUAL beschrieb eine manuelle IP-Eingabe, die es
    // in der Oberflaeche nicht gibt - vor dem Loeschen geprueft.
}
