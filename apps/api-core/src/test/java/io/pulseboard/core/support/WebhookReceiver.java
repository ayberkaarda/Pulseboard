package io.pulseboard.core.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * One WireMock server for the whole test JVM, started before any Spring context so its port can be put on the
 * webhook port allow-list. Tests use a unique path each and never reset the server.
 */
public final class WebhookReceiver {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(SERVER::stop));
    }

    private WebhookReceiver() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    public static int port() {
        return SERVER.port();
    }

    /** Base URL as a rule would use it: the host name {@code localhost} resolves to the loopback address. */
    public static String url(String path) {
        return "http://localhost:" + SERVER.port() + path;
    }
}
