package com.xa.mass.transport.starter;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter-starter-owned declaration for one embedded adapter runtime.
 */
public record EmbeddedAdapterDeclaration(
        String type,
        String adapterId,
        String dispatchQueueKey,
        String resultQueueKey,
        Map<String, String> options
) {

    public static final String DEFAULT_RESULT_QUEUE_KEY = "default";

    public EmbeddedAdapterDeclaration {
        type = requireText(type, "type").toLowerCase(Locale.ROOT);
        adapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        dispatchQueueKey = requireText(dispatchQueueKey, "dispatchQueueKey");
        resultQueueKey = requireText(resultQueueKey, "resultQueueKey");
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
    }

    public static EmbeddedAdapterDeclaration pollingDefault() {
        return new EmbeddedAdapterDeclaration(
                EmbeddedAdapterStarterDefaults.TYPE_POLLING,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                DEFAULT_RESULT_QUEUE_KEY,
                Map.of()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
