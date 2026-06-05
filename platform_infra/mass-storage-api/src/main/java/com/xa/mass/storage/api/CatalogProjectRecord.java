package com.xa.mass.storage.api;

import com.xa.mass.base.model.TenantConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Durable project catalog metadata with project-event bindings.
 */
public record CatalogProjectRecord(
        String tenantId,
        String code,
        String name,
        String description,
        boolean enabled,
        String ownerPrincipalId,
        List<String> eventCodes
) {

    public CatalogProjectRecord {
        tenantId = tenantId == null || tenantId.isBlank()
                ? TenantConstants.DEFAULT_TENANT_ID
                : tenantId.trim();
        code = requireNonBlank(code, "code");
        name = requireNonBlank(name, "name");
        description = description == null ? "" : description;
        ownerPrincipalId = ownerPrincipalId == null || ownerPrincipalId.isBlank()
                ? null
                : ownerPrincipalId.trim();
        eventCodes = immutableStrings(eventCodes);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> immutableStrings(Iterable<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CatalogProjectRecord other
                && enabled == other.enabled
                && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(code, other.code)
                && Objects.equals(name, other.name)
                && Objects.equals(description, other.description)
                && Objects.equals(ownerPrincipalId, other.ownerPrincipalId)
                && Objects.equals(eventCodes, other.eventCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, code, name, description, enabled, ownerPrincipalId, eventCodes);
    }
}
