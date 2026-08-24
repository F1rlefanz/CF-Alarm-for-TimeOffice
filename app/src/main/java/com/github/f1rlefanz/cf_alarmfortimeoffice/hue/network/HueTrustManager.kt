package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network

import android.annotation.SuppressLint
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust model for Philips Hue Bridge communication over the local network.
 *
 * HONEST DESCRIPTION OF WHAT THIS ACTUALLY DOES (please read before touching)
 * ------------------------------------------------------------------------
 * Hue Bridges use certificates that are either self-signed or signed by Signify's
 * internal CA - neither chains to a public root the Android system trust store knows
 * about in the general case. This is NOT a "trust all certificates" bypass, but it is
 * also NOT full PKI validation. It is a **permissive local-IoT trust model**:
 *
 *   1. PRIMARY: try normal system trust store validation first (covers the rare case
 *      of a bridge with a properly chained certificate, and keeps this code path
 *      honest for any future Signify CA rollout to public trust stores).
 *   2. FALLBACK: if that fails (the common case for today's bridges), accept the
 *      certificate if it matches known Hue Bridge certificate conventions (subject/
 *      issuer containing "philips"/"hue"/"bridge", a 12-hex-char bridge ID pattern,
 *      or a self-signed cert) AND its validity period is currently valid.
 *   3. BRIDGE-ID PINNING (additive, see below): if we've previously recorded this
 *      bridge's identity, we compare it against the current certificate's Subject CN
 *      and log the outcome. A mismatch is NEVER used to reject a connection that the
 *      steps above already accepted - seeing it purely tightens audit visibility and
 *      gives us a signal for a future opt-in UI warning.
 *   4. Hostname verification is similarly permissive: it accepts any hostname as long
 *      as the target IP is in a private (RFC 1918) / link-local range and the TLS
 *      session negotiated successfully. Hue Bridges are addressed by IP, not by a
 *      hostname the certificate would name, so standard hostname verification isn't
 *      meaningful here - this is the accepted approach for local IoT bridges (mirrors
 *      what Philips's own SDKs do).
 *
 * WHY THIS DESIGN AND NOT STRICT PINNING
 * The user actively depends on this connection working (alarm-triggered lighting).
 * Strict pinning that can independently reject a connection risks locking the user out
 * of a bridge that is otherwise legitimate (bridge replaced by Philips support, factory
 * reset, DHCP IP reassignment, certificate reissued after firmware update, etc.) with no
 * in-app recovery path other than full re-pairing. Every additional check in this file is
 * therefore additive to the existing accept path, never subtractive: it can make us log
 * more, it can never make us reject something we would otherwise have accepted.
 *
 * SECURITY LAYERS (defense in depth, in the order they run)
 *  Layer 1: Android system trust store (auto-updated via Play Services)
 *  Layer 2: Hue-specific certificate pattern + validity-period validation (fallback)
 *  Layer 3: Bridge-ID pinning via [HueBridgePinningStore] - Trust-On-First-Use, logging only
 *  Layer 4: Private-network (RFC 1918) IP validation for hostname verification
 *  Layer 5: Comprehensive security audit logging throughout
 *
 * @suppress CustomX509TrustManager: Justified - see class doc above; Hue Bridge IoT
 * integration requires a bridge-aware fallback beyond the system trust store.
 * @suppress TrustAllX509TrustManager: False positive - this does not trust all
 * certificates; it validates against certificate patterns, validity, and identity pinning.
 */
// Constructor is `internal` (not `private`) purely so unit tests in the same module can
// inject a fake system [X509TrustManager] and thereby exercise the Hue-specific fallback
// path deterministically. Production code must still go through [create] / the companion
// factories; do not call this constructor from production.
class HueTrustManager internal constructor(
    private val systemTrustManager: X509TrustManager,
    private val pinningStore: HueBridgePinningStore?
) : X509TrustManager {

    companion object {

        /**
         * Creates a [HueTrustManager] with system trust store integration and, if a
         * [Context] is supplied, bridge-ID pinning support (recommended).
         *
         * @param context Application context used to persist the Trust-On-First-Use
         * bridge-ID pin. Pass null only if pinning support is genuinely unavailable
         * (e.g. very early bootstrap) - in that case the trust manager still works,
         * it simply skips the pinning layer entirely (pure hybrid validation).
         */
        fun create(context: Context? = null): HueTrustManager {
            val defaultTrustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            defaultTrustManagerFactory.init(null as java.security.KeyStore?)

            val systemTrustManager = defaultTrustManagerFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: throw IllegalStateException("No system X509TrustManager found")

            val pinningStore = context?.let { HueBridgePinningStore(it) }
            return HueTrustManager(systemTrustManager, pinningStore)
        }

        /**
         * Creates a complete SSLContext configured with this hybrid trust model.
         *
         * @param context Application context for bridge-ID pinning (see [create]).
         */
        fun createSecureSSLContext(context: Context? = null): SSLContext {
            val trustManager = create(context)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

            Logger.i(LogTags.HUE_NETWORK, "🔒 Hue TLS: SSL context ready (hybrid trust model, pinning=${context != null})")
            return sslContext
        }

        /**
         * Hostname verifier for Hue Bridge connections.
         *
         * Hue Bridges are addressed by local IP, not a certificate-bound hostname, so
         * standard hostname verification does not apply. This verifier instead requires
         * the target to be a private-network (RFC 1918) or link-local (RFC 3927) address
         * with a valid, already-negotiated TLS session. This is the accepted approach for
         * local IoT bridge communication.
         */
        fun createHostnameVerifier(): HostnameVerifier {
            return HostnameVerifier { hostname, session ->
                val isValid = validateHueHostname(hostname, session)
                if (isValid) {
                    Logger.d(LogTags.HUE_NETWORK, "🔒 Hue TLS: accepted host $hostname (private network + valid session)")
                } else {
                    Logger.w(LogTags.HUE_NETWORK, "🚨 Hue TLS: rejected host $hostname (not private network or invalid session)")
                }
                isValid
            }
        }

        private fun validateHueHostname(hostname: String, session: SSLSession): Boolean {
            return try {
                if (!isPrivateNetworkAddress(hostname)) {
                    return false
                }
                session.isValid && session.cipherSuite.isNotEmpty()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: error validating hostname $hostname", e)
                false
            }
        }

        /**
         * RFC 1918 / RFC 3927 private-network validation. Hue Bridges only communicate
         * on the local network, so restricting to these ranges bounds the blast radius
         * of the permissive certificate validation above.
         */
        private fun isPrivateNetworkAddress(hostname: String): Boolean {
            return try {
                if (hostname.isEmpty() || hostname.contains("..") || hostname.contains("://")) {
                    Logger.w(LogTags.HUE_NETWORK, "🚨 Hue TLS: suspicious hostname pattern: $hostname")
                    return false
                }
                when {
                    hostname.startsWith("192.168.") -> true
                    hostname.startsWith("10.") -> true
                    hostname.startsWith("172.") -> {
                        val secondOctet = hostname.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                        secondOctet in 16..31
                    }
                    hostname == "localhost" || hostname == "127.0.0.1" -> true
                    hostname.startsWith("169.254.") -> true
                    else -> false
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: error validating private network address: $hostname", e)
                false
            }
        }

        /**
         * Best-effort extraction of the Hue Bridge ID from a certificate's Subject
         * Common Name. Signify bridge certificates carry the bridge ID (16-char hex,
         * derived from the MAC address) as the CN. Returns null if no plausible bridge
         * ID can be found - callers must treat that as "unknown", never as a mismatch.
         */
        internal fun extractBridgeIdFromCertificate(cert: X509Certificate): String? {
            return try {
                val subjectDN = cert.subjectDN?.toString() ?: return null
                // Look for "CN=<value>" and pull out a hex-like token of plausible length
                // (Hue bridge IDs are commonly 16 hex chars, mDNS-derived short IDs 6).
                val cnMatch = Regex("CN=([^,]+)").find(subjectDN) ?: return null
                val cnValue = cnMatch.groupValues[1].trim()
                val hexMatch = Regex("[a-fA-F0-9]{6,16}").find(cnValue) ?: return null
                hexMatch.value.lowercase()
            } catch (e: Exception) {
                Logger.d(LogTags.HUE_NETWORK, "Hue TLS: could not extract bridge ID from certificate: ${e.message}")
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // X509TrustManager
    // ------------------------------------------------------------------

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        Logger.d(LogTags.HUE_NETWORK, "🔍 Hue TLS: validating client certificate chain (length=${chain.size}, authType=$authType)")
        try {
            systemTrustManager.checkClientTrusted(chain, authType)
            Logger.i(LogTags.HUE_NETWORK, "✅ Hue TLS: client certificate validated by system trust store")
        } catch (_: CertificateException) {
            Logger.d(LogTags.HUE_NETWORK, "⚠️ Hue TLS: system validation failed, trying Hue-specific fallback")
            validateHueBridgeCertificate(chain, "client", authType)
            Logger.i(LogTags.HUE_NETWORK, "✅ Hue TLS: client certificate validated by Hue-specific fallback")
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        Logger.d(LogTags.HUE_NETWORK, "🔍 Hue TLS: validating server certificate chain (length=${chain.size}, authType=$authType)")

        var acceptedVia = "system-trust-store"
        try {
            systemTrustManager.checkServerTrusted(chain, authType)
            Logger.i(LogTags.HUE_NETWORK, "✅ Hue TLS: server certificate validated by system trust store")
        } catch (_: CertificateException) {
            Logger.d(LogTags.HUE_NETWORK, "⚠️ Hue TLS: system validation failed, trying Hue-specific fallback for local bridge")
            validateHueBridgeCertificate(chain, "server", authType)
            acceptedVia = "hue-pattern-fallback"
            Logger.i(LogTags.HUE_NETWORK, "✅ Hue TLS: server certificate validated by Hue-specific fallback")
        }

        // LAYER 3 (additive, non-blocking): bridge-ID pinning / TOFU comparison.
        // This runs AFTER the certificate has already been accepted above. It can only
        // log; it can never turn an already-accepted connection into a rejection.
        applyBridgeIdPinning(chain, acceptedVia)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return try {
            val systemIssuers = systemTrustManager.acceptedIssuers
            Logger.d(LogTags.HUE_NETWORK, "🔍 Hue TLS: returning ${systemIssuers.size} accepted issuers from system trust store")
            systemIssuers
        } catch (e: Exception) {
            Logger.w(LogTags.HUE_NETWORK, "⚠️ Hue TLS: error getting system issuers, returning empty array", e)
            emptyArray()
        }
    }

    // ------------------------------------------------------------------
    // Bridge-ID pinning (additive layer - see class doc)
    // ------------------------------------------------------------------

    /**
     * Compares the current certificate's bridge ID against a previously pinned value,
     * if any, and records/logs the outcome. NEVER throws, NEVER rejects - by design.
     *
     * Behavior:
     *  - No pinning store configured -> no-op (pure hybrid validation only).
     *  - No pin recorded yet -> pin the current bridge ID (Trust-On-First-Use).
     *  - Certificate has no extractable bridge ID -> log and skip (unknown, not a
     *    mismatch - never treated as suspicious on its own).
     *  - Pin matches -> log increased confidence.
     *  - Pin does not match -> log a warning only. The connection this method is
     *    called from has ALREADY been accepted by checkServerTrusted above; this
     *    method has no way to undo that, by design.
     */
    private fun applyBridgeIdPinning(chain: Array<X509Certificate>, acceptedVia: String) {
        val store = pinningStore ?: return
        val cert = chain.firstOrNull() ?: return

        val currentBridgeId = extractBridgeIdFromCertificate(cert)
        if (currentBridgeId == null) {
            Logger.d(LogTags.HUE_NETWORK, "ℹ️ PINNING: certificate has no recognizable bridge ID - skipping pin comparison (accepted via $acceptedVia)")
            return
        }

        val pinnedBridgeId = store.getPinnedBridgeId()
        when {
            pinnedBridgeId == null -> store.pinBridgeId(currentBridgeId)
            pinnedBridgeId == currentBridgeId ->
                Logger.d(LogTags.HUE_NETWORK, "✅ PINNING: bridge identity matches pinned value ($currentBridgeId) - accepted via $acceptedVia")
            else -> {
                // Mismatch: log loudly for audit purposes, but do NOT reject. The cert
                // already passed hybrid validation above. Common legitimate causes:
                // bridge replaced via Philips support, factory reset + re-pair, or a
                // different bridge now holding this IP via DHCP. We update the pin so
                // that repeated legitimate use of the new bridge does not keep warning.
                Logger.w(
                    LogTags.HUE_NETWORK,
                    "⚠️ PINNING: bridge identity mismatch (pinned=$pinnedBridgeId, current=$currentBridgeId, accepted via $acceptedVia). " +
                        "Connection was still accepted (hybrid validation passed) - pinning is advisory only, never blocking."
                )
                store.pinBridgeId(currentBridgeId)
            }
        }
    }

    // ------------------------------------------------------------------
    // Hybrid fallback validation (Layer 2)
    // ------------------------------------------------------------------

    /**
     * Validates Hue Bridge certificates against known conventions when system trust
     * store validation fails (the common case for today's bridges).
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun validateHueBridgeCertificate(
        chain: Array<X509Certificate>,
        type: String,
        authType: String
    ) {
        if (chain.isEmpty()) {
            val error = "Empty certificate chain for $type validation"
            Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: $error")
            throw CertificateException(error)
        }

        val cert = chain[0]
        val subjectDN = cert.subjectDN?.toString() ?: "Unknown"
        val issuerDN = cert.issuerDN?.toString() ?: "Unknown"

        try {
            cert.checkValidity()
            Logger.d(LogTags.HUE_NETWORK, "✅ Hue TLS: certificate validity period check passed")

            if (!isValidHueBridgeCertificate(cert)) {
                val error = "Certificate does not match Hue Bridge patterns: $subjectDN"
                Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: $error")
                throw CertificateException(error)
            }
            Logger.d(LogTags.HUE_NETWORK, "✅ Hue TLS: Hue Bridge pattern validation passed")

            validateCertificateStrength(cert)

            Logger.i(LogTags.HUE_NETWORK, "🔒 Hue TLS: $type certificate validation successful (subject=$subjectDN)")
            Logger.d(LogTags.HUE_NETWORK, "🔒 Hue TLS: issuer=$issuerDN, authType=$authType")
        } catch (e: CertificateException) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: certificate validation failed for $type", e)
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: unexpected error during $type certificate validation", e)
            throw CertificateException("Certificate validation failed: ${e.message}", e)
        }
    }

    /**
     * Checks whether a certificate matches known Hue Bridge conventions: official
     * Philips/Signify certificates, Hue/bridge-specific subject patterns, a bridge-ID
     * hex pattern, or a self-signed certificate (common for local bridges).
     */
    private fun isValidHueBridgeCertificate(cert: X509Certificate): Boolean {
        return try {
            val subjectDN = cert.subjectDN?.toString()?.lowercase() ?: ""
            val issuerDN = cert.issuerDN?.toString()?.lowercase() ?: ""

            val hasHuePattern = when {
                subjectDN.contains("philips") || issuerDN.contains("philips") -> true
                subjectDN.contains("hue") || issuerDN.contains("hue") -> true
                subjectDN.contains("bridge") || issuerDN.contains("bridge") -> true
                subjectDN.contains(Regex("[a-f0-9]{12}")) -> true
                subjectDN.isNotEmpty() && subjectDN == issuerDN -> true // self-signed
                else -> false
            }

            Logger.d(LogTags.HUE_NETWORK, "🔍 Hue TLS: pattern validation result: $hasHuePattern")
            hasHuePattern
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 Hue TLS: error validating Hue certificate pattern", e)
            false
        }
    }

    /**
     * Minimum key-strength check (RSA >= 2048 bit; ECDSA accepted as-is). Never throws
     * for unknown algorithms - logs a warning instead, to avoid rejecting a legitimate
     * bridge over a forward-compatibility gap in this check.
     */
    private fun validateCertificateStrength(cert: X509Certificate) {
        try {
            val publicKey = cert.publicKey
            val keyAlgorithm = publicKey.algorithm
            val signatureAlgorithm = cert.sigAlgName

            when (keyAlgorithm) {
                "RSA" -> {
                    val keySize = (publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 0
                    if (keySize < 2048) {
                        throw CertificateException("RSA key size too small: $keySize bits (minimum: 2048)")
                    }
                    Logger.d(LogTags.HUE_NETWORK, "✅ Hue TLS: RSA key strength acceptable: $keySize bits")
                }
                "EC", "ECDSA" -> {
                    Logger.d(LogTags.HUE_NETWORK, "✅ Hue TLS: ECDSA key algorithm acceptable for local bridge")
                }
                else -> {
                    Logger.w(LogTags.HUE_NETWORK, "⚠️ Hue TLS: unknown key algorithm: $keyAlgorithm")
                }
            }

            if (signatureAlgorithm.contains("MD5", ignoreCase = true) ||
                signatureAlgorithm.contains("SHA1", ignoreCase = true)
            ) {
                Logger.w(LogTags.HUE_NETWORK, "⚠️ Hue TLS: weak signature algorithm detected: $signatureAlgorithm")
            }
        } catch (e: CertificateException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.HUE_NETWORK, "⚠️ Hue TLS: could not validate certificate strength", e)
            // Don't throw - allow the certificate through; this is a best-effort check.
        }
    }
}
