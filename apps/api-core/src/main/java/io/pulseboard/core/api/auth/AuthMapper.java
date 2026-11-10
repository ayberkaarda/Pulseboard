package io.pulseboard.core.api.auth;

import io.pulseboard.core.api.auth.AuthDtos.LoginRequest;
import io.pulseboard.core.api.auth.AuthDtos.RegisterRequest;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.auth.AuthTokens;
import io.pulseboard.core.application.auth.LoginCommand;
import io.pulseboard.core.application.auth.RegisterCommand;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps authentication requests to commands and token results to responses. */
@Mapper
public interface AuthMapper {

    RegisterCommand toCommand(RegisterRequest request);

    LoginCommand toCommand(LoginRequest request);

    @Mapping(target = "tokenType", constant = "Bearer")
    TokenResponse toResponse(AuthTokens tokens);

    default UUID toUuid(TenantId tenantId) {
        return tenantId == null ? null : tenantId.value();
    }
}
