package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.security

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Factory für Preferences-DataStores, deren Datei komplett mit Tink AEAD (AES-256-GCM) verschlüsselt ist. */
object EncryptedDataStoreFactory {
    
    /**
     * Erstellt verschlüsselten Preferences DataStore
     * 
     * @param context Application context
     * @param name DataStore name (ohne .preferences_pb Suffix)
     * @param coroutineScope Optional: Custom scope (default: SupervisorJob + IO)
     * @return Verschlüsselter DataStore<Preferences>
     */
    fun create(
        context: Context,
        name: String,
        coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ): DataStore<Preferences> {
        
        Logger.d(LogTags.TOKEN, "🔐 Creating encrypted DataStore: $name")
        
        val encryptionHelper = TinkEncryptionHelper.getInstance(context)
        
        return DataStoreFactory.create(
            serializer = EncryptedPreferencesSerializer(encryptionHelper),
            // corruptionHandler: PFLICHT, DataStore liest vor jedem Schreiben - ohne Handler macht
            // ein kaputter Ciphertext den Store dauerhaft SCHREIB-tot. Er wirft den wertlosen Token
            // weg, erzwingt EINE Neuanmeldung (Skill cfalarm-persistenz-und-auth,
            // reference/persistenz.md).
            //
            // ZWEI Ursachen, EIN Handler — und sie heilen NICHT gleich weit (deshalb der
            // isAvailable()-Log, der sie im Nachhinein unterscheidbar macht):
            //  * Ciphertext/Datei kaputt, Keyset intakt -> vollständig geheilt: der Ersatz-Write
            //    gelingt regulär verschlüsselt, der nächste Token wird normal gespeichert.
            //  * Keyset unbrauchbar (invalidierter Keystore-Key, Geräte-Restore) -> nur die
            //    Leseseite heilt. `EncryptedPreferencesSerializer.writeTo()` verschlüsselt mit
            //    derselben aead-Instanz, die beim Lesen schon gescheitert ist; ein leerer Zustand
            //    kommt dank des dortigen Notausgangs (0-Byte-Datei) trotzdem auf die Platte, ein
            //    echter Token aber nicht. Ein NEUES Keyset kann nur `TinkEncryptionHelper`
            //    aufbauen (Keyset-SharedPrefs löschen + Singleton neu bauen) — bis dahin bleibt
            //    "App-Daten löschen" der einzige Weg. Nicht behaupten, das sei schon geheilt.
            corruptionHandler = ReplaceFileCorruptionHandler {
                Logger.w(
                    LogTags.TOKEN,
                    "⚠️ SECURITY: Verschluesselter Store '$name' war unlesbar und wurde durch einen " +
                        "leeren Zustand ersetzt - eine Neuanmeldung ist noetig " +
                        "(Verschluesselung verfuegbar: ${encryptionHelper.isAvailable()} - " +
                        "false = Keyset defekt, ein neuer Token laesst sich bis zum Keyset-Neuaufbau " +
                        "NICHT speichern)"
                )
                emptyPreferences()
            },
            scope = coroutineScope,
            produceFile = {
                File(context.filesDir, "datastore/$name.preferences_pb")
            }
        )
    }
}

/**
 * Custom Serializer für verschlüsselte Preferences
 *
 * Verschlüsselt die gesamte Preferences-Datei mit Tink AEAD
 *
 * `internal` (nicht private) ausschliesslich, damit das Übersetzen von
 * [TinkEncryptionException] in eine [CorruptionException] testbar bleibt.
 */
