package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import java.net.Inet6Address
import java.net.InetAddress

/** Decides whether a resolved address may be connected to (the SSRF address filter). */
interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean

    /** No filtering: only for trusted networks / tests (config `allow_private_addresses=true`). */
    object AllowAll : AddressPolicy {
        override fun isAllowed(address: InetAddress): Boolean = true
    }

    /** Standard guard: reject loopback, private, link-local (incl. metadata), reserved, and IPv6 ULA. */
    object Standard : AddressPolicy {
        override fun isAllowed(address: InetAddress): Boolean = !isBlocked(address)

        private fun isBlocked(address: InetAddress): Boolean =
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                isUniqueLocalIpv6(address)

        // fc00::/7 (IPv6 unique-local) is not covered by the standard InetAddress predicates.
        private fun isUniqueLocalIpv6(address: InetAddress): Boolean =
            address is Inet6Address && (address.address[0].toInt() and ULA_PREFIX_MASK) == ULA_PREFIX

        // fc00::/7: the top 7 bits of the first byte identify the unique-local block.
        private const val ULA_PREFIX_MASK = 0xfe
        private const val ULA_PREFIX = 0xfc
    }
}
