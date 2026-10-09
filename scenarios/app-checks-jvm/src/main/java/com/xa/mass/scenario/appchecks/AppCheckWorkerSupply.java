package com.xa.mass.scenario.appchecks;

import java.util.Map;

/** Shared scenario binding for Task supply, Item queries and Worker property projections. */
final class AppCheckWorkerSupply {
    static final Map<String, String> APPS = Map.of("app-a", "app-a-sim", "app-b", "app-b-sim");
    static final String POOL = "assignment-window";
    static final String FUNCTION = "worker.assignment.available";
    static final String EVENT = "extension.worker.app.registration.check";
    static final String LAST = "lastAssignedAt";
    static final String COUNT = "windowAssignmentCount";

    private AppCheckWorkerSupply() {}
}
