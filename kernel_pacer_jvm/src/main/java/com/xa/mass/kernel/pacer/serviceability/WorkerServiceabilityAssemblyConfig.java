package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record WorkerServiceabilityAssemblyConfig(
        boolean enabled,
        long hotEligibilityFloorMillis,
        WorkerServiceabilityResultApplicationConfig result,
        WorkerServiceabilityDispatchApplicationConfig dispatch
) {

    private static final Set<String> FIELDS = Set.of(
            "dispatchIntervalMillis",
            "resultIntervalMillis",
            "taskScanLimit",
            "recoveryRetryIntervalMillis",
            "probeSweepRestartDelayMillis",
            "maxRecoveryAttempts",
            "hotScanLimit",
            "recoveryScanLimit",
            "resultReportLimit",
            "evidenceMaxAgeMillis",
            "probeExcludedEndpointManagerIds"
    );

    public WorkerServiceabilityAssemblyConfig {
        java.util.Objects.requireNonNull(result, "result");
        java.util.Objects.requireNonNull(dispatch, "dispatch");
        if (enabled) {
            requireFloor(hotEligibilityFloorMillis);
        } else if (hotEligibilityFloorMillis != 0) {
            throw new IllegalArgumentException(
                    "disabled Serviceability must not carry a HOT floor"
            );
        }
    }

    public static WorkerServiceabilityAssemblyConfig fromKernelConfigJson(
            String value
    ) {
        return fromKernelConfigJson(value, System::currentTimeMillis);
    }

    static WorkerServiceabilityAssemblyConfig fromKernelConfigJson(
            String value,
            LongSupplier currentTimeMillis
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config JSON must be present"
            );
        }
        java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
        try {
            JsonNode root = JsonMapper.builder().build().readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "Kernel Pacer config must be a JSON object"
                );
            }
            JsonNode section = root.get("workerServiceability");
            if (section == null) {
                return disabled();
            }
            if (!section.isObject()) {
                throw new IllegalArgumentException(
                        "workerServiceability must be a JSON object"
                );
            }
            for (String field : section.propertyNames()) {
                if (!FIELDS.contains(field)) {
                    throw new IllegalArgumentException(
                            "Unknown workerServiceability field: " + field
                    );
                }
            }

            int maxRecoveryAttempts = integralInt(
                    section,
                    "maxRecoveryAttempts",
                    WorkerServiceabilityDispatchConfig
                            .DEFAULT_MAX_RECOVERY_ATTEMPTS
            );
            WorkerServiceabilityResultApplicationConfig result =
                    new WorkerServiceabilityResultApplicationConfig(
                            integralLong(
                                    section,
                                    "resultIntervalMillis",
                                    WorkerServiceabilityResultApplicationConfig
                                            .DEFAULT_INTERVAL_MILLIS
                            ),
                            new WorkerServiceabilityResultConfig(
                                    maxRecoveryAttempts,
                                    integralInt(
                                            section,
                                            "resultReportLimit",
                                            WorkerServiceabilityResultConfig
                                                    .DEFAULT_RESULT_REPORT_LIMIT
                                    ),
                                    integralLong(
                                            section,
                                            "evidenceMaxAgeMillis",
                                            WorkerServiceabilityResultConfig
                                                    .DEFAULT_EVIDENCE_MAX_AGE_MILLIS
                                    )
                            )
                    );
            WorkerServiceabilityDispatchApplicationConfig dispatch =
                    new WorkerServiceabilityDispatchApplicationConfig(
                            integralLong(
                                    section,
                                    "dispatchIntervalMillis",
                                    WorkerServiceabilityDispatchApplicationConfig
                                            .DEFAULT_INTERVAL_MILLIS
                            ),
                            new WorkerServiceabilityDispatchConfig(
                                    integralInt(
                                            section,
                                            "taskScanLimit",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_TASK_SCAN_LIMIT
                                    ),
                                    integralLong(
                                            section,
                                            "recoveryRetryIntervalMillis",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS
                                    ),
                                    integralLong(
                                            section,
                                            "probeSweepRestartDelayMillis",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS
                                    ),
                                    maxRecoveryAttempts,
                                    integralInt(
                                            section,
                                            "hotScanLimit",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_HOT_SCAN_LIMIT
                                    ),
                                    integralInt(
                                            section,
                                            "recoveryScanLimit",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_RECOVERY_SCAN_LIMIT
                                    ),
                                    stringArray(
                                            section,
                                            "probeExcludedEndpointManagerIds",
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
                                    )
                            )
                    );
            long current = currentTimeMillis.getAsLong();
            long floor = current / WorkerScoreCore.SLOT_MILLIS
                    * WorkerScoreCore.SLOT_MILLIS;
            return new WorkerServiceabilityAssemblyConfig(
                    true,
                    floor,
                    result,
                    dispatch
            );
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config is not valid JSON",
                    error
            );
        }
    }

    private static WorkerServiceabilityAssemblyConfig disabled() {
        return new WorkerServiceabilityAssemblyConfig(
                false,
                0,
                WorkerServiceabilityResultApplicationConfig.defaults(),
                WorkerServiceabilityDispatchApplicationConfig.defaults()
        );
    }

    private static List<String> stringArray(
            JsonNode section,
            String field,
            List<String> defaultValue
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isArray() || configured.size() > 100) {
            throw new IllegalArgumentException(
                    "workerServiceability." + field
                            + " must be an array with at most 100 ids"
            );
        }
        List<String> values = new ArrayList<>(configured.size());
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < configured.size(); index++) {
            JsonNode value = configured.get(index);
            if (value == null || !value.isTextual()
                    || value.textValue().isEmpty()
                    || !unique.add(value.textValue())) {
                throw new IllegalArgumentException(
                        "workerServiceability." + field
                                + " must contain unique non-empty ids"
                );
            }
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    private static int integralInt(
            JsonNode section,
            String field,
            int defaultValue
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isIntegralNumber() || !configured.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "workerServiceability." + field
                            + " must be an integer"
            );
        }
        return configured.intValue();
    }

    private static long integralLong(
            JsonNode section,
            String field,
            long defaultValue
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isIntegralNumber() || !configured.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "workerServiceability." + field
                            + " must be an integer"
            );
        }
        return configured.longValue();
    }

    static void requireFloor(long floor) {
        if (floor < WorkerScoreCore.SLOT_MILLIS
                || floor % WorkerScoreCore.SLOT_MILLIS != 0
                || floor > WorkerScoreCore.MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }
}
