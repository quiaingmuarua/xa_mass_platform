package com.xa.mass.scenario.appchecks;

/** Shared scenario binding for Task supply, Item queries and Worker property projections. */
final class AppCheckWorkerSupply {
    static final String POOL = "assignment-window";
    static final String FUNCTION = "worker.assignment.available";
    static final String EVENT = "extension.worker.app.registration.check";
    static final String LAST = "lastAssignedAt";
    static final String COUNT = "windowAssignmentCount";

    private AppCheckWorkerSupply() {}
}
