package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * Haelt fest, dass die mDNS-Discovery die IPv4-Adresse einer Bridge waehlt - nicht einfach die
 * erste aufgeloeste Adresse.
 *
 * DER FEHLER, DEN DAS VERHINDERT: Der komplette Hue-Pfad dieser App ist IPv4-only
 * (HueApiClient.isPrivateNetworkAddress prueft Praefixe, die URL wird ohne eckige Klammern
 * gebaut, HueBridgeConnectionManager.isBridgeReachableNow prueft ebenfalls nur IPv4). Liefert
 * der Resolver in einem IPv6-Heimnetz die IPv6-Adresse zuerst, meldete die Discovery Erfolg -
 * und danach scheiterte JEDER Zugriff an der Adress-Klemme. Ergebnis fuer den Nutzer: "keine
 * Bridge gefunden", obwohl sie gerade gefunden wurde, samt einer irrefuehrenden
 * Sicherheits-Warnung im Log.
 */
class HueMdnsAddressSelectionTest {

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `IPv6 an erster Stelle wird uebersprungen`() {
        val addresses = listOf(ip("2003:db8::1"), ip("192.168.178.42"))

        assertEquals(
            "192.168.178.42",
            HueMdnsDiscoveryService.firstIpv4HostAddress(addresses)
        )
    }

    @Test
    fun `einzelne IPv4-Adresse wird genommen`() {
        assertEquals(
            "192.168.178.42",
            HueMdnsDiscoveryService.firstIpv4HostAddress(listOf(ip("192.168.178.42")))
        )
    }

    @Test
    fun `nur IPv6 liefert null statt einer unbenutzbaren Adresse`() {
        assertNull(
            "Eine IPv6-Adresse durchzureichen erzeugte spaeter eine falsche Sicherheits-Meldung",
            HueMdnsDiscoveryService.firstIpv4HostAddress(listOf(ip("2003:db8::1"), ip("fe80::1")))
        )
    }

    @Test
    fun `keine Adresse liefert null`() {
        assertNull(HueMdnsDiscoveryService.firstIpv4HostAddress(emptyList()))
    }
}
