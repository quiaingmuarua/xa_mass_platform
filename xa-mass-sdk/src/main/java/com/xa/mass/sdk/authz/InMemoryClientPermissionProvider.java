package com.xa.mass.sdk.authz;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryClientPermissionProvider implements ClientPermissionProvider {

    private final Map<String, Set<String>> permissions = new ConcurrentHashMap<>();

    @Override
    public Set<String> allowedEventCodes(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Collections.emptySet();
        }
        return permissions.getOrDefault(clientId.trim(), Collections.emptySet());
    }

    public void grant(String clientId, Collection<String> eventCodes) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        permissions.put(clientId.trim(), immutableSet(eventCodes));
    }

    private Set<String> immutableSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(normalized);
    }
}
