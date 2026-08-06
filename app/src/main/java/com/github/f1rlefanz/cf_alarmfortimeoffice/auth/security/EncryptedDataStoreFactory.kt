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

/**
 * Factory für verschlüsselte DataStore-Instanzen mit Tink Crypto
 * 
 * Features:
 * - ✅ Transparente Verschlüsselung via Custom Serializer
 * - ✅ Tink AEAD (AES-256-GCM)
 * - ✅ Keine API-Änderungen für DataStore-Nutzer
 * - ✅ Backward-kompatibel (mit Migration)
 * 
 * Usage:
 * ```kotlin
 * val dataStore = EncryptedDataStoreFactory.create(
 *     context = context,
 *     name = "my_secure_data"
 * )
 * ```
 */
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
            // corruptionHandler: PFLICHT, nicht Kosmetik. DataStore liest VOR JEDEM Schreiben erneut
            // (DataStoreImpl.transformAndWrite -> readDataOrHandleCorruption); ein Lesefehler macht
            // den Store deshalb nicht nur lese-, sondern dauerhaft SCHREIB-tot. Ohne Handler wäre
            // ein unbrauchbares Tink-Keyset (invalidierter Keystore-Key, Geräte-Restore) eine
            // Endlosschleife: Re-Login gelingt, `save()` scheitert am internen Read, der
            // Token-Verlust-Watcher schlägt sofort wieder zu — dauerhaft, ohne Selbstheilung.
            //
            // Abwägung bewusst so entschieden: Der Handler WIRFT den (nicht entschlüsselbaren und
            // damit ohnehin wertlosen) Token weg und erzwingt EINE Neuanmeldung. Das ist das
            // kleinere Übel gegenüber einer App, die nie wieder einen Token speichern kann.
            // Sicherheitsargument bleibt gewahrt: es wird nichts entschlüsselt ausgeliefert, die
            // unlesbare Datei wird durch einen LEEREN Zustand ersetzt.
            corruptionHandler = ReplaceFileCorruptionHandler {
                Logger.w(
                    LogTags.TOKEN,
                    "⚠️ SECURITY: Verschluesselter Store '$name' war unlesbar und wurde durch einen " +
                        "leeren Zustand ersetzt - eine Neuanmeldung ist noetig"
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
            // 1. Lese verschlüsselte Bytes
            val encryptedBytes = input.readBytes()
            
            if (encryptedBytes.isEmpty()) {
                // Leere Datei = keine Daten
                Logger.d(LogTags.TOKEN, "📄 Empty encrypted file, returning default preferences")
                return defaultValue
            }
            
            // 2. Entschlüssle mit Tink
            val decryptedBytes = encryptionHelper.decrypt(encryptedBytes)
            Logger.d(LogTags.TOKEN, "🔓 Decrypted ${encryptedBytes.size} bytes -> ${decryptedBytes.size} bytes")
            
            // 3. Deserialisiere Preferences mit delegateSerializer
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
            Logger.e(LogTags.TOKEN, "❌ Failed to read encrypted preferences", e)
            // Bei Fehler: Rückgabe default statt crash
            Logger.w(LogTags.TOKEN, "⚠️ Returning default preferences due to read error")
            defaultValue
        }
    }
    
    /**
     * Verschlüsselt und schreibt Preferences
     */
    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        try {
            // 1. Serialisiere Preferences zu Bytes
            // PreferencesSerializer benötigt BufferedSink (Okio)
            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            val bufferedSink = byteArrayOutputStream.sink().buffer()
            delegateSerializer.writeTo(t, bufferedSink)
            bufferedSink.flush()
            val preferencesBytes = byteArrayOutputStream.toByteArray()
            
            // 2. Verschlüssele mit Tink
            val encryptedBytes = encryptionHelper.encrypt(preferencesBytes)
            Logger.d(LogTags.TOKEN, "🔐 Encrypted ${preferencesBytes.size} bytes -> ${encryptedBytes.size} bytes")
            
            // 3. Schreibe verschlüsselte Bytes
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
