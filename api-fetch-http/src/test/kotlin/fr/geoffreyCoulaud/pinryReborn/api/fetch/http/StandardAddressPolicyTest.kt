package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class StandardAddressPolicyTest {
    private val policy = AddressPolicy.Standard

    @Test fun `Given a public IPv4 address, Then it is allowed`() =
        assertTrue(policy.isAllowed(InetAddress.getByName("93.184.216.34")))

    @Test fun `Given a public IPv6 address, Then it is allowed`() =
        assertTrue(policy.isAllowed(InetAddress.getByName("2001:4860:4860::8888")))

    @Test fun `Given the wildcard any-local address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("0.0.0.0")))

    @Test fun `Given loopback, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("127.0.0.1")))

    @Test fun `Given a private 10 8 address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("10.0.0.1")))

    @Test fun `Given a private 192 168 address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("192.168.1.1")))

    @Test fun `Given link-local metadata 169 254, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("169.254.169.254")))

    @Test fun `Given an IPv4 multicast address, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("224.0.0.1")))

    @Test fun `Given IPv6 loopback, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("::1")))

    @Test fun `Given IPv6 unique-local fc00, Then it is blocked`() =
        assertFalse(policy.isAllowed(InetAddress.getByName("fc00::1")))

    @Test fun `Given AllowAll, Then every address is allowed`() =
        assertTrue(AddressPolicy.AllowAll.isAllowed(InetAddress.getByName("127.0.0.1")))
}
