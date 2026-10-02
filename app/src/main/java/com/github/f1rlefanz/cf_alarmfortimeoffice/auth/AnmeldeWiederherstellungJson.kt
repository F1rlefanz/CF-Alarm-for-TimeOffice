package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Reine Funktionen rund um den Restore-Schluessel (#55) - ohne Android, damit per Unit-Test
 * pruefbar. Die Plattform-Aufrufe stehen in [PlayDiensteAnmeldeWiederherstellung].
 *
 * WAS DER SCHLUESSEL FUER UNS IST: kein Anmeldenachweis, sondern nur ein Ende-zu-Ende-
 * verschluesselter Hinweis "dieses Google-Konto". Getragen wird er von `user.id` - die kommt beim
 * Lesen als `response.userHandle` zurueck. Den Zugriff auf den Kalender gewaehrt weiterhin allein
 * Googles `authorize()`; ein gefaelschter Hinweis fuehrte dort nur auf einen Zustimmungsdialog.
 *
 * WARUM DIE APP DAS WEBAUTHN-JSON SELBST BAUT: Die Doku sieht es "vom App-Server" vor, CF-Alarm
 * hat bewusst keinen. Geprueft wird hier nichts kryptografisch (Challenge, Signatur) - es gibt
 * niemanden, der es pruefen koennte, und der Hinweis braucht es nicht (siehe oben).
 *
 * WARUM DIE E-MAIL IN `user.id` (Issue #55, entschieden 02.10.2026): nur mit ihr laesst
 * sich `calendar.readonly` per `setAccount()` ganz ohne Oberflaeche holen. Die numerische
 * Google-Konto-ID braeuchte auf dem neuen Geraet einen Kontowaehler und versagt bei mehreren
 * Konten. `name`/`displayName` tragen deshalb bewusst NICHT noch einmal die Adresse.
 */
object AnmeldeWiederherstellungJson {

    /**
     * Die Domain der GitHub-Pages-Seite (`docs/CNAME`). Dort liegt
     * `.well-known/assetlinks.json` - ob die Play-Dienste sie fuer einen Restore-Schluessel
     * pruefen, ist nicht belegt; sie liegt vorsorglich da.
     */
    const val RP_ID: String = "cf-alarm.duckdns.org"

    private const val RP_NAME = "CF Alarm"

    /** WebAuthn erlaubt fuer `user.id` 1 bis 64 Byte. */
    const val MAX_USER_ID_BYTES: Int = 64

    /** Laenge der Zufalls-Challenge; WebAuthn verlangt mindestens 16 Byte. */
    const val CHALLENGE_BYTES: Int = 32

    private const val ZEITLIMIT_MS = 60_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Die `user.id`-Bytes zu einer E-Mail, oder `null`, wenn sie nicht in einen Schluessel passt
     * (leer oder ueber [MAX_USER_ID_BYTES] Byte UTF-8). Dann gibt es eben keinen Schluessel - der
     * normale Login bleibt, und die Adresse wird NICHT gekuerzt: eine gekuerzte Adresse waere ein
     * fremdes Konto.
     */
    fun userIdBytes(email: String): ByteArray? {
        if (email.isBlank()) return null
        val bytes = email.toByteArray(Charsets.UTF_8)
        return bytes.takeIf { it.size <= MAX_USER_ID_BYTES }
    }

    /**
     * `PublicKeyCredentialCreationOptionsJSON` fuer `CreateRestoreCredentialRequest`, oder `null`,
     * wenn die E-Mail nicht als `user.id` taugt (siehe [userIdBytes]).
     *
     * `residentKey = required` und `userVerification = preferred` wie im einzigen oeffentlichen
     * Beispiel (kkoiwai/RestoreCredentialsAndroid); ein Restore-Schluessel zeigt keine Oberflaeche.
     */
    fun baueCreateJson(email: String, challenge: ByteArray): String? {
        val userId = userIdBytes(email) ?: return null
        val objekt = buildJsonObject {
            put("challenge", base64Url(challenge))
            putJsonObject("rp") {
                put("name", RP_NAME)
                put("id", RP_ID)
            }
            putJsonObject("user") {
                put("id", base64Url(userId))
                put("name", RP_NAME)
                put("displayName", RP_NAME)
            }
            putJsonArray("pubKeyCredParams") {
                add(algorithmus(-7))   // ES256
                add(algorithmus(-257)) // RS256
            }
            put("timeout", ZEITLIMIT_MS)
            put("attestation", "none")
            putJsonArray("excludeCredentials") {}
            putJsonObject("authenticatorSelection") {
                put("residentKey", "required")
                put("requireResidentKey", true)
                put("userVerification", "preferred")
            }
        }
        return objekt.toString()
    }

    /** `PublicKeyCredentialRequestOptionsJSON` fuer `GetRestoreCredentialOption`. */
    fun baueGetJson(challenge: ByteArray): String = buildJsonObject {
        put("challenge", base64Url(challenge))
        put("rpId", RP_ID)
        putJsonArray("allowCredentials") {}
        put("timeout", ZEITLIMIT_MS)
        put("userVerification", "preferred")
    }.toString()

    /**
     * Liest die beim Anlegen gesetzte E-Mail aus `RestoreCredential.authenticationResponseJson`
     * (`response.userHandle`, base64url). `null` bei JEDER Abweichung - ein unlesbarer Hinweis
     * fuehrt auf den normalen Anmeldebildschirm, nie auf ein geratenes Konto.
     */
    fun leseEmailAusAntwort(antwortJson: String): String? = try {
        val wurzel = json.parseToJsonElement(antwortJson).jsonObject
        val handle = wurzel["response"]?.jsonObject?.get("userHandle")?.jsonPrimitive
            ?.takeIf { it.isString }?.content
        handle
            ?.takeIf { it.isNotBlank() }
            ?.let { Base64.getUrlDecoder().decode(it.trimEnd('=')) }
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_USER_ID_BYTES }
            ?.toString(Charsets.UTF_8)
            ?.takeIf { siehtAusWieEmail(it) }
    } catch (e: IllegalArgumentException) {
        // Kaputtes JSON (SerializationException ist eine IllegalArgumentException), falscher
        // Typ an der Stelle oder kein gueltiges base64url.
        null
    }

    private fun siehtAusWieEmail(text: String): Boolean {
        val at = text.indexOf('@')
        return at > 0 && at < text.length - 1 && text.none { it.isWhitespace() }
    }

    private fun algorithmus(alg: Int): JsonObject = buildJsonObject {
        put("type", "public-key")
        put("alg", alg)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
