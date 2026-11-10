package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.tenancy.TenantId;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.UUID;

/** Maps {@link TenantId} attributes to {@code uuid} columns for every entity. */
@Converter(autoApply = true)
public class TenantIdConverter implements AttributeConverter<TenantId, UUID> {

    @Override
    public UUID convertToDatabaseColumn(TenantId attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public TenantId convertToEntityAttribute(UUID dbData) {
        return dbData == null ? null : TenantId.of(dbData);
    }
}
