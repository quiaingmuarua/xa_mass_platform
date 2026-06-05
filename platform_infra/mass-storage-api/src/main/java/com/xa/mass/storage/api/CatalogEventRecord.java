package com.xa.mass.storage.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Durable event catalog metadata keyed by global event code.
 */
public record CatalogEventRecord(
        String code,
        String name,
        String description,
        List<String> payloadTypes,
        List<String> taskModes,
        boolean enabled,
        String defaultRoutingCode,
        List<String> projectCodes,
        String priorityClass,
        String responseMode,
        String deliveryAcknowledgementMode,
        String convergenceMode,
        String targetScope
) {

    public CatalogEventRecord {
        code = requireNonBlank(code, "code");
        name = requireNonBlank(name, "name");
        description = description == null ? "" : description;
        payloadTypes = immutableStrings(payloadTypes);
        taskModes = immutableStrings(taskModes);
        defaultRoutingCode = blankToNull(defaultRoutingCode);
        projectCodes = immutableStrings(projectCodes);
        priorityClass = blankToNull(priorityClass);
        responseMode = blankToNull(responseMode);
        deliveryAcknowledgementMode = blankToNull(deliveryAcknowledgementMode);
        convergenceMode = blankToNull(convergenceMode);
        targetScope = blankToNull(targetScope);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        return obj instanceof CatalogEventRecord other
                && enabled == other.enabled
                && Objects.equals(code, other.code)
                && Objects.equals(name, other.name)
                && Objects.equals(description, other.description)
                && Objects.equals(payloadTypes, other.payloadTypes)
                && Objects.equals(taskModes, other.taskModes)
                && Objects.equals(defaultRoutingCode, other.defaultRoutingCode)
                && Objects.equals(projectCodes, other.projectCodes)
                && Objects.equals(priorityClass, other.priorityClass)
                && Objects.equals(responseMode, other.responseMode)
                && Objects.equals(deliveryAcknowledgementMode, other.deliveryAcknowledgementMode)
                && Objects.equals(convergenceMode, other.convergenceMode)
                && Objects.equals(targetScope, other.targetScope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, name, description, payloadTypes, taskModes, enabled,
                defaultRoutingCode, projectCodes, priorityClass, responseMode,
                deliveryAcknowledgementMode, convergenceMode, targetScope);
    }
}
