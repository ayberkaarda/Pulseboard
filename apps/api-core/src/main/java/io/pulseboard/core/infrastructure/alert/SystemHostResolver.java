package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.webhook.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Resolves host names with the platform resolver. */
@Component
public class SystemHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
