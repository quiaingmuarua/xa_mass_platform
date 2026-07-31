package com.xa.mass.server.workerassembly;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-assembly",
        ignoreUnknownFields = false
)
public record ServerWorkerAssemblyProperties(
        @DefaultValue Map<String, BundleProperties> bundles
) {

    public ServerWorkerAssemblyProperties {
        if (bundles == null) {
            bundles = Map.of();
        } else {
            LinkedHashMap<String, BundleProperties> copy =
                    new LinkedHashMap<>();
            Set<String> workerGroupIds = new HashSet<>();
            Set<String> workerIds = new HashSet<>();
            bundles.forEach((bundleId, bundle) -> {
                requireNonBlank(bundleId, "bundleId");
                Objects.requireNonNull(bundle, "bundle");
                if (!workerGroupIds.add(bundle.workerGroupId())) {
                    throw new IllegalArgumentException(
                            "Worker bundles must use distinct "
                                    + "worker-group-id values: "
                                    + bundle.workerGroupId()
                    );
                }
                for (int index = 1;
                        index <= bundle.workerCount();
                        index++) {
                    String workerId = bundle.workerIdPrefix()
                            + String.format(
                                    Locale.ROOT,
                                    "%03d",
                                    index
                            );
                    if (!workerIds.add(workerId)) {
                        throw new IllegalArgumentException(
                                "Worker bundles must use distinct "
                                        + "worker IDs: "
                                        + workerId
                        );
                    }
                }
                copy.put(bundleId, bundle);
            });
            bundles = Collections.unmodifiableMap(copy);
        }
    }

    public enum BundleType {
        PHONE_NUMBER,
        STRING_UTILS
    }

    public record BundleProperties(
            BundleType type,
            String adapterId,
            String workerGroupId,
            String workerIdPrefix,
            @DefaultValue("10") int workerCount,
            @DefaultValue("10s") Duration requestTimeout,
            @DefaultValue("250ms") Duration reconnectInterval,
            @DefaultValue("15s") Duration connectTimeout
    ) {

        public BundleProperties {
            Objects.requireNonNull(type, "type");
            requireNonBlank(adapterId, "adapter-id");
            requireNonBlank(workerGroupId, "worker-group-id");
            requireNonBlank(workerIdPrefix, "worker-id-prefix");
            if (workerCount < 1 || workerCount > 100) {
                throw new IllegalArgumentException(
                        "worker-count must be between 1 and 100"
                );
            }
            requirePositive(requestTimeout, "request-timeout");
            requirePositive(
                    reconnectInterval,
                    "reconnect-interval"
            );
            requirePositive(connectTimeout, "connect-timeout");
        }
    }

    private static void requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
    }

    private static void requireNonBlank(
            String value,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }
}
