package com.xa.mass.worker.android;

import android.content.Context;
import android.content.SharedPreferences;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

final class AndroidWorkerIdentityStore implements WorkerIdentityStore {

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
        preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.expectedWorkerGroupId = expectedWorkerGroupId;
        String prefix = keyPrefix(expectedWorkerGroupId) + ".identity.";
        groupIdKey = prefix + "workerGroupId";
        clientKeyKey = prefix + "clientWorkerKey";
        workerIdKey = prefix + "workerId";
    }

    @Override
    public synchronized Optional<String> loadWorkerId() {
        validateCoordinate();
        String workerId = preferences.getString(workerIdKey, null);
        return workerId == null
                ? Optional.empty()
                : Optional.of(requireCanonicalWorkerId(workerId));
    }

    @Override
    public synchronized void saveWorkerId(String workerId) {
        String canonical = requireCanonicalWorkerId(workerId);
        validateCoordinate();
        String clientWorkerKey = preferences.getString(clientKeyKey, null);
        if (clientWorkerKey == null || clientWorkerKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Android clientWorkerKey must exist before workerId"
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

    private void validateCoordinate() {
        String storedGroup = preferences.getString(groupIdKey, null);
        String clientWorkerKey = preferences.getString(clientKeyKey, null);
        String workerId = preferences.getString(workerIdKey, null);
        boolean any = storedGroup != null
                || clientWorkerKey != null
                || workerId != null;
        if (!any) {
            return;
        }
        if (storedGroup == null || clientWorkerKey == null) {
            throw new IllegalStateException(
                    "Stored Android Worker identity is incomplete"
            );
        }
        if (!expectedWorkerGroupId.equals(storedGroup)) {
            throw new IllegalStateException(
                    "Stored WorkerGroup does not match this application"
            );
        }
        if (clientWorkerKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Stored clientWorkerKey is invalid"
            );
        }
    }

    static String keyPrefix(String workerGroupId) {
        return "worker." + UUID.nameUUIDFromBytes(
                workerGroupId.getBytes(StandardCharsets.UTF_8)
        );
    }

    static String requireCanonicalWorkerId(String value) {
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
}