internal class EncryptedPreferencesSerializer(
    private val encryptionHelper: TinkEncryptionHelper
) : Serializer<Preferences> {
    
    // Standard Preferences Serializer für De-/Serialization
    private val delegateSerializer = androidx.datastore.preferences.core.PreferencesSerializer
    
    override val defaultValue: Preferences
        get() = delegateSerializer.defaultValue
    
    /**
     * Liest und entschlüsselt Preferences
     */
    override suspend fun readFrom(input: InputStream): Preferences {
        return try {
            val encryptedBytes = input.readBytes()
            
            if (encryptedBytes.isEmpty()) {
                // Leere Datei = keine Daten
                Logger.d(LogTags.TOKEN, "📄 Empty encrypted file, returning default preferences")
                return defaultValue
            }
            
            val decryptedBytes = encryptionHelper.decrypt(encryptedBytes)
            Logger.d(LogTags.TOKEN, "🔓 Decrypted ${encryptedBytes.size} bytes -> ${decryptedBytes.size} bytes")
            
            // PreferencesSerializer benötigt BufferedSource (Okio)
            val decryptedStream = decryptedBytes.inputStream()
            val decryptedSource = decryptedStream.source().buffer()
            val preferences = delegateSerializer.readFrom(decryptedSource)
            Logger.d(LogTags.TOKEN, "✅ Preferences loaded successfully (${preferences.asMap().size} keys)")
            
            preferences
            
        } catch (e: TinkEncryptionException) {
            // Verschlüsselung fehlgeschlagen (Tampering? Keyset unbrauchbar?)
            //
            // WICHTIG: als CorruptionException weiterwerfen, NICHT als TinkEncryptionException.
            // DataStores Selbstheilungspfad (corruptionHandler) fängt ausschliesslich
            // CorruptionException; eine TinkEncryptionException (erbt direkt von Exception) macht
            // den Store dauerhaft lese- UND schreib-tot, weil DataStore vor jedem Schreiben liest.
            Logger.e(LogTags.TOKEN, "❌ SECURITY: Decryption failed - possible tampering!", e)
            throw CorruptionException("Verschluesselte Preferences nicht entschluesselbar", e)
        } catch (e: Exception) {
            // Kein stilles `defaultValue` (der nächste Write überschriebe den intakten Ciphertext) und
            // unverändert weiterwerfen: IO/Cancellation dürfen den Handler nie auslösen, ein defektes
            // Protobuf meldet der delegateSerializer schon selbst als CorruptionException
            // (Skill cfalarm-persistenz-und-auth, reference/persistenz.md).
            Logger.e(LogTags.TOKEN, "❌ Failed to read encrypted preferences", e)
            throw e
        }
    }
    
    /**
     * Verschlüsselt und schreibt Preferences
     */
    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        try {
            // PreferencesSerializer benötigt BufferedSink (Okio)
            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            val bufferedSink = byteArrayOutputStream.sink().buffer()
            delegateSerializer.writeTo(t, bufferedSink)
            bufferedSink.flush()
            val preferencesBytes = byteArrayOutputStream.toByteArray()
            
            val encryptedBytes = try {
                encryptionHelper.encrypt(preferencesBytes)
            } catch (e: TinkEncryptionException) {
                // NOTAUSGANG genau für den Fall, für den der corruptionHandler gebaut ist: ist das
                // Keyset unbrauchbar, scheitert nicht nur das Lesen, sondern auch der ERSATZ-Write
                // des Handlers (dieselbe lazy aead-Instanz) — und der Store bliebe dauerhaft lese-
                // UND schreib-tot, also genau die Endlosschleife, die der Handler verhindern soll.
                //
                // Ein LEERER Zustand braucht keine Verschlüsselung: eine 0-Byte-Datei liest
                // readFrom() bereits als "noch nie etwas gespeichert". Damit gelingt die Ersetzung
                // und die Leseseite ist wieder gesund. Ausschliesslich für den leeren Zustand -
                // echte Token-Daten werden NIEMALS unverschlüsselt geschrieben, die scheitern
                // weiterhin laut (siehe throw unten).
                if (t.asMap().isEmpty()) {
                    Logger.w(
                        LogTags.TOKEN,
                        "⚠️ SECURITY: Verschluesselung nicht verfuegbar (Keyset defekt?) - LEERER " +
                            "Zustand wird als 0-Byte-Datei geschrieben, damit der Store wieder " +
                            "lesbar wird. Ein neuer Token laesst sich bis zum Keyset-Neuaufbau nicht " +
                            "speichern.",
                        e
                    )
                    output.flush()
                    return
                }
                throw e
            }
            Logger.d(LogTags.TOKEN, "🔐 Encrypted ${preferencesBytes.size} bytes -> ${encryptedBytes.size} bytes")
            
            output.write(encryptedBytes)
            output.flush()
            
            Logger.d(LogTags.TOKEN, "✅ Preferences encrypted and saved (${t.asMap().size} keys)")
            
        } catch (e: TinkEncryptionException) {
            Logger.e(LogTags.TOKEN, "❌ CRITICAL: Encryption failed", e)
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to write encrypted preferences", e)
            throw e
        }
    }
}
