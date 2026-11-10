package io.pulseboard.core.application.alert.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Looks a host name up in DNS. A port so tests can answer differently on every call (rebinding). */
public interface HostResolver {

    /**
     * @return the addresses of the host, never empty
     * @throws UnknownHostException when the name does not resolve
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
