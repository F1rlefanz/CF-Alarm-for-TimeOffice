package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.*

/**
 * Manages alarm audio playback with optimized settings for maximum audibility
 * and proper resource management.
 * 
 * Features:
 * - Audio focus management
 * - Doze-mode optimized audio attributes
 * - Automatic resource cleanup
 * - Volume optimization
 * - Fallback sound selection
 */
interface IAlarmAudioManager {
    /**
     * Starts alarm sound playback with optimized settings
     */
    suspend fun startAlarmSound(): Result<Unit>
    
    /**
     * Stops alarm sound and releases resources
     */
    suspend fun stopAlarmSound(): Result<Unit>
    
    /**
     * Checks if alarm is currently playing
     */
    fun isPlaying(): Boolean
    
    /**
     * Gets current audio configuration info
     */
    fun getAudioDebugInfo(): String
}

class AlarmAudioManager(
    private val context: Context,
    private val wakeLockManager: IWakeLockManager
) : IAlarmAudioManager {
    
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Logger.d(LogTags.ALARM_AUDIO, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Keep playing alarm even if we lose focus - this is critical!
                Logger.w(LogTags.ALARM_AUDIO, "Audio focus lost but continuing alarm playback")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Logger.d(LogTags.ALARM_AUDIO, "Audio focus gained")
            }
        }
    }
    
    override suspend fun startAlarmSound(): Result<Unit> {
        return try {
            // Stop any existing playback first
            stopAlarmSound()
            
            // Acquire wake lock for audio operations
            wakeLockManager.acquireAlarmWakeLock(
                WakeLockManager.TAG_ALARM_SOUND, 
                timeoutMs = 5 * 60 * 1000 // 5 minutes max
            )
            
            // Request audio focus
            requestAudioFocus()
            
            // Get best available alarm sound
            val alarmUri = getBestAlarmSound()
            
            if (alarmUri != null) {
                setupAndStartMediaPlayer(alarmUri)
                Logger.i(LogTags.ALARM_AUDIO, "🔊 Alarm sound started successfully")
                Result.success(Unit)
            } else {
                Logger.e(LogTags.ALARM_AUDIO, "❌ No alarm sound available")
                Result.failure(IllegalStateException("No alarm sound available"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_AUDIO, "❌ Failed to start alarm sound", e)
            stopAlarmSound() // Cleanup on failure
            Result.failure(e)
        }
    }
    
    override suspend fun stopAlarmSound(): Result<Unit> {
        return try {
            Logger.d(LogTags.ALARM_AUDIO, "🔇 Stopping alarm sound")
            
            // Stop MediaPlayer
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayer = null
            }
            
            // Release audio focus
            releaseAudioFocus()
            
            // Release wake lock
            wakeLockManager.releaseWakeLock(WakeLockManager.TAG_ALARM_SOUND)
            
            Logger.i(LogTags.ALARM_AUDIO, "✅ Alarm sound stopped and resources released")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_AUDIO, "❌ Error stopping alarm sound", e)
            Result.failure(e)
        }
    }
    
    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }
    
    private fun getBestAlarmSound(): Uri? {
        // Try different sound types in order of preference
        val soundTypes = listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_NOTIFICATION,
            RingtoneManager.TYPE_RINGTONE
        )
        
        for (type in soundTypes) {
            RingtoneManager.getDefaultUri(type)?.let { uri ->
                Logger.d(LogTags.ALARM_AUDIO, "Found sound URI for type $type: $uri")
                return uri
            }
        }
        
        Logger.w(LogTags.ALARM_AUDIO, "No default sounds found, using system sound")
        return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
    }
    
    private suspend fun setupAndStartMediaPlayer(uri: Uri) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer().apply {
            // Optimized audio attributes for alarm
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(
                        AudioAttributes.FLAG_AUDIBILITY_ENFORCED or
                        AudioAttributes.FLAG_HW_AV_SYNC
                    )
                    .build()
            )
            
            setDataSource(context, uri)
            
            // Maximum volume for alarm
            setVolume(1.0f, 1.0f)
            
            // Loop indefinitely until stopped
            isLooping = true
            
            // Error handling
            setOnErrorListener { mp, what, extra ->
                Logger.e(LogTags.ALARM_AUDIO, "MediaPlayer error: what=$what, extra=$extra")
                scope.launch {
                    stopAlarmSound()
                }
                true // Error handled
            }
            
            // Prepared listener
            setOnPreparedListener { mp ->
                Logger.d(LogTags.ALARM_AUDIO, "MediaPlayer prepared, starting playback")
                try {
                    mp.start()
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_AUDIO, "Error starting playback", e)
                }
            }
            
            // Async preparation
            prepareAsync()
        }
    }
    
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Modern audio focus API
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                
                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                Logger.d(LogTags.ALARM_AUDIO, "Audio focus request result: $result")
                
            } else {
                // Legacy audio focus API
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                Logger.d(LogTags.ALARM_AUDIO, "Legacy audio focus request result: $result")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_AUDIO, "Error requesting audio focus", e)
        }
    }
    
    private fun releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    audioManager.abandonAudioFocusRequest(request)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            Logger.d(LogTags.ALARM_AUDIO, "Audio focus released")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_AUDIO, "Error releasing audio focus", e)
        }
    }
    
    override fun getAudioDebugInfo(): String {
        return buildString {
            appendLine("=== Alarm Audio Debug ===")
            appendLine("MediaPlayer: ${mediaPlayer?.let { "Active (playing=${it.isPlaying})" } ?: "None"}")
            appendLine("Audio focus: ${audioFocusRequest?.let { "Requested" } ?: "None"}")
            appendLine("Stream volume (alarm): ${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}")
            appendLine("Stream max volume (alarm): ${audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
            appendLine("Audio mode: ${audioManager.mode}")
            appendLine("===========================")
        }
    }
    
    /**
     * Cleanup all resources
     */
    fun cleanup() {
        scope.launch {
            stopAlarmSound()
        }
        scope.cancel()
    }
}
