package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import androidx.datastore.core.CorruptionException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.EncryptedPreferencesSerializer
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security.TinkEncryptionHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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
}
