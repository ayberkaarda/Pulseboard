package io.pulseboard.core.application.alert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The server-side request forgery matrix: what a webhook target may be, before and after DNS resolution. */
class WebhookTargetGuardTest {

    private static final String SECRET = "s".repeat(32);

    private static WebhookPolicy policy(boolean allowHttp, boolean allowPrivate, Integer... ports) {
        return new WebhookPolicy(SECRET, allowHttp, allowPrivate, Set.of(ports), Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }

    private static WebhookTargetGuard strict(HostResolver resolver) {
        return new WebhookTargetGuard(policy(false, false, 443), resolver);
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(literal, ex);
        }
    }

    private static HostResolver answering(String... literals) {
        return host -> java.util.Arrays.stream(literals).map(WebhookTargetGuardTest::ip).toList();
    }

    private static void assertRejected(WebhookTargetGuard guard, String url) {
        assertThatThrownBy(() -> guard.parse(url)).as(url).isInstanceOf(UnsafeWebhookTargetException.class);
    }

    @Test
    void anOrdinaryHttpsUrlIsAccepted() throws Exception {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));

        WebhookTarget target = guard.parse("https://hooks.example.com/path/to?x=1#frag");

        assertThat(target.host()).isEqualTo("hooks.example.com");
        assertThat(target.port()).isEqualTo(443);
        assertThat(guard.parse("HTTPS://Hooks.Example.COM:443/x").host()).isEqualTo("hooks.example.com");
        assertThat(guard.parse("https://xn--e1afmkfd.xn--p1ai/x").host()).isEqualTo("xn--e1afmkfd.xn--p1ai");
        assertThat(guard.parse("https://hooks.example.com/" + "a".repeat(2048 - "https://hooks.example.com/".length()))
                .uri().toString()).hasSize(2048);
    }

    @Test
    void schemesOtherThanHttpsAreRefusedUnlessHttpIsAllowed() throws Exception {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of("http://hooks.example.com/x", "ftp://hooks.example.com/x", "file:///etc/passwd",
                "gopher://hooks.example.com/", "javascript:alert(1)", "//hooks.example.com/x", "hooks.example.com/x",
                "https:hooks.example.com", "https:///x", "", "   ")) {
            assertRejected(guard, url);
        }
        assertRejected(guard, null);
        WebhookTargetGuard lenient = new WebhookTargetGuard(policy(true, false, 80, 443), answering("93.184.216.34"));
        assertThat(lenient.parse("http://hooks.example.com/x").port()).isEqualTo(80);
        assertRejected(lenient, "ftp://hooks.example.com/x");
    }

    @Test
    void credentialsInTheUrlAreRefused() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of("https://user:pw@hooks.example.com/x", "https://user@hooks.example.com/x",
                "https://hooks.example.com@evil.example.net/x", "https://hooks.example.com:443@evil.example.net/x",
                "https://:@hooks.example.com/", "https://hooks.example.com%40evil.example.net/x",
                "https://hooks.example.com\\@evil.example.net/x")) {
            assertRejected(guard, url);
        }
    }

    @Test
    void everyIpLiteralSpellingIsRefused() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of(
                "https://127.0.0.1/", "https://127.1/", "https://127.0.1/", "https://0x7f.0.0.1/",
                "https://0x7f000001/", "https://0177.0.0.1/", "https://017700000001/", "https://2130706433/",
                "https://0/", "https://0.0.0.0/", "https://169.254.169.254/latest/meta-data/",
                "https://2852039166/", "https://0xa9fea9fe/", "https://10.0.0.1/", "https://172.16.0.1/",
                "https://192.168.1.1/", "https://100.64.0.1/", "https://8.8.8.8/", "https://1.1.1.1:443/",
                "https://[::1]/", "https://[::]/", "https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/",
                "https://[0:0:0:0:0:ffff:7f00:1]/", "https://[fe80::1]/", "https://[fe80::1%25eth0]/",
                "https://[fc00::1]/", "https://[fd00::1]/", "https://[2001:db8::1]/", "https://[64:ff9b::7f00:1]/",
                "https://[2606:4700:4700::1111]/")) {
            assertRejected(guard, url);
        }
    }

    @Test
    void localhostStyleAndSingleLabelNamesAreRefused() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of("https://localhost/", "https://LOCALHOST/", "https://localhost./",
                "https://localhost../", "https://foo.localhost/", "https://foo.bar.localhost./", "https://redis/",
                "https://api-core/", "https://postgres:443/", "https://printer.local/", "https://db.internal/",
                "https://localhost.localdomain/", "https://a..example.com/", "https://-bad.example.com/",
                "https://exa_mple.example.com/", "https://exämple.example.com/", "https://exa%6Dple.example.com/",
                "https://exa mple.example.com/", "https://example.com/\u0000", "https://example.com/\n")) {
            assertRejected(guard, url);
        }
    }

    @Test
    void aTrailingDotDoesNotChangeWhatIsChecked() throws Exception {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));

        assertThat(guard.parse("https://hooks.example.com./x").host()).isEqualTo("hooks.example.com");
        assertRejected(guard, "https://localhost./x");
        assertRejected(guard, "https://127.0.0.1./x");
    }

    @Test
    void onlyAllowedPortsAreAccepted() throws Exception {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of("https://hooks.example.com:80/", "https://hooks.example.com:8443/",
                "https://hooks.example.com:22/", "https://hooks.example.com:6379/", "https://hooks.example.com:0/",
                "https://hooks.example.com:65535/", "https://hooks.example.com:99999/",
                "https://hooks.example.com:-1/", "https://hooks.example.com:443443/")) {
            assertRejected(guard, url);
        }
        assertThat(guard.parse("https://hooks.example.com:443/").port()).isEqualTo(443);
        WebhookTargetGuard wide = new WebhookTargetGuard(policy(false, false, 443, 8443), answering("93.184.216.34"));
        assertThat(wide.parse("https://hooks.example.com:8443/").port()).isEqualTo(8443);
    }

    @Test
    void anOverlongUrlIsRefused() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));

        assertRejected(guard, "https://hooks.example.com/" + "a".repeat(2048));
        assertRejected(guard, "https://" + "a".repeat(64) + ".example.com/");
    }

    @Test
    void rejectionReasonsNeverEchoTheUrl() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34"));
        for (String url : List.of("https://user:topsecret@hooks.example.com/x?token=abc",
                "https://169.254.169.254/latest?secret=1", "https://hooks.example.com:8443/path-with-secret")) {
            assertThatThrownBy(() -> guard.parse(url)).isInstanceOf(UnsafeWebhookTargetException.class)
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("topsecret").doesNotContain("abc")
                            .doesNotContain("169.254").doesNotContain("secret").doesNotContain("8443"));
        }
    }

    @Test
    void everyBlockedAddressClassIsRefusedAfterResolution() {
        List<String> blocked = List.of(
                // IPv4
                "127.0.0.1", "127.255.255.254", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.255",
                "192.168.0.1", "192.168.255.255", "169.254.169.254", "169.254.0.1", "169.254.255.255", "100.64.0.1",
                "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "240.0.0.1",
                "255.255.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.255", "198.51.100.1",
                "203.0.113.1", "192.88.99.1",
                // IPv6
                "::1", "::", "fe80::1", "febf::1", "fc00::1", "fd12:3456:789a::1", "fec0::1", "ff02::1",
                "ff0e::1", "100::1", "2001::1", "2001:db8::1",
                // IPv4 carried in IPv6
                "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1", "::ffff:192.168.1.1",
                "::ffff:100.64.0.1", "::127.0.0.1", "::10.0.0.1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe",
                "64:ff9b::a00:1", "64:ff9b:1::1", "2002:7f00:1::1", "2002:a9fe:a9fe::1", "2002:c0a8:101::1");
        for (String literal : blocked) {
            WebhookTargetGuard guard = strict(answering(literal));
            assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x"))
                    .as(literal)
                    .isInstanceOf(UnsafeWebhookTargetException.class)
                    .satisfies(ex -> assertThat(((UnsafeWebhookTargetException) ex).isTransientFailure())
                            .as(literal).isFalse());
        }
    }

    @Test
    void ipv4MappedAddressesAreJudgedByTheAddressTheyCarry() throws Exception {
        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xff;
        mappedLoopback[11] = (byte) 0xff;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1;
        // Built as a real IPv6 address object (no conversion to IPv4 by the JDK).
        InetAddress mapped = java.net.Inet6Address.getByAddress(null, mappedLoopback, -1);
        assertThat(mapped).isInstanceOf(java.net.Inet6Address.class);

        assertThatThrownBy(() -> strict(host -> List.of(mapped)).resolve("https://hooks.example.com/"))
                .isInstanceOf(UnsafeWebhookTargetException.class);
        mappedLoopback[12] = 93;
        mappedLoopback[13] = (byte) 184;
        mappedLoopback[14] = (byte) 216;
        mappedLoopback[15] = 34;
        InetAddress mappedPublic = java.net.Inet6Address.getByAddress(null, mappedLoopback, -1);
        assertThat(strict(host -> List.of(mappedPublic)).resolve("https://hooks.example.com/").addresses())
                .hasSize(1);
    }

    @Test
    void publicAddressesPassAndTheBoundariesOfTheBlockedRangesAreExact() throws Exception {
        for (String literal : List.of("93.184.216.34", "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1",
                "100.63.255.255", "100.128.0.1", "169.253.255.255", "169.255.0.1", "11.0.0.1", "126.255.255.255",
                "128.0.0.1", "223.255.255.255", "192.167.255.255", "192.169.0.1", "198.17.255.255", "198.20.0.1",
                "2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2001:4860:4860::8888")) {
            ResolvedWebhookTarget resolved = strict(answering(literal)).resolve("https://hooks.example.com/x");
            assertThat(resolved.addresses()).as(literal).hasSize(1);
            assertThat(resolved.addresses().getFirst().getHostAddress()).isEqualTo(ip(literal).getHostAddress());
        }
    }

    @Test
    void oneBlockedAnswerAmongPublicOnesRejectsTheTarget() {
        WebhookTargetGuard guard = strict(answering("93.184.216.34", "10.0.0.7"));

        assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x"))
                .isInstanceOf(UnsafeWebhookTargetException.class);
        assertThatThrownBy(() -> strict(answering("10.0.0.7", "93.184.216.34")).resolve("https://hooks.example.com/"))
                .isInstanceOf(UnsafeWebhookTargetException.class);
    }

    @Test
    void privateTargetsCanBeAllowedForTestsButNeverTheMetadataAddressOrOtherNonPublicRanges() throws Exception {
        WebhookPolicy relaxed = policy(true, true, 80, 443);
        for (String literal : List.of("127.0.0.1", "10.1.2.3", "172.20.0.2", "192.168.1.1", "::1", "fd00::1")) {
            assertThat(new WebhookTargetGuard(relaxed, answering(literal)).resolve("http://hooks.example.com/")
                    .addresses()).as(literal).hasSize(1);
        }
        assertThat(new WebhookTargetGuard(relaxed, answering("127.0.0.1")).parse("http://localhost/x").host())
                .isEqualTo("localhost");
        for (String literal : List.of("169.254.169.254", "169.254.1.1", "fe80::1", "100.64.0.1", "0.0.0.0", "::",
                "224.0.0.1", "ff02::1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1")) {
            assertThatThrownBy(() -> new WebhookTargetGuard(relaxed, answering(literal))
                    .resolve("http://hooks.example.com/")).as(literal)
                    .isInstanceOf(UnsafeWebhookTargetException.class);
        }
        // IP literals stay refused even when private targets are allowed.
        assertRejected(new WebhookTargetGuard(relaxed, answering("127.0.0.1")), "http://127.0.0.1/");
    }

    @Test
    void isatapAddressesAreJudgedByTheirEmbeddedIpv4InBothInterfaceIdentifierForms() throws Exception {
        // RFC 5214 6.1: interface identifier 0000:5efe or 0200:5efe followed by the IPv4 address.
        for (String literal : List.of("2001:4860:4860:0:0:5efe:a00:1", "2001:4860:4860:0:200:5efe:a00:1",
                "2001:4860:4860:0:200:5efe:a9fe:a9fe", "2001:4860:4860:0:0:5efe:c0a8:101")) {
            WebhookTargetGuard guard = strict(answering(literal));
            assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x")).as(literal)
                    .isInstanceOf(UnsafeWebhookTargetException.class);
        }
        assertThat(strict(answering("2001:4860:4860:0:200:5efe:808:808")).resolve("https://hooks.example.com/x")
                .addresses()).as("public embedded IPv4").hasSize(1);
    }

    @Test
    void theLocalUseNat64BlockIsRefusedWhileTheWellKnownPrefixIsJudgedByItsIpv4() throws Exception {
        for (String literal : List.of("64:ff9b:1::a00:1", "64:ff9b:1:0:0:a00:0:1", "64:ff9b:1:a00:1::",
                "64:ff9b:1:0:a:0:100:0", "64:ff9b:1::a9fe:a9fe", "64:ff9b:1:808::a00:1", "64:ff9b:1:ffff::1",
                "64:ff9b:1:0:0:808:808:0", "64:ff9b:2::1")) {
            WebhookTargetGuard guard = strict(answering(literal));
            assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x")).as(literal)
                    .isInstanceOf(UnsafeWebhookTargetException.class);
        }
        assertThat(strict(answering("64:ff9b::808:808")).resolve("https://hooks.example.com/x").addresses())
                .as("well-known prefix with a public IPv4").hasSize(1);
    }

    @Test
    void aNameThatDoesNotResolveIsATransientFailure() {
        WebhookTargetGuard unknown = strict(host -> {
            throw new UnknownHostException(host);
        });
        WebhookTargetGuard empty = strict(host -> List.of());

        for (WebhookTargetGuard guard : List.of(unknown, empty)) {
            assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x"))
                    .isInstanceOf(UnsafeWebhookTargetException.class)
                    .hasMessage("host cannot be resolved")
                    .satisfies(ex -> assertThat(((UnsafeWebhookTargetException) ex).isTransientFailure()).isTrue());
        }
    }

    @Test
    void theNameIsLookedUpOnceAndTheCheckedAnswerIsTheOneThatIsPinned() throws Exception {
        // DNS rebinding: a public address on the first lookup, an internal one on every later lookup.
        AtomicInteger lookups = new AtomicInteger();
        List<String> asked = new ArrayList<>();
        HostResolver rebinding = host -> {
            asked.add(host);
            return List.of(ip(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "169.254.169.254"));
        };
        WebhookTargetGuard guard = strict(rebinding);

        ResolvedWebhookTarget resolved = guard.resolve("https://hooks.example.com/x");

        assertThat(lookups).hasValue(1);
        assertThat(asked).containsExactly("hooks.example.com");
        assertThat(resolved.addresses()).hasSize(1);
        assertThat(resolved.addresses().getFirst().getHostAddress()).isEqualTo("93.184.216.34");
        assertThat(resolved.addresses().getFirst().getHostName()).isEqualTo("hooks.example.com");
        // The check at delivery time looks again and sees the internal answer: the target is refused then.
        assertThatThrownBy(() -> guard.resolve("https://hooks.example.com/x"))
                .isInstanceOf(UnsafeWebhookTargetException.class);
        assertThat(lookups).hasValue(2);
    }

    @Test
    void deliveryConfigurationDependsOnTheSigningSecret() {
        WebhookPolicy withoutSecret = new WebhookPolicy("", false, false, Set.of(443), Duration.ofSeconds(1),
                Duration.ofSeconds(1));

        assertThat(new WebhookTargetGuard(withoutSecret, answering("93.184.216.34")).deliveryConfigured()).isFalse();
        assertThat(strict(answering("93.184.216.34")).deliveryConfigured()).isTrue();
        assertThat(withoutSecret.toString()).doesNotContain("secret");
        assertThat(policy(false, false, 443).toString()).doesNotContain(SECRET);
    }
}
