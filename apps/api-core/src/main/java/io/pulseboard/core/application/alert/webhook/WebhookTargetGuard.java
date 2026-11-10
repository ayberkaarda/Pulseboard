package io.pulseboard.core.application.alert.webhook;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Keeps webhook deliveries from reaching internal systems (server-side request forgery).
 *
 * <p>{@link #parse} accepts only syntactically safe URLs: at most 2048 ASCII characters, scheme https (http when
 * explicitly allowed), no credentials, a DNS host name (every form of IP literal is refused, so decimal, octal,
 * hexadecimal, shortened and IPv6 spellings cannot sneak through), no {@code localhost}-style names, and a port
 * from the allow-list. {@link #resolve} additionally looks the name up once and refuses the target when any answer
 * is a loopback, private, link-local (cloud metadata), carrier-grade NAT, multicast, unspecified or otherwise
 * non-public address, including IPv4-mapped IPv6 forms. The caller connects to the returned addresses only.
 *
 * <p>Both run when a rule is saved and again for every delivery, because DNS answers change.
 */
@Component
public class WebhookTargetGuard {

    public static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_ADDRESSES = 4;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4_LIKE = Pattern.compile("[0-9.]+");

    private final WebhookPolicy policy;
    private final HostResolver resolver;

    public WebhookTargetGuard(WebhookPolicy policy, HostResolver resolver) {
        this.policy = policy;
        this.resolver = resolver;
    }

    /** Whether the server can sign webhook requests; without a signing secret the channel is switched off. */
    public boolean deliveryConfigured() {
        return policy.configured();
    }

    /** Checks the URL without touching the network. */
    public WebhookTarget parse(String url) throws UnsafeWebhookTargetException {
        if (url == null || url.isBlank()) {
            throw reject("URL is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw reject("URL is too long");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                throw reject("URL contains characters that are not allowed; use punycode for international names");
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw reject("URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !(policy.allowHttp() && scheme.equals("http"))) {
            throw reject(policy.allowHttp() ? "scheme must be http or https" : "scheme must be https");
        }
        String authority = uri.getRawAuthority();
        if (uri.getRawUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
            throw reject("URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.indexOf('%') >= 0) {
            throw reject("URL has no valid host name");
        }
        host = normaliseHost(host);
        if (!policy.allowPrivateTargets() && isInternalName(host)) {
            throw reject("host is not allowed");
        }
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        if (!policy.allowedPorts().contains(port)) {
            throw reject("port is not allowed");
        }
        return new WebhookTarget(uri, host, port);
    }

    /**
     * Checks the URL and resolves its host once. Every returned address passed the check; the host name is not
     * looked up again by the caller.
     */
    public ResolvedWebhookTarget resolve(String url) throws UnsafeWebhookTargetException {
        return resolve(parse(url));
    }

    public ResolvedWebhookTarget resolve(WebhookTarget target) throws UnsafeWebhookTargetException {
        List<InetAddress> answers;
        try {
            answers = resolver.resolve(target.host());
        } catch (UnknownHostException | RuntimeException ex) {
            throw new UnsafeWebhookTargetException("host cannot be resolved", true);
        }
        if (answers == null || answers.isEmpty()) {
            throw new UnsafeWebhookTargetException("host cannot be resolved", true);
        }
        List<InetAddress> pinned = new ArrayList<>();
        for (InetAddress answer : answers) {
            // One blocked answer rejects the target: an attacker could otherwise mix a public and an internal one.
            if (AddressClassifier.isBlocked(answer, policy.allowPrivateTargets())) {
                throw reject("host resolves to a blocked address");
            }
            if (pinned.size() < MAX_ADDRESSES) {
                try {
                    pinned.add(InetAddress.getByAddress(target.host(), answer.getAddress()));
                } catch (UnknownHostException ex) {
                    throw new UnsafeWebhookTargetException("host cannot be resolved", true);
                }
            }
        }
        return new ResolvedWebhookTarget(target, pinned);
    }

    private static String normaliseHost(String rawHost) throws UnsafeWebhookTargetException {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            throw reject("host must be a DNS name, not an IP address");
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253) {
            throw reject("URL has no valid host name");
        }
        if (IPV4_LIKE.matcher(host).matches()) {
            throw reject("host must be a DNS name, not an IP address");
        }
        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException ex) {
            throw reject("URL has no valid host name");
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw reject("URL has no valid host name");
            }
        }
        // A last label that is not alphabetic is how decimal, octal and hexadecimal IP spellings look.
        if (!Character.isLetter(labels[labels.length - 1].charAt(0))) {
            throw reject("host must be a DNS name, not an IP address");
        }
        return host;
    }

    private static boolean isInternalName(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".localdomain") || !host.contains(".");
    }

    private static UnsafeWebhookTargetException reject(String reason) {
        return new UnsafeWebhookTargetException(reason, false);
    }
}
