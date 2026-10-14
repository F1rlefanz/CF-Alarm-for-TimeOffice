package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Die echte Audio-Anbindung der [WecktonProbe]: MediaPlayer und Audio-Fokus.
 *
 * Gleiche Tonquelle ([WecktonQuellen]) und gleiche Attribute (`USAGE_ALARM`) wie der Wecker -
 * dadurch spielt die Probe ueber den Alarm-Lautstaerkeregler, wie der Wecker auch. Mit
 * `USAGE_MEDIA` hoerte man etwas anderes als das, womit man geweckt wird.
 *
 * Entsteht erst beim ersten Probehoeren (siehe [WecktonProbe], "Direct Boot").
 */
internal class AndroidProbeAudio(private val context: Context) : ProbeAudio {

    private var fokusAnfrage: AudioFocusRequest? = null

    private fun audioManager(): AudioManager =
        context.getSystemService(AudioManager::class.java)

    override fun fordereFokus(beiVerlust: () -> Unit): Boolean {
        val anfrage = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_ATTRIBUTE)
            // Jeder negative Wert ist ein Verlust - auch LOSS_TRANSIENT_CAN_DUCK: die Probe ist
            // verzichtbar und duckt sich nicht, sie hoert auf.
            .setOnAudioFocusChangeListener(
                { aenderung -> if (aenderung < 0) beiVerlust() },
                Handler(Looper.getMainLooper())
            )
            .build()
        fokusAnfrage = anfrage
        return audioManager().requestAudioFocus(anfrage) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun gibFokusFrei() {
        val anfrage = fokusAnfrage ?: return
        fokusAnfrage = null
        audioManager().abandonAudioFocusRequest(anfrage)
    }

    override fun spiele(startPegel: Float, beiFehler: () -> Unit): ProbeKlang? {
        for (quelle in WecktonQuellen.kandidaten()) {
            // Lokale Referenz VOR dem Konfigurieren, damit ein scheiterndes setDataSource() den
            // frisch erzeugten Player nicht unerreichbar zuruecklaesst (Muster aus dem Wecker).
            var player: MediaPlayer? = null
            try {
                player = MediaPlayer()
                val klang = MediaPlayerKlang(player, startPegel)
                player.setDataSource(context, quelle)
                player.setAudioAttributes(ALARM_ATTRIBUTE)
                // Die Systemtoene sind oft kuerzer als die Probe - ohne Schleife verstummte sie
                // mitten im Anstieg.
                player.isLooping = true
                // Startpegel VOR start(): sonst begaenne der Anstieg mit einem vollen Ton.
                player.setVolume(startPegel, startPegel)
                player.setOnPreparedListener { bereit -> klang.starte(bereit, beiFehler) }
                player.setOnErrorListener { _, was, extra ->
                    Logger.w(LogTags.ALARM, "Probehoeren: Player-Fehler what=$was extra=$extra")
                    beiFehler()
                    true
                }
                player.prepareAsync()
                return klang
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Probehoeren: Weckton-Quelle nicht nutzbar: $quelle", e)
                player?.let { try { it.release() } catch (_: Exception) {} }
            }
        }
        return null
    }

    /**
     * Ein Player plus der Pegel, den er haben soll. Der Pegel wird gemerkt, weil der Anstieg
     * schon laeuft, waehrend der Player sich noch vorbereitet - beim Start gilt der aktuelle
     * Stand, nicht der vom Anfang.
     */
    private class MediaPlayerKlang(
        private val player: MediaPlayer,
        @Volatile private var pegel: Float
    ) : ProbeKlang {

        @Volatile
        private var freigegeben = false

        fun starte(bereit: MediaPlayer, beiFehler: () -> Unit) {
            // Ein spaetes onPrepared nach stop() darf den Ton nicht doch noch anwerfen.
            if (freigegeben) return
            try {
                bereit.setVolume(pegel, pegel)
                bereit.start()
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Probehoeren: Start gescheitert", e)
                beiFehler()
            }
        }

        override fun setzePegel(pegel: Float) {
            this.pegel = pegel
            if (freigegeben) return
            // setVolume() wirft auf einem Player im Fehlerzustand; geschluckt, wie im Wecker.
            try {
                player.setVolume(pegel, pegel)
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Probehoeren: Pegel $pegel nicht setzbar", e)
            }
        }

        override fun freigeben() {
            if (freigegeben) return
            freigegeben = true
            try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
    }

    private companion object {
        val ALARM_ATTRIBUTE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
