package com.xa.mass.kernel.assembly;

import com.xa.mass.kernel.assignment.AssignmentDispatchApplicationConfig;
import com.xa.mass.kernel.result.ResultRoutingApplicationConfig;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityAssemblyConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchApplicationConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultApplicationConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The finite production configuration for all Java Kernel Pacers.
 *
 * <p>This object is intentionally parsed once by the Server assembly. It is
 * not a dynamic Pacer registry and it does not expose policy fields to the
 * Server.</p>
 */
public record KernelPacerPolicyConfig(
        ResultRoutingApplicationConfig resultRouting,
        WorkerServiceabilityAssemblyConfig workerServiceability,
        AssignmentDispatchApplicationConfig assignmentDispatch
) {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "resultRouting",
            "workerServiceability",
            "assignmentDispatch",
            "systemPolicy"
    );
    private static final Set<String> RESULT_ROUTING_FIELDS = Set.of(
            "intervalMillis"
    );
    private static final Set<String> SERVICEABILITY_FIELDS = Set.of(
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
    private static final Set<String> ASSIGNMENT_FIELDS = Set.of(
            "workerAllocationIntervalMillis",
            "runningActivationIntervalMillis",
            "taskDispatchIntervalMillis"
    );
    private static final Set<String> SYSTEM_POLICY_FIELDS = Set.of(
            "runningTaskSoftLimit"
    );

    public KernelPacerPolicyConfig {
        Objects.requireNonNull(resultRouting, "resultRouting");
        Objects.requireNonNull(workerServiceability, "workerServiceability");
        Objects.requireNonNull(assignmentDispatch, "assignmentDispatch");
    }

    public static KernelPacerPolicyConfig fromJson(String value) {
        return fromJson(value, System::currentTimeMillis);
    }

    static KernelPacerPolicyConfig fromJson(
            String value,
            LongSupplier currentTimeMillis
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config JSON must be present"
            );
        }
        Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        try {
            JsonNode root = JsonMapper.builder().build().readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "Kernel Pacer config must be a JSON object"
                );
            }
            rejectUnknown(root, ROOT_FIELDS, "Kernel Pacer config");
            return new KernelPacerPolicyConfig(
                    parseResultRouting(root),
                    parseServiceability(root, currentTimeMillis),
                    parseAssignment(root)
            );
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config is not valid JSON",
                    error
            );
        }
    }

    private static ResultRoutingApplicationConfig parseResultRouting(
            JsonNode root
    ) {
        JsonNode section = objectSection(root, "resultRouting");
        rejectUnknown(
                section,
                RESULT_ROUTING_FIELDS,
                "resultRouting"
        );
        return new ResultRoutingApplicationConfig(integralLong(
                section,
                "intervalMillis",
                ResultRoutingApplicationConfig.DEFAULT_INTERVAL_MILLIS,
                "resultRouting"
        ));
    }

    private static WorkerServiceabilityAssemblyConfig parseServiceability(
            JsonNode root,
            LongSupplier currentTimeMillis
    ) {
        JsonNode section = root.get("workerServiceability");
        if (section == null) {
            return new WorkerServiceabilityAssemblyConfig(
                    false,
                    0,
                    WorkerServiceabilityResultApplicationConfig.defaults(),
                    WorkerServiceabilityDispatchApplicationConfig.defaults()
            );
        }
        if (!section.isObject()) {
            throw new IllegalArgumentException(
                    "workerServiceability must be a JSON object"
            );
        }
        rejectUnknown(
                section,
                SERVICEABILITY_FIELDS,
                "workerServiceability"
        );
        int maxRecoveryAttempts = integralInt(
                section,
                "maxRecoveryAttempts",
                WorkerServiceabilityDispatchConfig
                        .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                "workerServiceability"
        );
        WorkerServiceabilityResultApplicationConfig result =
                new WorkerServiceabilityResultApplicationConfig(
                        integralLong(
                                section,
                                "resultIntervalMillis",
                                WorkerServiceabilityResultApplicationConfig
                                        .DEFAULT_INTERVAL_MILLIS,
                                "workerServiceability"
                        ),
                        new WorkerServiceabilityResultConfig(
                                maxRecoveryAttempts,
                                integralInt(
                                        section,
                                        "resultReportLimit",
                                        WorkerServiceabilityResultConfig
                                                .DEFAULT_RESULT_REPORT_LIMIT,
                                        "workerServiceability"
                                ),
                                integralLong(
                                        section,
                                        "evidenceMaxAgeMillis",
                                        WorkerServiceabilityResultConfig
                                                .DEFAULT_EVIDENCE_MAX_AGE_MILLIS,
                                        "workerServiceability"
                                )
                        )
                );
        WorkerServiceabilityDispatchApplicationConfig dispatch =
                new WorkerServiceabilityDispatchApplicationConfig(
                        integralLong(
                                section,
                                "dispatchIntervalMillis",
                                WorkerServiceabilityDispatchApplicationConfig
                                        .DEFAULT_INTERVAL_MILLIS,
                                "workerServiceability"
                        ),
                        new WorkerServiceabilityDispatchConfig(
                                integralInt(
                                        section,
                                        "taskScanLimit",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_TASK_SCAN_LIMIT,
                                        "workerServiceability"
                                ),
                                integralLong(
                                        section,
                                        "recoveryRetryIntervalMillis",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS,
                                        "workerServiceability"
                                ),
                                integralLong(
                                        section,
                                        "probeSweepRestartDelayMillis",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                                        "workerServiceability"
                                ),
                                maxRecoveryAttempts,
                                integralInt(
                                        section,
                                        "hotScanLimit",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_HOT_SCAN_LIMIT,
                                        "workerServiceability"
                                ),
                                integralInt(
                                        section,
                                        "recoveryScanLimit",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_RECOVERY_SCAN_LIMIT,
                                        "workerServiceability"
                                ),
                                stringArray(
                                        section,
                                        "probeExcludedEndpointManagerIds",
                                        WorkerServiceabilityDispatchConfig
                                                .DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS,
                                        "workerServiceability"
                                )
                        )
                );
        long floor = currentTimeMillis.getAsLong()
                / WorkerScoreCore.SLOT_MILLIS
                * WorkerScoreCore.SLOT_MILLIS;
        return new WorkerServiceabilityAssemblyConfig(
                true,
                floor,
                result,
                dispatch
        );
    }

    private static AssignmentDispatchApplicationConfig parseAssignment(
            JsonNode root
    ) {
        JsonNode assignment = objectSection(root, "assignmentDispatch");
        rejectUnknown(
                assignment,
                ASSIGNMENT_FIELDS,
                "assignmentDispatch"
        );
        JsonNode policy = objectSection(root, "systemPolicy");
        rejectUnknown(policy, SYSTEM_POLICY_FIELDS, "systemPolicy");
        return AssignmentDispatchApplicationConfig.create(
                integralLong(
                        assignment,
                        "workerAllocationIntervalMillis",
                        AssignmentDispatchApplicationConfig
                                .DEFAULT_INTERVAL_MILLIS,
                        "assignmentDispatch"
                ),
                integralLong(
                        assignment,
                        "runningActivationIntervalMillis",
                        AssignmentDispatchApplicationConfig
                                .DEFAULT_INTERVAL_MILLIS,
                        "assignmentDispatch"
                ),
                integralLong(
                        assignment,
                        "taskDispatchIntervalMillis",
                        AssignmentDispatchApplicationConfig
                                .DEFAULT_INTERVAL_MILLIS,
                        "assignmentDispatch"
                ),
                integralInt(
                        policy,
                        "runningTaskSoftLimit",
                        AssignmentDispatchApplicationConfig
                                .DEFAULT_RUNNING_TASK_SOFT_LIMIT,
                        "systemPolicy"
                )
        );
    }

    private static JsonNode objectSection(JsonNode root, String name) {
        JsonNode section = root.get(name);
        if (section == null) {
            return JsonMapper.builder().build().createObjectNode();
        }
        if (!section.isObject()) {
            throw new IllegalArgumentException(
                    name + " must be a JSON object"
            );
        }
        return section;
    }

    private static void rejectUnknown(
            JsonNode value,
            Set<String> allowed,
            String name
    ) {
        for (String field : value.propertyNames()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        "Unknown " + name + " field: " + field
                );
            }
        }
    }

    private static int integralInt(
            JsonNode section,
            String field,
            int defaultValue,
            String sectionName
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isIntegralNumber() || !configured.canConvertToInt()) {
            throw new IllegalArgumentException(
                    sectionName + "." + field + " must be an integer"
            );
        }
        return configured.intValue();
    }

    private static long integralLong(
            JsonNode section,
            String field,
            long defaultValue,
            String sectionName
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isIntegralNumber() || !configured.canConvertToLong()) {
            throw new IllegalArgumentException(
                    sectionName + "." + field + " must be an integer"
            );
        }
        return configured.longValue();
    }

    private static List<String> stringArray(
            JsonNode section,
            String field,
            List<String> defaultValue,
            String sectionName
    ) {
        JsonNode configured = section.get(field);
        if (configured == null) {
            return defaultValue;
        }
        if (!configured.isArray() || configured.size() > 100) {
            throw new IllegalArgumentException(
                    sectionName + "." + field
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
                        sectionName + "." + field
                                + " must contain unique non-empty ids"
                );
            }
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }
}
