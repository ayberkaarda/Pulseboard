package io.pulseboard.core.api.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document-level OpenAPI metadata. The published contract ({@code contracts/api-core.openapi.yaml}) is exported
 * from this application, so everything here must be deterministic: a fixed relative server instead of the
 * request URL, and a contract version that is bumped whenever the contract changes.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    /** Security scheme of user requests: a JWT access token in the Authorization header. */
    public static final String BEARER_AUTH = "bearerAuth";
    /** Security scheme of machine requests: an API key in the X-Api-Key header. */
    public static final String API_KEY_AUTH = "apiKey";
    /** Media type of every error response (RFC 9457). */
    public static final String PROBLEM_JSON = "application/problem+json";

    /** Security scheme of service to service calls: the HMAC-SHA256 of the request body. */
    public static final String INTERNAL_HMAC_AUTH = "internalHmac";

    /** Security scheme of the live stream for clients that cannot set headers: a one-time ticket in the query. */
    public static final String STREAM_TICKET_AUTH = "streamTicket";

    static final String CONTRACT_VERSION = "0.4.0";

    @Bean
    OpenAPI pulseboardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pulseboard api-core")
                        .version(CONTRACT_VERSION)
                        .description("Identity, tenancy, metric ingestion and queries, anomalies and their live "
                                + "stream. Errors are RFC 9457 problem details (application/problem+json)."))
                .servers(List.of(new Server().url("/").description("Same origin")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from /api/v1/auth/login, /register or /refresh."))
                        .addSecuritySchemes(API_KEY_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("Tenant API key from /api/v1/api-keys."))
                        .addSecuritySchemes(STREAM_TICKET_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.QUERY)
                                .name("ticket")
                                .description("GET /api/v1/stream/anomalies only. One-time ticket from "
                                        + "POST /api/v1/stream/ticket, an alternative to the bearer token; "
                                        + "sending both is refused."))
                        .addSecuritySchemes(INTERNAL_HMAC_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Signature")
                                .description("Internal endpoints only. Lower-case hex HMAC-SHA256 of the raw "
                                        + "request body, keyed with INTERNAL_SHARED_SECRET. Missing, malformed "
                                        + "or wrong signatures are answered with 401.")));
    }
}
