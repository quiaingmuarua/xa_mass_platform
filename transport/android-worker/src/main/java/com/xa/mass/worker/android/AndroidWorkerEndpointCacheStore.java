package com.xa.mass.worker.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class AndroidWorkerEndpointCacheStore {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final SharedPreferences preferences;
    private final String groupIdKey;
    private final String workerIdKey;
    private final String endpointUriKey;
    private final String propertiesSha256Key;

    AndroidWorkerEndpointCacheStore(
            Context context,
            String workerGroupId
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        preferences = context.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        );
        String prefix = AndroidWorkerIdentityStore.keyPrefix(
                requireNonBlank(workerGroupId, "workerGroupId")
        ) + ".endpoint.";
        groupIdKey = prefix + "workerGroupId";
        workerIdKey = prefix + "workerId";
        endpointUriKey = prefix + "endpointUri";
        propertiesSha256Key = prefix + "workerPropertiesSha256";
    }

    synchronized Optional<Entry> load() {
        boolean any = preferences.contains(groupIdKey)
                || preferences.contains(workerIdKey)
                || preferences.contains(endpointUriKey)
                || preferences.contains(propertiesSha256Key);
        if (!any) {
            return Optional.empty();
        }
        try {
            String groupId = requireNonBlank(
                    preferences.getString(groupIdKey, null),
                    "cachedWorkerGroupId"
            );
            String workerId = requireCanonicalWorkerId(
                    preferences.getString(workerIdKey, null)
            );
            URI endpointUri = requireWebSocketUri(
                    preferences.getString(endpointUriKey, null)
            );
            String propertiesSha256 = preferences.getString(
                    propertiesSha256Key,
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
                .putString(groupIdKey, group)
                .putString(workerIdKey, canonicalWorkerId)
                .putString(endpointUriKey, websocketUri.toString())
                .putString(propertiesSha256Key, propertiesSha256)
                .commit();
        if (!stored) {
            throw new IllegalStateException(
                    "Unable to persist Android Worker endpoint cache"
            );
        }
    }

    synchronized void clear() {
        if (!preferences.edit()
                .remove(groupIdKey)
                .remove(workerIdKey)
                .remove(endpointUriKey)
                .remove(propertiesSha256Key)
                .commit()) {
            throw new IllegalStateException(
                    "Unable to clear Android Worker endpoint cache"
            );
        }
    }

    private void clearQuietly() {
        preferences.edit()
                .remove(groupIdKey)
                .remove(workerIdKey)
                .remove(endpointUriKey)
                .remove(propertiesSha256Key)
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
