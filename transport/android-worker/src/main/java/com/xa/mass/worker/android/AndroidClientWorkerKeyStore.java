package com.xa.mass.worker.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class AndroidClientWorkerKeyStore {

    private final SharedPreferences preferences;
    private final String workerGroupId;
    private final String groupIdKey;
    private final String clientKeyKey;
    private final String workerIdKey;

    AndroidClientWorkerKeyStore(Context context, String workerGroupId) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        if (workerGroupId == null || workerGroupId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerGroupId must be non-blank"
            );
        }
        preferences = context.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.workerGroupId = workerGroupId;
        String prefix = AndroidWorkerIdentityStore.keyPrefix(workerGroupId)
                + ".identity.";
        groupIdKey = prefix + "workerGroupId";
        clientKeyKey = prefix + "clientWorkerKey";
        workerIdKey = prefix + "workerId";
    }

    synchronized String loadOrCreate() {
        String storedGroup = preferences.getString(groupIdKey, null);
        String clientWorkerKey = preferences.getString(clientKeyKey, null);
        String workerId = preferences.getString(workerIdKey, null);
        boolean any = storedGroup != null
                || clientWorkerKey != null
                || workerId != null;
        if (any && (storedGroup == null || clientWorkerKey == null)) {
            throw new IllegalStateException(
                    "Stored Android Worker identity is incomplete"
            );
        }
        if (storedGroup != null && !workerGroupId.equals(storedGroup)) {
            throw new IllegalStateException(
                    "Stored WorkerGroup does not match this application"
            );
        }
        if (clientWorkerKey != null) {
            if (clientWorkerKey.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Stored clientWorkerKey is invalid"
                );
            }
            return clientWorkerKey;
        }
        String generated = UUID.randomUUID().toString();
        if (!preferences.edit()
                .putString(groupIdKey, workerGroupId)
                .putString(clientKeyKey, generated)
                .commit()) {
            throw new IllegalStateException(
                    "Unable to persist Android clientWorkerKey"
            );
        }
        return generated;
    }
}
