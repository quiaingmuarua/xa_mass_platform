package com.xa.mass.integration.androidworker;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class AndroidWorkerIdentityStore {

    static final String PREFERENCES = "android-worker-demo";
    private static final String GROUP_ID = "workerGroupId";
    private static final String CLIENT_KEY = "clientWorkerKey";
    private static final String WORKER_ID = "workerId";

    private final SharedPreferences preferences;
    private final String expectedWorkerGroupId;

    AndroidWorkerIdentityStore(
            Context context,
            String expectedWorkerGroupId
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        if (expectedWorkerGroupId == null
                || expectedWorkerGroupId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedWorkerGroupId must be non-blank"
            );
        }
        this.preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.expectedWorkerGroupId = expectedWorkerGroupId;
    }

    synchronized Identity loadOrCreateIdentity() {
        String storedGroup = preferences.getString(GROUP_ID, null);
        String clientWorkerKey = preferences.getString(CLIENT_KEY, null);
        String workerId = preferences.getString(WORKER_ID, null);

        boolean anyIdentityField = storedGroup != null
                || clientWorkerKey != null
                || workerId != null;
        if (anyIdentityField
                && (storedGroup == null || clientWorkerKey == null)) {
            throw new IllegalStateException(
                    "Stored Android Worker identity is incomplete"
            );
        }
        if (storedGroup != null
                && !expectedWorkerGroupId.equals(storedGroup)) {
            throw new IllegalStateException(
                    "Stored WorkerGroup does not match this application"
            );
        }
        if (clientWorkerKey != null && clientWorkerKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Stored clientWorkerKey is invalid"
            );
        }
        if (clientWorkerKey == null) {
            if (workerId != null) {
                throw new IllegalStateException(
                        "Stored workerId has no clientWorkerKey"
                );
            }
            clientWorkerKey = UUID.randomUUID().toString();
            boolean stored = preferences.edit()
                    .putString(GROUP_ID, expectedWorkerGroupId)
                    .putString(CLIENT_KEY, clientWorkerKey)
                    .commit();
            if (!stored) {
                throw new IllegalStateException(
                        "Unable to persist Android Worker identity"
                );
            }
        }
        if (workerId != null) {
            workerId = requireCanonicalWorkerId(workerId);
        }
        return new Identity(
                expectedWorkerGroupId,
                clientWorkerKey,
                workerId
        );
    }

    synchronized void persistWorkerId(String workerId) {
        String canonical = requireCanonicalWorkerId(workerId);
        String storedGroup = preferences.getString(GROUP_ID, null);
        String clientWorkerKey = preferences.getString(CLIENT_KEY, null);
        if (!expectedWorkerGroupId.equals(storedGroup)
                || clientWorkerKey == null
                || clientWorkerKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Android Worker identity must exist before workerId"
            );
        }
        String existing = preferences.getString(WORKER_ID, null);
        if (existing != null && !canonical.equals(existing)) {
            throw new IllegalStateException(
                    "Android Worker identity cannot be replaced"
            );
        }
        if (canonical.equals(existing)) {
            return;
        }
        if (!preferences.edit().putString(WORKER_ID, canonical).commit()) {
            throw new IllegalStateException(
                    "Unable to persist platform workerId"
            );
        }
    }

    private static String requireCanonicalWorkerId(String value) {
        try {
            if (value == null
                    || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Stored workerId is not a canonical UUID",
                    error
            );
        }
    }

    static final class Identity {

        private final String workerGroupId;
        private final String clientWorkerKey;
        private final String workerId;

        private Identity(
                String workerGroupId,
                String clientWorkerKey,
                String workerId
        ) {
            this.workerGroupId = workerGroupId;
            this.clientWorkerKey = clientWorkerKey;
            this.workerId = workerId;
        }

        String workerGroupId() {
            return workerGroupId;
        }

        String clientWorkerKey() {
            return clientWorkerKey;
        }

        String workerId() {
            return workerId;
        }
    }
}
