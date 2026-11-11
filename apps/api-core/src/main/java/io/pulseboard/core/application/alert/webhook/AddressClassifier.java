package io.pulseboard.core.application.alert.webhook;

import java.net.InetAddress;

/**
 * Decides whether an IP address may be the destination of a webhook. Only globally routable unicast addresses pass.
 *
 * <p>IPv4: blocked always are unspecified, link-local (169.254.0.0/16, which holds the cloud metadata address),
 * carrier-grade NAT (100.64.0.0/10), multicast, reserved and documentation ranges, benchmarking and 6to4 relay
 * ranges. IPv4-mapped IPv6 addresses ({@code ::ffff:a.b.c.d}) are judged by the IPv4 address they carry.
 *
 * <p>IPv6 is an allow-list: only 2000::/3 passes, minus the special-purpose blocks of the IANA registry that are not
 * globally reachable (2001::/23, 2001:db8::/32, 3fff::/20). 6to4 (2002::/16), NAT64 (64:ff9b::/96 and
 * 64:ff9b:1::/48) and ISATAP interface identifiers are judged by the IPv4 address they embed.
 *
 * <p>Blocked unless private targets are allowed (tests and local development only): loopback, RFC 1918, unique
 * local addresses (fc00::/7) and the deprecated site-local range (fec0::/10).
 */
public final class AddressClassifier {

    private AddressClassifier() {
    }

    public static boolean isBlocked(InetAddress address, boolean allowPrivate) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? blockedV4(bytes, 0, allowPrivate) : blockedV6(bytes, allowPrivate);
    }

    private static boolean blockedV4(byte[] b, int offset, boolean allowPrivate) {
        int first = b[offset] & 0xff;
        int second = b[offset + 1] & 0xff;
        int third = b[offset + 2] & 0xff;
        if (first == 0) {
            return true; // 0.0.0.0/8: "this network", including the unspecified address
        }
        if (first == 127 || first == 10 || (first == 172 && (second & 0xf0) == 16)
                || (first == 192 && second == 168)) {
            return !allowPrivate; // loopback and RFC 1918
        }
        if (first == 169 && second == 254) {
            return true; // link-local, cloud metadata services
        }
        if (first == 100 && (second & 0xc0) == 64) {
            return true; // 100.64.0.0/10 carrier-grade NAT
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return true; // 192.0.0.0/24 protocol assignments, 192.0.2.0/24 documentation
        }
        if (first == 192 && second == 88 && third == 99) {
            return true; // 6to4 relay anycast
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true; // benchmarking
        }
        if (first == 198 && second == 51 && third == 100) {
            return true; // documentation
        }
        if (first == 203 && second == 0 && third == 113) {
            return true; // documentation
        }
        return first >= 224; // multicast, reserved, broadcast
    }

    /**
     * IPv6 is an allow-list: only global unicast space (2000::/3) passes, minus the special-purpose blocks inside it
     * that are not globally reachable. Everything outside 2000::/3 is refused, except the forms that carry an IPv4
     * address, which are judged by that address.
     */
    private static boolean blockedV6(byte[] b, boolean allowPrivate) {
        if (allZero(b, 0, 16)) {
            return true; // unspecified
        }
        if (allZero(b, 0, 15) && b[15] == 1) {
            return !allowPrivate; // loopback
        }
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return blockedV4(b, 12, allowPrivate); // IPv4-mapped
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        int b3 = b[3] & 0xff;
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b) {
            if (allZero(b, 4, 12)) {
                return blockedV4(b, 12, allowPrivate); // 64:ff9b::/96 NAT64, RFC 6052: the IPv4 address is last
            }
            // 64:ff9b:1::/48 local-use NAT64 (RFC 8215): prefixes of /48 to /96 embed the IPv4 address at
            // different offsets (RFC 6052 2.2) and the prefix length is unknown here. No public webhook target
            // lives there, so everything else under 64:ff9b::/32 is refused instead of guessing the address.
            return true;
        }
        if (isatapBlocked(b, allowPrivate)) {
            return true; // ISATAP interface identifier ::0:5efe:a.b.c.d or ::200:5efe:a.b.c.d
        }
        if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) {
            return !allowPrivate; // fec0::/10 site-local
        }
        if ((b0 & 0xfe) == 0xfc) {
            return !allowPrivate; // fc00::/7 unique local
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return blockedV4(b, 2, allowPrivate); // 2002::/16 6to4: the IPv4 address follows the prefix
        }
        if ((b0 & 0xe0) != 0x20) {
            return true; // not in 2000::/3: loopback, link-local, unique local, multicast, 5f00::/16 and the rest
        }
        if (b0 == 0x20 && b1 == 0x01 && b2 < 0x02) {
            return true; // 2001::/23 IETF protocol assignments: Teredo, benchmarking, ORCHID, AMT, ...
        }
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) {
            return true; // 2001:db8::/32 documentation
        }
        return b0 == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0; // 3fff::/20 documentation
    }

    /** An ISATAP address embeds an IPv4 address in its interface identifier; it is judged by that address. */
    private static boolean isatapBlocked(byte[] b, boolean allowPrivate) {
        boolean marker = (b[8] == 0 || b[8] == 0x02) && b[9] == 0 && (b[10] & 0xff) == 0x5e
                && (b[11] & 0xff) == 0xfe;
        return marker && blockedV4(b, 12, allowPrivate);
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
