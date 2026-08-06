package com.xa.mass.worker.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AndroidWorkerIdentityStore {

    static final String PREFERENCES = "xa-mass-android-worker";

    private final SharedPreferences preferences;
    private final String expectedWorkerGroupId;
    private final String groupIdKey;
    private final String clientKeyKey;
    private final String workerIdKey;

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
        String prefix = keyPrefix(expectedWorkerGroupId) + ".identity.";
        groupIdKey = prefix + "workerGroupId";
        clientKeyKey = prefix + "clientWorkerKey";
        workerIdKey = prefix + "workerId";
    }

    synchronized Identity loadOrCreateIdentity(
            String configuredClientWorkerKey
    ) {
        String storedGroup = preferences.getString(groupIdKey, null);
        String clientWorkerKey = preferences.getString(clientKeyKey, null);
        String workerId = preferences.getString(workerIdKey, null);

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
        if (configuredClientWorkerKey != null) {
            if (configuredClientWorkerKey.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "configured clientWorkerKey must be non-blank"
                );
            }
            if (clientWorkerKey != null
                    && !clientWorkerKey.equals(configuredClientWorkerKey)) {
                throw new IllegalStateException(
                        "Configured clientWorkerKey conflicts with "
                                + "the stored Android Worker identity"
                );
            }
        }
        if (clientWorkerKey == null) {
            if (workerId != null) {
                throw new IllegalStateException(
                        "Stored workerId has no clientWorkerKey"
                );
            }
            clientWorkerKey = configuredClientWorkerKey == null
                    ? UUID.randomUUID().toString()
                    : configuredClientWorkerKey;
            boolean stored = preferences.edit()
                    .putString(groupIdKey, expectedWorkerGroupId)
                    .putString(clientKeyKey, clientWorkerKey)
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
        String storedGroup = preferences.getString(groupIdKey, null);
        String clientWorkerKey = preferences.getString(clientKeyKey, null);
        if (!expectedWorkerGroupId.equals(storedGroup)
                || clientWorkerKey == null
                || clientWorkerKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Android Worker identity must exist before workerId"
            );
        }
        String existing = preferences.getString(workerIdKey, null);
        if (existing != null && !canonical.equals(existing)) {
            throw new IllegalStateException(
                    "Android Worker identity cannot be replaced"
            );
        }
        if (canonical.equals(existing)) {
            return;
        }
        if (!preferences.edit().putString(workerIdKey, canonical).commit()) {
            throw new IllegalStateException(
                    "Unable to persist platform workerId"
            );
        }
    }

    static String keyPrefix(String workerGroupId) {
        String value = UUID.nameUUIDFromBytes(
                workerGroupId.getBytes(StandardCharsets.UTF_8)
        ).toString();
        return "worker." + value;
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
