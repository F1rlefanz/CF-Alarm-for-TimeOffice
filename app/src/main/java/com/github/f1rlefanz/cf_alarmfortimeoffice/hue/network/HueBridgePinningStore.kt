package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network

import android.content.Context
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Trust-On-First-Use (TOFU) store for the Hue Bridge identity ("bridgeid").
 *
 * PURPOSE
 * Philips/Signify Hue Bridge certificates carry the bridge's unique ID (a 16-character
 * hex string derived from its MAC address) in the certificate Subject Common Name.
 * This store remembers the bridge ID we've previously talked to, so future TLS
 * handshakes can be compared against a known-good identity - similar in spirit to
 * SSH host-key pinning.
 *
 * SECURITY MODEL - ADDITIVE ONLY, NEVER BLOCKING
 * This store is intentionally "dumb": it only remembers and compares. It never decides
 * to reject a connection by itself. [HueTrustManager] uses it purely to strengthen
 * audit logging and confidence signals. A missing or mismatched pin NEVER causes a
 * rejection - only the existing hybrid validation (system trust store, then Hue
 * certificate-pattern fallback) decides accept/reject. See [HueTrustManager] for the
 * full rationale on why this can never lock the user out of their own bridge.
 *
 * Deliberately uses its own small SharedPreferences namespace, separate from the
 * DataStore instances (@MainDataStore/@HueDataStore) used elsewhere in the app -
 * this is a lightweight, non-sensitive cache, not user config.
 */
class HueBridgePinningStore(context: Context) {

    /**
     * `by lazy` aus demselben Grund wie in `BackgroundServiceManager` (siehe dort): ein
     * sofortiger `getSharedPreferences()`-Aufruf auf dem CREDENTIAL-ENCRYPTED Context wirft in
     * einem Prozess, der vor der ersten Entsperrung startet. Diese Klasse haengt heute nicht am
     * eager Application-Graphen, aber sie ist ein Konstruktor-Argument im Hue-Netzwerkpfad, und
     * ob der je in einen frueh gebauten Graphen wandert, entscheidet eine kuenftige Aenderung -
     * nicht diese Datei.
     */
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    /** Returns the previously pinned bridge ID, or null if none has been recorded yet. */
    fun getPinnedBridgeId(): String? = prefs.getString(KEY_PINNED_BRIDGE_ID, null)

    /**
     * Records (or updates) the bridge ID we trust for future comparisons.
     *
     * Called after a certificate chain has ALREADY been accepted by the existing
     * hybrid validation. This never gates trust decisions itself - it only records
     * the outcome of a decision already made elsewhere.
     */
    fun pinBridgeId(bridgeId: String) {
        if (bridgeId.isBlank()) return

        val existing = getPinnedBridgeId()
        if (existing == bridgeId) return // Nothing to do - already pinned

        prefs.edit { putString(KEY_PINNED_BRIDGE_ID, bridgeId) }

        if (existing == null) {
            Logger.i(LogTags.HUE_NETWORK, "🔒 PINNING: First-use bridge identity recorded (TOFU): $bridgeId")
        } else {
            // Bridge ID changed since last pin. This can legitimately happen (bridge
            // replacement via Philips support, factory reset, etc.) so we only log -
            // never reject. Updating the pin here means the NEW identity becomes the
            // baseline for future comparisons.
            Logger.w(
                LogTags.HUE_NETWORK,
                "⚠️ PINNING: Bridge identity changed since last pin (was: $existing, now: $bridgeId). " +
                    "Updating pin - this is expected after bridge replacement/reset, but if unexpected, verify your bridge."
            )
        }
    }

    /** Clears the pin, e.g. when the user explicitly disconnects/forgets the bridge. */
    fun clearPin() {
        prefs.edit { remove(KEY_PINNED_BRIDGE_ID) }
        Logger.i(LogTags.HUE_NETWORK, "🔓 PINNING: Bridge identity pin cleared")
    }

    companion object {
        private const val PREFS_NAME = "hue_trust_pinning"
        private const val KEY_PINNED_BRIDGE_ID = "pinned_bridge_id"
    }
}
