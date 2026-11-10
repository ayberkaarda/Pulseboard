package io.pulseboard.core.application.alert.webhook;

import java.net.InetAddress;

/**
 * Decides whether an IP address may be the destination of a webhook. Only globally routable unicast addresses pass.
 *
 * <p>Blocked always: unspecified, link-local (169.254.0.0/16, which holds the cloud metadata address, and
 * fe80::/10), carrier-grade NAT (100.64.0.0/10), multicast, reserved and documentation ranges, benchmarking and
 * 6to4 relay ranges, and the IPv6 transition forms that carry an IPv4 address (NAT64, 6to4, Teredo, IPv4-compatible)
 * when the embedded address is blocked or the form cannot be judged. IPv4-mapped IPv6 addresses
 * ({@code ::ffff:a.b.c.d}) are judged by the IPv4 address they carry.
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
        if (allZero(b, 0, 12)) {
            return true; // IPv4-compatible (deprecated)
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
            // 64:ff9b::/96 NAT64 carries the IPv4 address; the local-use prefix 64:ff9b:1::/48 is not judged.
            return allZero(b, 4, 12) ? blockedV4(b, 12, allowPrivate) : true;
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return blockedV4(b, 2, allowPrivate); // 6to4: the IPv4 address follows the prefix
        }
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) {
            return true; // Teredo
        }
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
            return true; // documentation
        }
        if (b0 == 0x01 && allZero(b, 1, 8)) {
            return true; // 100::/64 discard-only
        }
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
            return true; // fe80::/10 link-local
        }
        if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) {
            return !allowPrivate; // fec0::/10 site-local
        }
        if ((b0 & 0xfe) == 0xfc) {
            return !allowPrivate; // fc00::/7 unique local
        }
        return b0 == 0xff; // multicast
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
