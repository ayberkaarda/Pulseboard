package io.pulseboard.core.api.apikey;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.application.apikey.ApiKeyService.IssuedApiKey;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps issued API keys to their one-time response. */
@Mapper
public interface ApiKeyMapper {

    @Mapping(target = "id", source = "apiKey.id")
    @Mapping(target = "name", source = "apiKey.name")
    @Mapping(target = "prefix", source = "apiKey.prefix")
    @Mapping(target = "createdAt", source = "apiKey.createdAt")
    @Mapping(target = "key", source = "plaintext")
    ApiKeyCreatedResponse toCreatedResponse(IssuedApiKey issued);
}
