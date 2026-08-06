package com.xa.mass.integration.androidworker;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class AndroidWorkerEndpointCacheStore {

    private static final String GROUP_ID = "cachedWorkerGroupId";
    private static final String WORKER_ID = "cachedWorkerId";
    private static final String ENDPOINT_URI = "cachedEndpointUri";
    private static final String PROPERTIES_SHA256 =
            "cachedWorkerPropertiesSha256";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final SharedPreferences preferences;

    AndroidWorkerEndpointCacheStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        preferences = context.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    synchronized Optional<Entry> load() {
        boolean any = preferences.contains(GROUP_ID)
                || preferences.contains(WORKER_ID)
                || preferences.contains(ENDPOINT_URI)
                || preferences.contains(PROPERTIES_SHA256);
        if (!any) {
            return Optional.empty();
        }
        try {
            String groupId = requireNonBlank(
                    preferences.getString(GROUP_ID, null),
                    GROUP_ID
            );
            String workerId = requireCanonicalWorkerId(
                    preferences.getString(WORKER_ID, null)
            );
            URI endpointUri = requireWebSocketUri(
                    preferences.getString(ENDPOINT_URI, null)
            );
            String propertiesSha256 = preferences.getString(
                    PROPERTIES_SHA256,
                    null
            );
            if (propertiesSha256 == null
                    || !SHA256.matcher(propertiesSha256).matches()) {
                throw new IllegalArgumentException(
                        "cachedWorkerPropertiesSha256 is invalid"
                );
            }
            return Optional.of(new Entry(
                    groupId,
                    workerId,
                    endpointUri,
                    propertiesSha256
            ));
        } catch (RuntimeException invalidCache) {
            clearQuietly();
            return Optional.empty();
        }
    }

    synchronized void store(
            String workerGroupId,
            String workerId,
            URI endpointUri,
            String propertiesSha256
    ) {
        String group = requireNonBlank(workerGroupId, "workerGroupId");
        String canonicalWorkerId = requireCanonicalWorkerId(workerId);
        URI websocketUri = requireWebSocketUri(
                endpointUri == null ? null : endpointUri.toString()
        );
        if (propertiesSha256 == null
                || !SHA256.matcher(propertiesSha256).matches()) {
            throw new IllegalArgumentException(
                    "propertiesSha256 must be lowercase SHA-256"
            );
        }
        boolean stored = preferences.edit()
                .putString(GROUP_ID, group)
                .putString(WORKER_ID, canonicalWorkerId)
                .putString(ENDPOINT_URI, websocketUri.toString())
                .putString(PROPERTIES_SHA256, propertiesSha256)
                .commit();
        if (!stored) {
            throw new IllegalStateException(
                    "Unable to persist Android Worker endpoint cache"
            );
        }
    }

    synchronized void clear() {
        if (!preferences.edit()
                .remove(GROUP_ID)
                .remove(WORKER_ID)
                .remove(ENDPOINT_URI)
                .remove(PROPERTIES_SHA256)
                .commit()) {
            throw new IllegalStateException(
                    "Unable to clear Android Worker endpoint cache"
            );
        }
    }

    private void clearQuietly() {
        preferences.edit()
                .remove(GROUP_ID)
                .remove(WORKER_ID)
                .remove(ENDPOINT_URI)
                .remove(PROPERTIES_SHA256)
                .commit();
    }

    private static String requireCanonicalWorkerId(String workerId) {
        String value = requireNonBlank(workerId, "workerId");
        if (!UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException(
                    "workerId must be a canonical UUID"
            );
        }
        return value;
    }

    private static URI requireWebSocketUri(String encoded) {
        URI uri = URI.create(requireNonBlank(encoded, "endpointUri"));
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || (!("ws".equalsIgnoreCase(uri.getScheme()))
                && !("wss".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException(
                    "endpointUri must be an absolute WS or WSS URI"
            );
        }
        return uri;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    static final class Entry {

        private final String workerGroupId;
        private final String workerId;
        private final URI endpointUri;
        private final String propertiesSha256;

        private Entry(
                String workerGroupId,
                String workerId,
                URI endpointUri,
                String propertiesSha256
        ) {
            this.workerGroupId = workerGroupId;
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.propertiesSha256 = propertiesSha256;
        }

        String workerGroupId() {
            return workerGroupId;
        }

        String workerId() {
            return workerId;
        }

        URI endpointUri() {
            return endpointUri;
        }

        String propertiesSha256() {
            return propertiesSha256;
        }
    }
}
