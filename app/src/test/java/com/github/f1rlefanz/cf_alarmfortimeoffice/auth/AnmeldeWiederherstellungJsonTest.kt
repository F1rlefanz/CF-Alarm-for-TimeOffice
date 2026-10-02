package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * #55: Der Restore-Schluessel traegt die E-Mail des Google-Kontos in `user.id`, und beim Lesen
 * kommt sie als `response.userHandle` zurueck. Diese Tests halten fest, dass der Hinweis
 * verlustfrei hin- und zurueckkommt, dass die 64-Byte-Grenze von WebAuthn gilt (keine gekuerzte,
 * also fremde Adresse) und dass jede kaputte Antwort auf "kein Hinweis" faellt statt auf ein
 * geratenes Konto.
 */
class AnmeldeWiederherstellungJsonTest {

    private val challenge = ByteArray(AnmeldeWiederherstellungJson.CHALLENGE_BYTES) { it.toByte() }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Baut eine Lese-Antwort in der Form von `RestoreCredential.authenticationResponseJson`. */
    private fun antwortMitHandle(handle: String): String =
        """{"id":"abc","rawId":"abc","type":"public-key",""" +
            """"response":{"clientDataJSON":"x","authenticatorData":"y","signature":"z",""" +
            """"userHandle":"$handle"}}"""

    @Test
    fun `Create-JSON traegt die E-Mail als base64url in user_id und die eigene Domain als rp_id`() {
        val json = AnmeldeWiederherstellungJson.baueCreateJson("nutzer@example.org", challenge)
        assertNotNull(json)
        val wurzel = parse(json!!)

        val userId = wurzel["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("nutzer@example.org", String(Base64.getUrlDecoder().decode(userId), Charsets.UTF_8))
        assertFalse("base64url ohne Auffuellung", userId.contains("="))

        assertEquals("cf-alarm.duckdns.org", wurzel["rp"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(b64(challenge), wurzel["challenge"]!!.jsonPrimitive.content)

        val algs = wurzel["pubKeyCredParams"]!!.jsonArray.map { it.jsonObject["alg"]!!.jsonPrimitive.int }
        assertEquals(listOf(-7, -257), algs)

        val auswahl = wurzel["authenticatorSelection"]!!.jsonObject
        assertEquals("required", auswahl["residentKey"]!!.jsonPrimitive.content)
        assertTrue(auswahl["requireResidentKey"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `die E-Mail steht NUR in user_id, nicht noch einmal im Klartext`() {
        val json = AnmeldeWiederherstellungJson.baueCreateJson("nutzer@example.org", challenge)!!
        assertFalse("Datenschutzerklaerung sagt: nur die Adresse, und nur in user.id", json.contains("nutzer@example.org"))
    }

    @Test
    fun `genau 64 Byte passen, 65 Byte ergeben keinen Schluessel`() {
        val genau64 = "a".repeat(64 - "@example.org".length) + "@example.org"
        assertEquals(64, genau64.toByteArray(Charsets.UTF_8).size)
        assertNotNull(AnmeldeWiederherstellungJson.baueCreateJson(genau64, challenge))

        val zuLang = "a$genau64"
        assertNull(AnmeldeWiederherstellungJson.baueCreateJson(zuLang, challenge))
        assertNull(AnmeldeWiederherstellungJson.userIdBytes(zuLang))
    }

    @Test
    fun `die Grenze zaehlt UTF-8-Bytes, nicht Zeichen`() {
        // 30 Umlaute = 60 Bytes, plus "@b.de" (5) = 65 Bytes bei nur 35 Zeichen.
        val umlaute = "ä".repeat(30) + "@b.de"
        assertEquals(35, umlaute.length)
        assertNull(AnmeldeWiederherstellungJson.userIdBytes(umlaute))
    }

    @Test
    fun `leere E-Mail ergibt keinen Schluessel`() {
        assertNull(AnmeldeWiederherstellungJson.baueCreateJson("", challenge))
        assertNull(AnmeldeWiederherstellungJson.baueCreateJson("   ", challenge))
    }

    @Test
    fun `Get-JSON nennt dieselbe rp_id und eine leere allowCredentials-Liste`() {
        val wurzel = parse(AnmeldeWiederherstellungJson.baueGetJson(challenge))
        assertEquals("cf-alarm.duckdns.org", wurzel["rpId"]!!.jsonPrimitive.content)
        assertTrue(wurzel["allowCredentials"]!!.jsonArray.isEmpty())
        assertEquals(b64(challenge), wurzel["challenge"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Rundreise - die beim Anlegen gesetzte E-Mail kommt beim Lesen zurueck`() {
        val email = "schicht.dienst+test@example.org"
        val userId = parse(AnmeldeWiederherstellungJson.baueCreateJson(email, challenge)!!)["user"]!!
            .jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(email, AnmeldeWiederherstellungJson.leseEmailAusAntwort(antwortMitHandle(userId)))
    }

    @Test
    fun `userHandle mit Auffuellung wird ebenfalls gelesen`() {
        val mitPadding = Base64.getUrlEncoder().encodeToString("ab@cd.ef".toByteArray())
        assertTrue(mitPadding.endsWith("="))
        assertEquals("ab@cd.ef", AnmeldeWiederherstellungJson.leseEmailAusAntwort(antwortMitHandle(mitPadding)))
    }

    @Test
    fun `kaputte oder unpassende Antworten ergeben null statt eines geratenen Kontos`() {
        val faelle = listOf(
            "",
            "kein json",
            "[]",
            """{"response":{}}""",
            """{"response":{"userHandle":null}}""",
            """{"response":{"userHandle":42}}""",
            """{"response":"text"}""",
            antwortMitHandle(""),
            antwortMitHandle("!!!kein-base64!!!"),
            antwortMitHandle(b64("kein-at-zeichen".toByteArray())),
            antwortMitHandle(b64("@vorne.de".toByteArray())),
            antwortMitHandle(b64("hinten@".toByteArray())),
            antwortMitHandle(b64("mit leer@zeichen.de".toByteArray())),
            antwortMitHandle(b64(("a".repeat(60) + "@b.de").toByteArray()))
        )
        for (fall in faelle) {
            assertNull("Erwartet null fuer: $fall", AnmeldeWiederherstellungJson.leseEmailAusAntwort(fall))
        }
    }
}
