package com.xa.mass.workerpack.sample.command.fixture;

import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.command.core.CommandDefinition;
import com.xa.mass.command.core.CommandRegistry;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SampleCommandRoutes {

    private SampleCommandRoutes() {
    }

    public static void registerSampleRoutes() {
        registerIfAbsent(command(
                "mock.state.get",
                SampleCommandRoutes::sampleStateGet,
                "Return the current mock fault-injection state for a worker.",
                true,
                "prepare", "verify"
        ));

        registerIfAbsent(command(
                "mock.delay.response",
                SampleCommandRoutes::sampleDelayResponse,
                "Delay future TASK result responses from the worker by a bounded number of milliseconds.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "mock.drop.outbound",
                SampleCommandRoutes::sampleDropOutbound,
                "Drop future TASK result responses using mode off/once/always.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "mock.task.result.status",
                SampleCommandRoutes::sampleTaskResultStatus,
                "Override future TASK result status with SUCCESS/FAILED, or clear the override.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "mock.disconnect",
                SampleCommandRoutes::sampleDisconnect,
                "Disconnect the target worker after the current disconnect task result is sent.",
                true,
                "trigger", "verify"
        ));

        registerIfAbsent(command(
                "mock.reset",
                SampleCommandRoutes::sampleReset,
                "Reset all mock fault-injection state for the target worker.",
                true,
                "prepare", "verify"
        ));

        registerIfAbsent(command(
                "fault.state.get",
                SampleCommandRoutes::faultStateGet,
                "Return the current fault profile state for a worker.",
                true,
                "prepare", "verify"
        ));

        registerIfAbsent(command(
                "fault.execution.profile",
                SampleCommandRoutes::faultExecutionProfile,
                "Configure a named deterministic fault profile for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.execution.delay",
                SampleCommandRoutes::faultExecutionDelay,
                "Configure deterministic worker execution delay bounds for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.execution.stall",
                SampleCommandRoutes::faultExecutionStall,
                "Configure worker execution stall behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.result.drop",
                SampleCommandRoutes::faultResultDrop,
                "Configure deterministic sample worker result-drop behavior.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.result.duplicate",
                SampleCommandRoutes::faultResultDuplicate,
                "Configure duplicate task-result submit behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.result.late",
                SampleCommandRoutes::faultResultLate,
                "Configure late task-result submit behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.result.malformed",
                SampleCommandRoutes::faultResultMalformed,
                "Configure malformed task-result submit behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.result.identity",
                SampleCommandRoutes::faultResultIdentity,
                "Configure invalid task-result identity behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.transport.disconnect",
                SampleCommandRoutes::faultTransportDisconnect,
                "Configure transport disconnect behavior for a sample worker.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.worker.state.flap",
                SampleCommandRoutes::faultWorkerStateFlap,
                "Report a one-shot worker state flip through the worker-control owner surface.",
                true,
                "prepare", "trigger", "verify"
        ));

        registerIfAbsent(command(
                "fault.reset",
                SampleCommandRoutes::faultReset,
                "Reset fault profile state for one worker or all sample workers.",
                true,
                "prepare", "verify"
        ));
    }

    private static Map<String, Object> sampleStateGet(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        return buildStateResponse(workerId, stateRegistry(context).getOrCreate(workerId), "state");
    }

    private static Map<String, Object> sampleDelayResponse(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        long millis = boundedLong(request, "millis", 0L, 30_000L);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResponseDelayMillis(millis);
        return buildStateResponse(workerId, state, "delay_updated");
    }

    private static Map<String, Object> sampleDropOutbound(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String modeValue = stringValue(request, "mode", "OFF");
        SampleClientState.DropMode dropMode;
        try {
            dropMode = SampleClientState.DropMode.fromValue(modeValue);
        } catch (IllegalArgumentException e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, e.getMessage());
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResponseDropMode(dropMode);
        return buildStateResponse(workerId, state, "drop_mode_updated");
    }

    private static Map<String, Object> sampleTaskResultStatus(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String status = stringValue(request, "status", "");
        if (!status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!"SUCCESS".equals(normalized) && !"FAILED".equals(normalized)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "sample task result status must be SUCCESS, FAILED, or blank");
            }
            status = normalized;
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResultStatusOverride(status);
        return buildStateResponse(workerId, state, "task_result_status_updated");
    }

    private static Map<String, Object> sampleDisconnect(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        SampleWorkerClient client = clientManager(context).getClient(workerId);
        if (client == null) {
            throw new CommandException(ErrorCode.INIT_ERROR, "sample client not found: " + workerId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("action", "disconnect");
        result.put("disconnectAfterAck", true);
        result.put("disconnectWorkerId", workerId);
        result.put("connected", client.isConnected());
        result.put("state", stateRegistry(context).getOrCreate(workerId).snapshot());
        return result;
    }

    private static Map<String, Object> sampleReset(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.reset();
        return buildStateResponse(workerId, state, "reset");
    }

    private static Map<String, Object> faultStateGet(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        return buildStateResponse(workerId, stateRegistry(context).getOrCreate(workerId), "fault_state");
    }

    private static Map<String, Object> faultExecutionProfile(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String profile = stringValue(request, "profile", "FAST");
        long seed = boundedLong(request, "seed", 0L, Long.MAX_VALUE);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        try {
            state.setFaultProfile(SampleWorkerFaultProfile.fromProfile(profile, seed));
        } catch (IllegalArgumentException e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, e.getMessage());
        }
        return buildStateResponse(workerId, state, "fault_profile_updated");
    }

    private static Map<String, Object> faultExecutionDelay(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        long minMs = boundedLong(request, "minMs", 0L, 30_000L);
        long maxMs = boundedLong(request, "maxMs", minMs, 30_000L);
        long seed = boundedLong(request, "seed", 0L, Long.MAX_VALUE);
        SampleWorkerFaultProfile.DelayDistribution distribution = delayDistribution(
                stringValue(request, "distribution", "UNIFORM"));
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.NORMAL,
                        seed
                )
                .delay(minMs, maxMs, distribution)
                .build());
        return buildStateResponse(workerId, state, "fault_delay_updated");
    }

    private static Map<String, Object> faultExecutionStall(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String until = stringValue(request, "until", "forever");
        long millis = boundedLong(request, "millis", 0L, 30_000L);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        SampleWorkerFaultProfile.Builder builder = SampleWorkerFaultProfile.builder(
                SampleWorkerFaultProfile.ProfileName.STUCK,
                boundedLong(request, "seed", 0L, Long.MAX_VALUE)
        );
        switch (until.trim().toLowerCase()) {
            case "forever" -> builder.stallMode(SampleWorkerFaultProfile.StallMode.FOREVER);
            case "lease-expiry", "lease_expiry", "leaseexpiry" ->
                    builder.stallMode(SampleWorkerFaultProfile.StallMode.LEASE_EXPIRY);
            case "ms", "duration" -> builder.stallDuration(millis);
            default -> throw new CommandException(
                    ErrorCode.PARSE_ERROR,
                    "fault.execution.stall until must be forever, lease-expiry, or ms"
            );
        }
        state.setFaultProfile(builder.build());
        return buildStateResponse(workerId, state, "fault_stall_updated");
    }

    private static Map<String, Object> faultResultDrop(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String mode = stringValue(request, "mode", "OFF");
        int percent = boundedInt(request, "percent", 0, 100);
        long seed = boundedLong(request, "seed", 0L, Long.MAX_VALUE);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT,
                        seed
                )
                .resultDrop(resultDropMode(mode), percent)
                .build());
        return buildStateResponse(workerId, state, "fault_result_drop_updated");
    }

    private static Map<String, Object> faultResultDuplicate(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        int count = boundedInt(request, "count", 1, 5);
        long gapMs = boundedLong(request, "gapMs", 25L, 5_000L);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT,
                        boundedLong(request, "seed", 0L, Long.MAX_VALUE)
                )
                .duplicateResult(count, gapMs)
                .build());
        return buildStateResponse(workerId, state, "fault_result_duplicate_updated");
    }

    private static Map<String, Object> faultResultLate(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        long delayPastLeaseMs = boundedLong(request, "delayPastLeaseMs", 1_000L, 120_000L);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.NEAR_TIMEOUT,
                        boundedLong(request, "seed", 0L, Long.MAX_VALUE)
                )
                .lateResultDelay(delayPastLeaseMs)
                .build());
        return buildStateResponse(workerId, state, "fault_result_late_updated");
    }

    private static Map<String, Object> faultResultMalformed(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String kind = stringValue(request, "kind", "missing_message_id");
        SampleWorkerFaultProfile.MalformedResultKind malformedKind;
        try {
            malformedKind = SampleWorkerFaultProfile.MalformedResultKind.valueOf(
                    kind.trim().toUpperCase().replace('-', '_')
            );
        } catch (IllegalArgumentException e) {
            throw new CommandException(
                    ErrorCode.PARSE_ERROR,
                    "fault.result.malformed kind must be missing_message_id, invalid_status, or invalid_payload"
            );
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.MALFORMED_RESULT,
                        boundedLong(request, "seed", 0L, Long.MAX_VALUE)
                )
                .malformedResultKind(malformedKind)
                .build());
        return buildStateResponse(workerId, state, "fault_result_malformed_updated");
    }

    private static Map<String, Object> faultResultIdentity(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String kind = stringValue(request, "kind", "wrongCorrelation");
        SampleWorkerFaultProfile.ResultIdentityKind identityKind;
        try {
            identityKind = SampleWorkerFaultProfile.ResultIdentityKind.valueOf(
                    normalizeEnumToken(kind)
            );
        } catch (IllegalArgumentException e) {
            throw new CommandException(
                    ErrorCode.PARSE_ERROR,
                    "fault.result.identity kind must be wrongCorrelation, wrongWorker, or wrongLease"
            );
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.WRONG_IDENTITY,
                        boundedLong(request, "seed", 0L, Long.MAX_VALUE)
                )
                .resultIdentityKind(identityKind)
                .build());
        return buildStateResponse(workerId, state, "fault_result_identity_updated");
    }

    private static Map<String, Object> faultTransportDisconnect(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String phase = stringValue(request, "phase", "before_result");
        SampleWorkerFaultProfile.DisconnectPhase disconnectPhase;
        try {
            disconnectPhase = SampleWorkerFaultProfile.DisconnectPhase.valueOf(
                    phase.trim().toUpperCase().replace('-', '_')
            );
        } catch (IllegalArgumentException e) {
            throw new CommandException(
                    ErrorCode.PARSE_ERROR,
                    "fault.transport.disconnect phase must be before_receive, after_receive, before_result, or after_result"
            );
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setFaultProfile(SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT,
                        boundedLong(request, "seed", 0L, Long.MAX_VALUE)
                )
                .disconnectPhase(disconnectPhase)
                .build());
        return buildStateResponse(workerId, state, "fault_transport_disconnect_updated");
    }

    private static Map<String, Object> faultWorkerStateFlap(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String reportedState = normalizeWorkerState(stringValue(request, "state", "DRAINING"));
        long stateVersion = optionalPositiveLong(request, "stateVersion", System.currentTimeMillis());
        WorkerStateReportSnapshot report = workerControl(context).reportWorkerState(new WorkerStateReportRequest(
                workerId,
                stateVersion,
                reportedState,
                "fault.worker.state.flap",
                Instant.now(),
                Map.of("faultEvent", "fault.worker.state.flap")
        ));
        Map<String, Object> result = buildStateResponse(
                workerId,
                stateRegistry(context).getOrCreate(workerId),
                "fault_worker_state_flap_reported"
        );
        result.put("reportedState", reportedState);
        result.put("stateVersion", stateVersion);
        result.put("report", workerStateReportSnapshot(report));
        return result;
    }

    private static Map<String, Object> faultReset(JsonObject request, CommandContext context) {
        String scope = stringValue(request, "scope", "worker");
        SampleClientStateRegistry registry = stateRegistry(context);
        if ("all".equalsIgnoreCase(scope)) {
            registry.resetAllFaultProfiles();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "fault_reset");
            result.put("scope", "all");
            return result;
        }
        if (!"worker".equalsIgnoreCase(scope)) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "fault.reset scope must be worker or all");
        }
        String workerId = requireWorkerId(request);
        SampleClientState state = registry.getOrCreate(workerId);
        state.resetFaultProfile();
        return buildStateResponse(workerId, state, "fault_reset");
    }

    private static Map<String, Object> buildStateResponse(String workerId, SampleClientState state, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("action", action);
        result.put("state", state.snapshot());
        return result;
    }

    private static SampleClientStateRegistry stateRegistry(CommandContext context) {
        return context.require(SampleClientStateRegistry.class);
    }

    private static ClientSessionManager clientManager(CommandContext context) {
        return context.require(ClientSessionManager.class);
    }

    private static WorkerControlOperations workerControl(CommandContext context) {
        WorkerControlOperations workerControl = context.get(WorkerControlOperations.class);
        if (workerControl == null) {
            throw new CommandException(
                    ErrorCode.INIT_ERROR,
                    "fault.worker.state.flap requires WorkerControlOperations service"
            );
        }
        return workerControl;
    }

    private static String requireWorkerId(JsonObject request) {
        String workerId = stringValue(request, "workerId", "");
        if (workerId.isBlank()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "workerId is required");
        }
        return workerId;
    }

    private static String stringValue(JsonObject json, String field, String defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        return json.get(field).getAsString();
    }

    private static long boundedLong(JsonObject json, String field, long defaultValue, long maxValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            long value = json.get(field).getAsLong();
            return Math.max(0L, Math.min(value, maxValue));
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be a number");
        }
    }

    private static long optionalPositiveLong(JsonObject json, String field, long defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            long value = json.get(field).getAsLong();
            if (value <= 0L) {
                throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be greater than 0");
            }
            return value;
        } catch (CommandException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be a number");
        }
    }

    private static int boundedInt(JsonObject json, String field, int defaultValue, int maxValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            int value = json.get(field).getAsInt();
            return Math.max(0, Math.min(value, maxValue));
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be a number");
        }
    }

    private static String normalizeWorkerState(String state) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AVAILABLE", "DEGRADED", "DRAINING", "OFFLINE" -> normalized;
            default -> throw new CommandException(
                    ErrorCode.PARSE_ERROR,
                    "fault.worker.state.flap state must be AVAILABLE, DEGRADED, DRAINING, or OFFLINE"
            );
        };
    }

    private static SampleWorkerFaultProfile.DelayDistribution delayDistribution(String value) {
        try {
            return SampleWorkerFaultProfile.DelayDistribution.valueOf(normalizeEnumToken(value));
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "unsupported fault delay distribution: " + value);
        }
    }

    private static SampleWorkerFaultProfile.ResultDropMode resultDropMode(String value) {
        try {
            return SampleWorkerFaultProfile.ResultDropMode.valueOf(normalizeEnumToken(value));
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "unsupported fault result drop mode: " + value);
        }
    }

    private static String normalizeEnumToken(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        char previous = 0;
        for (char current : value.trim().toCharArray()) {
            if (current == '-' || current == '_' || Character.isWhitespace(current)) {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '_') {
                    builder.append('_');
                }
            } else {
                if (Character.isUpperCase(current)
                        && Character.isLowerCase(previous)
                        && !builder.isEmpty()
                        && builder.charAt(builder.length() - 1) != '_') {
                    builder.append('_');
                }
                builder.append(Character.toUpperCase(current));
            }
            previous = current;
        }
        return builder.toString();
    }

    private static Map<String, Object> workerStateReportSnapshot(WorkerStateReportSnapshot report) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (report == null) {
            return result;
        }
        result.put("status", report.status());
        result.put("workerId", report.workerId());
        result.put("stateVersion", report.stateVersion());
        result.put("accepted", report.accepted());
        result.put("projectionChanged", report.projectionChanged());
        result.put("reason", report.reason());
        if (report.projection() != null) {
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("workerId", report.projection().workerId());
            projection.put("stateVersion", report.projection().stateVersion());
            projection.put("state", report.projection().state());
            projection.put("reason", report.projection().reason());
            projection.put("observedAt", String.valueOf(report.projection().observedAt()));
            projection.put("acceptedAt", String.valueOf(report.projection().acceptedAt()));
            result.put("projection", projection);
        }
        return result;
    }

    private static void registerIfAbsent(CommandDefinition<?, ?> definition) {
        if (!CommandRegistry.contains(definition.getEvent())) {
            CommandRegistry.register(definition);
        }
    }

    private static CommandDefinition<JsonObject, Map<String, Object>> command(
            String event,
            com.xa.mass.command.core.CommandHandler<JsonObject, Map<String, Object>> handler,
            String summary,
            boolean safeForScenario,
            String... phases) {
        return new CommandDefinition<>(
                event,
                handler,
                json -> json,
                CommandDefinition.Descriptor.simple(
                        event,
                        summary,
                        java.util.List.of(phases),
                        safeForScenario
                )
        );
    }
}
