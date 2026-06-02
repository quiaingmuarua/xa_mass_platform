package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal framework-owned task ownership stamp stored in sharedConfig.
 */
public final class TaskOwnershipStamp {

    public static final String SHARED_CONFIG_KEY = "_massSecurity";
    public static final String CREATED_BY_PRINCIPAL_ID = "createdByPrincipalId";
    public static final String CREATED_BY_PRINCIPAL_TYPE = "createdByPrincipalType";

    private final String createdByPrincipalId;
    private final PrincipalType createdByPrincipalType;

    public TaskOwnershipStamp(String createdByPrincipalId, PrincipalType createdByPrincipalType) {
        this.createdByPrincipalId = requireNonBlank(createdByPrincipalId, "createdByPrincipalId");
        this.createdByPrincipalType = Objects.requireNonNull(createdByPrincipalType, "createdByPrincipalType");
    }

    public static TaskOwnershipStamp fromPrincipal(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal");
        return new TaskOwnershipStamp(principal.getPrincipalId(), principal.getPrincipalType());
    }

    public String getCreatedByPrincipalId() {
        return createdByPrincipalId;
    }

    public PrincipalType getCreatedByPrincipalType() {
        return createdByPrincipalType;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(CREATED_BY_PRINCIPAL_ID, createdByPrincipalId);
        data.put(CREATED_BY_PRINCIPAL_TYPE, createdByPrincipalType.name());
        return Collections.unmodifiableMap(data);
    }

    public static TaskOwnershipStamp fromSharedConfig(Map<String, Object> sharedConfig) {
        if (sharedConfig == null || sharedConfig.isEmpty()) {
            return null;
        }
        Object raw = sharedConfig.get(SHARED_CONFIG_KEY);
        if (!(raw instanceof Map<?, ?> data)) {
            return null;
        }
        Object principalId = data.get(CREATED_BY_PRINCIPAL_ID);
        Object principalType = data.get(CREATED_BY_PRINCIPAL_TYPE);
        if (principalId == null || principalType == null) {
            return null;
        }
        return new TaskOwnershipStamp(
                String.valueOf(principalId).trim(),
                PrincipalType.valueOf(String.valueOf(principalType).trim())
        );
    }

    public static Map<String, Object> applyToSharedConfig(Map<String, Object> sharedConfig, TaskOwnershipStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        Map<String, Object> merged = new LinkedHashMap<>();
        if (sharedConfig != null && !sharedConfig.isEmpty()) {
            merged.putAll(sharedConfig);
        }
        merged.put(SHARED_CONFIG_KEY, stamp.toMap());
        return Collections.unmodifiableMap(merged);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
