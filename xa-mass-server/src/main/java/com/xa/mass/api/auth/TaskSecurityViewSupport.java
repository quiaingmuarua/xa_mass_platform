package com.xa.mass.api.auth;

import com.xa.mass.base.model.Task;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TaskSecurityViewSupport {

    public Map<String, Object> toSecurityView(Task task) {
        if (task == null) {
            return Map.of();
        }
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(task.getSharedConfig());
        if (ownershipStamp == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("createdByPrincipalId", ownershipStamp.getCreatedByPrincipalId());
        view.put("createdByPrincipalType", ownershipStamp.getCreatedByPrincipalType().name());
        return Map.copyOf(view);
    }
}
