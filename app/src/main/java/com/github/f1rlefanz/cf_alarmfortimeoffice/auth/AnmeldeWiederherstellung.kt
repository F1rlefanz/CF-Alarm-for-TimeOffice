package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import android.os.Build
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateRestoreCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetRestoreCredentialOption
import androidx.credentials.RestoreCredential
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.restorecredential.E2eeUnavailableException
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-Tap-Wiederherstellung der Anmeldung auf einem neuen Geraet (#55, Play-Pflicht ab April 2027)
 * ueber die Restore Credentials API von `androidx.credentials`.
 *
 * Der Schluessel traegt nur die E-Mail-Adresse des Google-Kontos (siehe
 * [AnmeldeWiederherstellungJson]); er reist Ende-zu-Ende-verschluesselt im Google-Backup mit. Auf
 * dem neuen Geraet meldet [lesen] die Adresse, der Rest laeuft ueber denselben Weg wie eine
 * Anmeldung per Knopf (`authorize()` mit `setAccount()`).
 *
 * VERTRAG FUER ALLE DREI AUFRUFE: sie WERFEN NIE (ausser `CancellationException`, damit ein
 * Zeitdeckel des Aufrufers greift). Die Funktion ist eine Bequemlichkeit - sie darf weder die
 * Anmeldung noch das Abmelden je aufhalten. Die Zeitdeckel setzt der Aufrufer
 * (`AuthViewModel`), damit sie dort im Test pruefbar sind.
 */
interface AnmeldeWiederherstellung {

    /** Legt den Schluessel fuer [email] an (ersetzt einen vorhandenen). `true` = angelegt. */
    suspend fun anlegen(activityContext: Context, email: String): Boolean

    /** Die E-Mail aus einem vorhandenen Schluessel, sonst `null` (keiner, Fehler, nicht verfuegbar). */
    suspend fun lesen(activityContext: Context): String?

    /** Loescht den Schluessel. Gehoert zum Abmelden - "nichts bleibt zurueck". */
    suspend fun loeschen()

    /**
     * Geraetelokaler Vermerk "auf DIESEM Geraet wurde ausdruecklich abgemeldet". Solange er
     * steht, darf ein abgemeldeter Start nicht still wieder anmelden - auch wenn noch ein
     * Schluessel da ist, weil [loeschen] gescheitert, abgelaufen oder durch Prozesstod nie
     * angekommen ist, oder weil ein abgelaufenes [anlegen] in den Play-Diensten verspaetet
     * geschrieben hat.
     *
     * Er reist NICHT mit (weder Backup noch Geraetetransfer): auf einem neuen Geraet soll die
     * Wiederherstellung ja gerade greifen. Und er hat eine Gegenfrage, anders als der 2026
     * verworfene dauerhafte Abmelde-Merker (Skill `cfalarm-persistenz-und-auth`): er wird
     * geraeumt, sobald wieder jemand angemeldet ist ([vergissAbmeldung] vor jedem Anlegen),
     * und er sperrt NUR das Lesen des Schluessels - keine Wartung, keinen Wecker.
     */
    suspend fun merkeAbmeldung()

    /** Ob [merkeAbmeldung] gilt. Nicht lesbar heisst `true`: im Zweifel nicht still anmelden. */
    suspend fun istAbmeldungVermerkt(): Boolean

    /** Raeumt den Vermerk - es ist wieder jemand angemeldet. */
    suspend fun vergissAbmeldung()

    /**
     * Die abgeschaltete Variante: legt nichts an, findet nichts, loescht nichts. Fuer Unit-Tests,
     * die die Funktion nicht betrachten - sie reichen sie dem `AuthViewModel` AUSDRUECKLICH
     * herein (es gibt bewusst keinen Konstruktor, der sie still einsetzt).
     */
    object Aus : AnmeldeWiederherstellung {
        override suspend fun anlegen(activityContext: Context, email: String): Boolean = false
        override suspend fun lesen(activityContext: Context): String? = null
        override suspend fun loeschen() = Unit
        override suspend fun merkeAbmeldung() = Unit
        override suspend fun istAbmeldungVermerkt(): Boolean = false
        override suspend fun vergissAbmeldung() = Unit
    }
}

/**
 * Umsetzung ueber den `CredentialManager` der Play-Dienste. Gebunden an die Schnittstelle in
 * `di/modules/RepositoryModule`.
 *
 * DIRECT BOOT: Der `CredentialManager` wird erst IM AUFRUF geholt, nie beim Bauen - diese Klasse
 * haengt als Singleton am Application-Graphen, der auch im Direct-Boot-Prozess entsteht (gleiche
 * Regel wie `PlayDiensteKalenderAutorisierung`).
 *
 * API < 28: Die Restore Credentials API verlangt Android 9. Darunter ist die Funktion still aus -
 * diese Geraete melden sich wie bisher per Knopf an.
 *
 * KEIN EIGENER `BackupAgent` (Entscheidung 02.10.2026): gelesen wird beim ersten Start, das
 * genuegt, weil `auth_prefs` nicht mitreist und man dort also abgemeldet ankommt. Ein Agent
 * aenderte dagegen das Backup-Verhalten der ganzen App.
 */
@Singleton
class PlayDiensteAnmeldeWiederherstellung @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : AnmeldeWiederherstellung {

    private val zufall = SecureRandom()

    private val verfuegbar: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override suspend fun anlegen(activityContext: Context, email: String): Boolean {
        if (!verfuegbar) return false
        val anfrageJson = AnmeldeWiederherstellungJson.baueCreateJson(email, challenge())
        if (anfrageJson == null) {
            // Bewusst ohne die Adresse: WARN landet im Release-Log.
            Logger.w(
                LogTags.AUTH,
                "Restore-Schluessel: E-Mail passt nicht in user.id (max. " +
                    "${AnmeldeWiederherstellungJson.MAX_USER_ID_BYTES} Byte) - kein Schluessel, " +
                    "es bleibt beim normalen Login"
            )
            return false
        }
        return try {
            val manager = CredentialManager.create(activityContext)
            try {
                manager.createCredential(
                    activityContext,
                    CreateRestoreCredentialRequest(anfrageJson, isCloudBackupEnabled = true)
                )
                Logger.business(LogTags.AUTH, "Restore-Schluessel angelegt (mit Cloud-Backup)")
            } catch (e: E2eeUnavailableException) {
                // Kein Backup oder keine Bildschirmsperre: der Schluessel liegt dann nur lokal
                // und kommt nur per Geraet-zu-Geraet-Transfer mit. Besser als keiner.
                Logger.w(
                    LogTags.AUTH,
                    "Restore-Schluessel: Ende-zu-Ende-Backup nicht verfuegbar - lege ihn nur lokal an"
                )
                manager.createCredential(
                    activityContext,
                    CreateRestoreCredentialRequest(anfrageJson, isCloudBackupEnabled = false)
                )
                Logger.business(LogTags.AUTH, "Restore-Schluessel angelegt (nur lokal)")
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel konnte nicht angelegt werden", e)
            false
        }
    }

    override suspend fun lesen(activityContext: Context): String? {
        if (!verfuegbar) return null
        return try {
            val anfrage = GetCredentialRequest(
                listOf(GetRestoreCredentialOption(AnmeldeWiederherstellungJson.baueGetJson(challenge())))
            )
            val antwort = CredentialManager.create(activityContext).getCredential(activityContext, anfrage)
            val credential = antwort.credential
            if (credential !is RestoreCredential) {
                Logger.w(LogTags.AUTH, "Restore-Schluessel: unerwarteter Credential-Typ ${credential.type}")
                return null
            }
            val email = AnmeldeWiederherstellungJson.leseEmailAusAntwort(credential.authenticationResponseJson)
            if (email == null) {
                Logger.w(LogTags.AUTH, "Restore-Schluessel gefunden, aber ohne lesbare Kontoangabe")
            }
            email
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoCredentialException) {
            // Der Normalfall: frische Installation ohne Vorgaenger, oder abgemeldet.
            Logger.d(LogTags.AUTH, "Kein Restore-Schluessel vorhanden")
            null
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel nicht lesbar - normaler Login", e)
            null
        }
    }

    override suspend fun loeschen() {
        if (!verfuegbar) return
        try {
            CredentialManager.create(appContext).clearCredentialState(
                ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_RESTORE_CREDENTIAL)
            )
            Logger.business(LogTags.AUTH, "Restore-Schluessel geloescht")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel konnte nicht geloescht werden", e)
        }
    }

    // Der Vermerk liegt als leere Datei in `noBackupFilesDir`: dieses Verzeichnis nimmt Android
    // weder ins Auto-Backup noch in den Geraetetransfer auf - also KEINE Regel in den beiden
    // Backup-Dateien noetig, und nicht `auth_prefs`, das `clearAuthData()` beim Abmelden leert.
    // Der Pfad wird erst IM AUFRUF aufgeloest (CE-Storage, siehe Klassen-KDoc zu Direct Boot).
    private fun abmeldeVermerk(): File = File(appContext.noBackupFilesDir, ABMELDE_VERMERK_DATEI)

    override suspend fun merkeAbmeldung() {
        try {
            withContext(Dispatchers.IO) { abmeldeVermerk().createNewFile() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel: Abmelde-Vermerk nicht gespeichert", e)
        }
    }

    override suspend fun istAbmeldungVermerkt(): Boolean =
        try {
            withContext(Dispatchers.IO) { abmeldeVermerk().exists() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel: Abmelde-Vermerk nicht lesbar - keine Wiederherstellung", e)
            true
        }

    override suspend fun vergissAbmeldung() {
        try {
            withContext(Dispatchers.IO) { abmeldeVermerk().delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Restore-Schluessel: Abmelde-Vermerk nicht geraeumt", e)
        }
    }

    private fun challenge(): ByteArray =
        ByteArray(AnmeldeWiederherstellungJson.CHALLENGE_BYTES).also { zufall.nextBytes(it) }

    private companion object {
        const val ABMELDE_VERMERK_DATEI = "restore_nach_abmelden_gesperrt"
    }
}
