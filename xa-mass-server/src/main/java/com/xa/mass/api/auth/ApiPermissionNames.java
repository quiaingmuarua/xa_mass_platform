package com.xa.mass.api.auth;

import java.util.List;

public final class ApiPermissionNames {

    public static final String TASK_VIEW = "task:view";
    public static final String TASK_CREATE = "task:create";
    public static final String TASK_EDIT = "task:edit";
    public static final String TASK_GOVERN = "task:govern";
    public static final String TASK_CONTROL = "task:control";
    public static final String WORKER_VIEW = "worker:view";
    public static final String WORKER_EDIT = "worker:edit";
    public static final String RULE_VIEW = "rule:view";
    public static final String RULE_EDIT = "rule:edit";
    public static final String CONFIG_VIEW = "config:view";
    public static final String CONFIG_EDIT = "config:edit";
    public static final String USER_VIEW = "user:view";
    public static final String USER_EDIT = "user:edit";
    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_EDIT = "role:edit";
    public static final String AUDIT_VIEW = "audit:view";

    public static final List<String> ALL = List.of(
            TASK_VIEW,
            TASK_CREATE,
            TASK_EDIT,
            TASK_GOVERN,
            TASK_CONTROL,
            WORKER_VIEW,
            WORKER_EDIT,
            RULE_VIEW,
            RULE_EDIT,
            CONFIG_VIEW,
            CONFIG_EDIT,
            USER_VIEW,
            USER_EDIT,
            ROLE_VIEW,
            ROLE_EDIT,
            AUDIT_VIEW
    );

    private ApiPermissionNames() {
    }
}
