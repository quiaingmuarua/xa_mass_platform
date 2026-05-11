package com.xa.mass.api.auth;

import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TaskSecurityViewSupport {

    public Map<String, Object> toSecurityView(Map<String, Object> sharedConfig) {
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(sharedConfig);
        if (ownershipStamp == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("createdByPrincipalId", ownershipStamp.getCreatedByPrincipalId());
        view.put("createdByPrincipalType", ownershipStamp.getCreatedByPrincipalType().name());
        return Map.copyOf(view);
    }

    public Map<String, Object> sanitizeSharedConfig(Map<String, Object> sharedConfig) {
        if (sharedConfig == null || sharedConfig.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(sharedConfig);
        sanitized.remove(TaskOwnershipStamp.SHARED_CONFIG_KEY);
        return Map.copyOf(sanitized);
    }
}
