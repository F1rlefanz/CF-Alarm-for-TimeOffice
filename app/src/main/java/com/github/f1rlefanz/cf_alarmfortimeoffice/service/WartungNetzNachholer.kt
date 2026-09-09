package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt einen an fehlendem Netz gescheiterten Wartungslauf nach, SOBALD wieder Netz da ist -
 * statt bis zum naechsten regulaeren 6h-Lauf zu warten.
 *
 * WARUM WORKMANAGER UND KEIN TIMER: Ein fester Nachholabstand raet ins Blaue - er ist entweder zu
 * kurz (weckt das Geraet im Funkloch immer wieder ohne Aussicht auf Erfolg) oder zu lang. Die
 * Bedingung, auf die es ankommt, kennt das System bereits: [NetworkType.CONNECTED]. WorkManager
 * haelt sie in seiner eigenen Datenbank vor, also ueber Prozesstod UND Neustart hinweg - ein
 * `ConnectivityManager`-Callback im laufenden Prozess koennte das nicht, denn der Prozess einer
 * Wecker-App ist zwischen zwei Wartungslaeufen regelmaessig gar nicht da.
 *
 * KEIN ZWEITER PLANER DER 6h-KETTE (die Invariante aus CLAUDE.md gilt weiter): das hier ist ein
 * EINMALIGER Auftrag, der sich nicht selbst nachstellt und keinen AlarmManager-Slot belegt. Er
 * startet nur denselben Wartungslauf, den auch `TimezoneChangeReceiver` anstoesst; dessen
 * `finally` zieht die Kette anschliessend regulaer weiter.
 *
 * ANGEFORDERT wird er ausschliesslich von `AlarmMaintenanceService.behandleTokenFehlschlag`, und
 * nur bei nachgewiesener Netzursache und gedeckelt - siehe
 * [WartungTokenFehler.MAX_NETZ_NACHHOLVERSUCHE].
 */
@Singleton
class WartungNetzNachholer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val WORK_NAME = "wartung_netz_nachhol"
    }

    /**
     * [ExistingWorkPolicy.KEEP], nicht REPLACE: liegt schon ein Auftrag bereit, ist er derselbe.
     * REPLACE wuerde ihn bei jedem Fehlschlag neu in die Warteschlange stellen und damit sein
     * Alter - und einen eventuellen Backoff - verlieren.
     *
     * Alles in try/catch und ohne Rueckgabewert: eine gescheiterte Nachhol-Anmeldung ist ein
     * Komfortverlust, der den laufenden Wartungslauf nicht abbrechen darf.
     */
    fun merkeVor() {
        try {
            val auftrag = OneTimeWorkRequestBuilder<WartungNetzNachholWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // WorkManager.getInstance() bewusst ERST HIER, nicht in einem Feld dieser Klasse:
            // sie haengt als @Singleton am Application-Graphen, und der wird auch im
            // Direct-Boot-Prozess aufgebaut, wo WorkManager nicht angefasst werden darf.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, auftrag)

            Logger.business(
                LogTags.MAINTENANCE,
                "🌐 WARTUNG: Nachholung angemeldet - laeuft, sobald wieder Netz da ist"
            )
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "⚠️ WARTUNG: Netz-Nachholung konnte nicht angemeldet werden", e)
        }
    }

    /** Nach einem gueltigen Token aufzurufen: ein noch wartender Auftrag ist gegenstandslos. */
    fun verwirf() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "⚠️ WARTUNG: Netz-Nachholung konnte nicht verworfen werden", e)
        }
    }
}

/**
 * Startet den nachzuholenden Wartungslauf, sobald WorkManager wieder Netz sieht.
 *
 * Braucht KEINE Injection (und damit keinen Hilt-EntryPoint wie `CalendarPreAlarmRefreshWorker`):
 * er stoesst nur den bestehenden Wartungslauf an, statt dessen Ablauf ein zweites Mal
 * nachzubauen. Genau darum geht dieser Weg auch ueber den Service und nicht direkt auf
 * `syncAlarms` - es soll EINE Wartungsimplementierung geben.
 *
 * `forceSync = true`, weil sonst das Lade-Gate greifen koennte: der ausgefallene Lauf hat den
 * Frische-Stempel nicht gesetzt, aber der Puffer der bestehenden Wecker reicht meist noch weit -
 * ohne Zwang wuerde ausgerechnet der Nachholversuch die Kalender-Abfrage ueberspringen.
 *
 * WIRD DER VORDERGRUND-START ABGELEHNT (Android 12+ laesst ihn aus einem WorkManager-Auftrag
 * heraus nicht zu, anders als beim Feuern eines exakten Alarms), faengt
 * [AlarmMaintenanceService.start] das selbst ab und holt per exaktem Alarm ~10 s spaeter nach.
 * Dieser Rueckfallpfad ist bereits vorhanden und gedeckelt - deshalb wird er hier bewusst
 * benutzt statt umgangen.
 */
class WartungNetzNachholWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Logger.business(LogTags.MAINTENANCE, "🌐 WARTUNG: Netz wieder da - Nachholung startet")
        return try {
            AlarmMaintenanceService.start(applicationContext, forceSync = true)
            Result.success()
        } catch (e: Exception) {
            // Kein Result.retry(): der Lauf selbst haengt nicht an diesem Auftrag, und die
            // regulaere 6h-Kette deckt denselben Zustand ohnehin ab.
            Logger.e(LogTags.MAINTENANCE, "❌ WARTUNG: Netz-Nachholung fehlgeschlagen", e)
            Result.success()
        }
    }
}
