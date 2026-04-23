package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.authz.UserPermissionProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUserPermissionProvider implements UserPermissionProvider {

    private final Map<String, Set<String>> permissions = new ConcurrentHashMap<>();

    @Override
    public Set<String> allowedEventCodes(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptySet();
        }
        return permissions.getOrDefault(userId.trim(), Collections.emptySet());
    }

    public void grant(String userId, Collection<String> eventCodes) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        permissions.put(userId.trim(), immutableSet(eventCodes));
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
