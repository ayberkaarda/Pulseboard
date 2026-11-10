package io.pulseboard.core.support;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.http.server.LocalTestWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Keeps the HTTP test client on the JDK's HTTP client. With Apache HttpClient on the class path (it is used for webhook
 * delivery) Spring would pick it for the test client, and its default retry strategy would silently repeat requests
 * that were answered with 429 or 503 and wait for their Retry-After, which hides exactly what the rate limit tests
 * look at.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JdkRestTestClientConfiguration {

    @Bean
    RestTestClient restTestClient(ApplicationContext context,
            ObjectProvider<RestTestClientBuilderCustomizer> customizers) {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .uriBuilderFactory(LocalTestWebServer.obtain(context).uriBuilderFactory());
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }
}
