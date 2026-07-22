package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Unit tests for [NetworkStateMonitor.isReachableSubnet] — the real CIDR-based "am I on the
 * same subnet as this local IP" check that replaced the old transport-type-only
 * ("bin ich in irgendeinem WLAN") heuristic.
 *
 * The IPs used in the "wrong subnet" cases below are taken verbatim from real
 * CF-Alarm-for-TimeOffice debug logs (2026-07-20/22) where the Hue Bridge
 * (192.168.178.24) timed out because the phone was on some other network.
 */
class NetworkStateMonitorTest {

    private fun ipv4(ip: String): Inet4Address = InetAddress.getByName(ip) as Inet4Address

    private fun linkAddress(ip: String, prefixLength: Int): LinkAddress {
        val la = mock<LinkAddress>()
        whenever(la.address).thenReturn(ipv4(ip))
        whenever(la.prefixLength).thenReturn(prefixLength)
        return la
    }

    private fun monitorWithLinkAddresses(vararg addresses: LinkAddress): NetworkStateMonitor {
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val linkProperties = mock<LinkProperties>()
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getLinkProperties(network)).thenReturn(linkProperties)
        whenever(linkProperties.linkAddresses).thenReturn(addresses.toList())
        return NetworkStateMonitor(connectivityManager)
    }

    private fun monitorWithNoActiveNetwork(): NetworkStateMonitor {
        val connectivityManager = mock<ConnectivityManager>()
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        return NetworkStateMonitor(connectivityManager)
    }

    private fun monitorWithNoLinkProperties(): NetworkStateMonitor {
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getLinkProperties(network)).thenReturn(null)
        return NetworkStateMonitor(connectivityManager)
    }

    @Test
    fun `no active network - not reachable`() {
        assertFalse(monitorWithNoActiveNetwork().isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `no link properties - not reachable`() {
        assertFalse(monitorWithNoLinkProperties().isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `same home subnet - reachable`() {
        val monitor = monitorWithLinkAddresses(linkAddress("192.168.178.50", 24))
        assertTrue(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `wrong private subnet - not reachable (log case 2026-07-22 08-55, 192-168-44-84)`() {
        val monitor = monitorWithLinkAddresses(linkAddress("192.168.44.84", 24))
        assertFalse(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `wrong private subnet - not reachable (log case 2026-07-20 12-45, 10-0-9-10)`() {
        val monitor = monitorWithLinkAddresses(linkAddress("10.0.9.10", 24))
        assertFalse(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `CGNAT mobile-data address - not reachable (log case 2026-07-22 08-43, 100-72-201-243)`() {
        val monitor = monitorWithLinkAddresses(linkAddress("100.72.201.243", 16))
        assertFalse(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `IPv6-only link address - not reachable`() {
        val la = mock<LinkAddress>()
        val v6 = mock<Inet6Address>()
        whenever(la.address).thenReturn(v6)
        val monitor = monitorWithLinkAddresses(la)
        assertFalse(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `multiple link addresses - matches when any one is on the same subnet`() {
        val monitor = monitorWithLinkAddresses(
            linkAddress("10.0.9.10", 24),
            linkAddress("192.168.178.50", 24),
        )
        assertTrue(monitor.isReachableSubnet("192.168.178.24"))
    }

    @Test
    fun `malformed target ip does not throw and is not reachable`() {
        val monitor = monitorWithLinkAddresses(linkAddress("192.168.178.50", 24))
        assertFalse(monitor.isReachableSubnet(""))
        assertFalse(monitor.isReachableSubnet("not.an.ip"))
        assertFalse(monitor.isReachableSubnet("999.1.1.1"))
        assertFalse(monitor.isReachableSubnet("192.168.1"))
    }

    // --- isSameSubnet (pure CIDR math, exercised directly across prefix boundaries) -------

    private fun bytes(ip: String) = ipv4(ip).address

    @Test
    fun `isSameSubnet prefix 0 always matches`() {
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("1.2.3.4"), bytes("250.250.250.250"), 0))
    }

    @Test
    fun `isSameSubnet prefix 8 byte-aligned`() {
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("10.1.2.3"), bytes("10.99.99.99"), 8))
        assertFalse(NetworkStateMonitor.isSameSubnet(bytes("10.1.2.3"), bytes("11.1.2.3"), 8))
    }

    @Test
    fun `isSameSubnet prefix 24 byte-aligned`() {
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("192.168.178.50"), bytes("192.168.178.24"), 24))
        assertFalse(NetworkStateMonitor.isSameSubnet(bytes("192.168.44.84"), bytes("192.168.178.24"), 24))
    }

    @Test
    fun `isSameSubnet prefix 25 non byte-aligned`() {
        // 192.168.1.100 and 192.168.1.120 are both in 192.168.1.0/25 (0-127)
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("192.168.1.100"), bytes("192.168.1.120"), 25))
        // 192.168.1.100 (/25 -> 0-127) vs 192.168.1.200 (128-255) - different half
        assertFalse(NetworkStateMonitor.isSameSubnet(bytes("192.168.1.100"), bytes("192.168.1.200"), 25))
    }

    @Test
    fun `isSameSubnet prefix 30 non byte-aligned`() {
        // 192.168.1.1 and 192.168.1.2 are both in 192.168.1.0/30 (0-3)
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("192.168.1.1"), bytes("192.168.1.2"), 30))
        // 192.168.1.1 (/30 -> 0-3) vs 192.168.1.5 (4-7) - different block
        assertFalse(NetworkStateMonitor.isSameSubnet(bytes("192.168.1.1"), bytes("192.168.1.5"), 30))
    }

    @Test
    fun `isSameSubnet prefix 32 requires exact match`() {
        assertTrue(NetworkStateMonitor.isSameSubnet(bytes("192.168.178.24"), bytes("192.168.178.24"), 32))
        assertFalse(NetworkStateMonitor.isSameSubnet(bytes("192.168.178.24"), bytes("192.168.178.25"), 32))
    }
}
