package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import androidx.datastore.core.CorruptionException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedPreferencesSerializer
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Haelt fest, dass ein Entschluesselungs-Fehler des Token-Stores als
 * [CorruptionException] herauskommt - und NICHT als [TinkEncryptionException].
 *
 * Warum das der ganze Punkt ist: DataStore liest VOR JEDEM Schreiben erneut
 * (`DataStoreImpl.transformAndWrite` -> `readDataOrHandleCorruption`) und dessen Selbstheilungspfad
 * (`corruptionHandler`) faengt ausschliesslich `CorruptionException`. Eine `TinkEncryptionException`
 * (erbt direkt von `Exception`) machte den verschluesselten Store damit dauerhaft lese- UND
 * SCHREIB-tot: der Nutzer konnte sich neu anmelden, aber der Token liess sich nie mehr speichern -
 * Endlos-Re-Auth, keine Alarme, keine Selbstheilung ausser "App-Daten loeschen".
 */
class EncryptedPreferencesSerializerTest {

    @Test
    fun `readFrom uebersetzt einen Tink-Fehler in eine CorruptionException`() = runTest {
        val helper = mock<TinkEncryptionHelper>()
        // thenAnswer, NICHT thenThrow: TinkEncryptionException ist eine CHECKED Exception, und
        // Kotlin erzeugt keine `throws`-Klausel. Mockito lehnt thenThrow dann mit
        // "Checked exception is invalid for this method" schon beim Stubben ab.
        whenever(helper.decrypt(any(), anyOrNull()))
            .thenAnswer { throw TinkEncryptionException("Decryption failed") }
        val serializer = EncryptedPreferencesSerializer(helper)

        try {
            serializer.readFrom(byteArrayOf(1, 2, 3, 4).inputStream())
            fail("Ein nicht entschluesselbarer Store muss einen Fehler melden")
        } catch (e: CorruptionException) {
            assertNotNull("Die Urspruchsursache muss erhalten bleiben", e.cause)
            assertTrue(
                "Ursache muss der Tink-Fehler sein, damit er im Log rekonstruierbar bleibt",
                e.cause is TinkEncryptionException
            )
        }
    }

    @Test
    fun `readFrom liefert bei leerer Datei den Default und meldet keinen Fehler`() = runTest {
        val helper = mock<TinkEncryptionHelper>()
        val serializer = EncryptedPreferencesSerializer(helper)

        // Leere Datei = "noch nie etwas gespeichert" - das ist KEINE Korruption.
        val prefs = serializer.readFrom(ByteArray(0).inputStream())

        assertTrue("Leere Datei muss leere Preferences ergeben", prefs.asMap().isEmpty())
    }

    /**
     * Ein UNERWARTETER Lesefehler (IO-Haenger, unerwartete Exception nach dem Entschluesseln) darf
     * nicht still zu leeren Preferences werden: DataStore haelt einen zurueckgegebenen Default fuer
     * den gueltigen Ist-Zustand und schreibt ihn beim naechsten Write ueber den noch INTAKTEN
     * Ciphertext - der Token ist dann weg, obwohl er nie unlesbar war.
     */
    @Test
    fun `readFrom degradiert einen unerwarteten Lesefehler nicht still auf leere Preferences`() = runTest {
        val helper = mock<TinkEncryptionHelper>()
        whenever(helper.decrypt(any(), anyOrNull()))
            .thenAnswer { throw IllegalStateException("Storage haengt") }
        val serializer = EncryptedPreferencesSerializer(helper)

        try {
            val prefs = serializer.readFrom(byteArrayOf(1, 2, 3, 4).inputStream())
            fail("Ein Lesefehler muss gemeldet werden, hier kamen ${prefs.asMap().size} Keys zurueck")
        } catch (e: IllegalStateException) {
            assertEquals("Storage haengt", e.message)
        }
    }

    /**
     * Der Notausgang, ohne den der corruptionHandler den Fall NICHT heilt, fuer den er gebaut ist:
     * bei unbrauchbarem Keyset scheitert auch der ERSATZ-Write (dieselbe aead-Instanz wie beim
     * Lesen) und der Store bleibt lese- UND schreib-tot. Ein LEERER Zustand braucht keine
     * Verschluesselung - als 0-Byte-Datei liest ihn readFrom() bereits als "noch nichts gespeichert".
     */
    @Test
    fun `writeTo schreibt einen leeren Zustand auch ohne funktionierende Verschluesselung`() = runTest {
        val helper = mock<TinkEncryptionHelper>()
        whenever(helper.encrypt(any(), anyOrNull()))
            .thenAnswer { throw TinkEncryptionException("Keyset unbrauchbar") }
        val serializer = EncryptedPreferencesSerializer(helper)
        val output = ByteArrayOutputStream()

        serializer.writeTo(emptyPreferences(), output)

        assertEquals("Der leere Zustand muss als 0-Byte-Datei landen", 0, output.size())
        assertTrue(
            "...und wieder als leere Preferences lesbar sein",
            serializer.readFrom(output.toByteArray().inputStream()).asMap().isEmpty()
        )
    }

    /**
     * Die Kehrseite desselben Notausgangs: echte Daten werden NIEMALS unverschluesselt geschrieben.
     */
    @Test
    fun `writeTo schreibt echte Daten niemals unverschluesselt`() = runTest {
        val helper = mock<TinkEncryptionHelper>()
        whenever(helper.encrypt(any(), anyOrNull()))
            .thenAnswer { throw TinkEncryptionException("Keyset unbrauchbar") }
        val serializer = EncryptedPreferencesSerializer(helper)
        val output = ByteArrayOutputStream()
        val withToken = mutablePreferencesOf(stringPreferencesKey("token_data_v2") to "geheim")

        try {
            serializer.writeTo(withToken, output)
            fail("Ein nicht verschluesselbarer Token darf nicht geschrieben werden")
        } catch (e: TinkEncryptionException) {
            assertEquals("Keyset unbrauchbar", e.message)
        }
        assertFalse(
            "Es darf kein Klartext in der Datei stehen",
            output.toByteArray().decodeToString().contains("geheim")
        )
    }
}
