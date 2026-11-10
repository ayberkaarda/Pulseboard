package io.pulseboard.core.application.common;

import java.util.UUID;

/**
 * The requested resource does not exist for the calling tenant. Resources of other tenants are reported with
 * this exception as well, so their existence is never disclosed.
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final UUID resourceId;

    public ResourceNotFoundException(String resourceType, UUID resourceId) {
        super(resourceType + " " + resourceId + " was not found");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }
}
