package com.xa.mass.server.runtimeview;

import com.xa.mass.server.api.v1.runtimeview.model.WorkerNetworkObserveResponse;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.delivery.directcall.DirectCallService.AdapterCallHandle;
import com.xa.mass.server.delivery.directcall.DirectCallService.AdapterCallOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class WorkerNetworkObservationService {

    private static final String OPERATION =
            "runtimeView.observeWorkerNetwork";
    private static final String CONNECTION_SNAPSHOT_EVENT =
            "platform.adapter.worker-connections.snapshot";
    private final DirectCallService directCalls;

    public WorkerNetworkObservationService(DirectCallService directCalls) {
        this.directCalls = Objects.requireNonNull(
                directCalls,
                "directCalls"
        );
    }

    public DeferredResult<WorkerNetworkObserveResponse> observe(
            String endpointManagerId,
            List<String> workerIds,
            String requestId
    ) {
        requireWorkerIds(workerIds);
        AdapterCallHandle call;
        try {
            call = directCalls.beginAdapterCall(
                    endpointManagerId,
                    CONNECTION_SNAPSHOT_EVENT,
                    Jsons.toJson(Map.of("workerIds", workerIds)),
                    null
            );
        } catch (RuntimeException error) {
            throw RuntimeViewService.unavailable(
                    OPERATION,
                    endpointManagerId,
                    requestId,
                    error
            );
        }

        DeferredResult<WorkerNetworkObserveResponse> response =
                new DeferredResult<>(call.timeoutMillis());
        call.completion().whenComplete((outcome, failure) -> {
            if (failure != null) {
                response.setErrorResult(RuntimeViewService.unavailable(
                        OPERATION,
                        endpointManagerId,
                        requestId,
                        failure
                ));
                return;
            }
            try {
                response.setResult(toResponse(
                        endpointManagerId,
                        workerIds,
                        outcome
                ));
            } catch (RuntimeException error) {
                response.setErrorResult(RuntimeViewService.unavailable(
                        OPERATION,
                        endpointManagerId,
                        requestId,
                        error
                ));
            }
        });
        response.onTimeout(() -> directCalls.timeout(call));
        response.onError(ignored -> directCalls.cancel(call));
        response.onCompletion(() -> directCalls.cancel(call));
        return response;
    }

    private static WorkerNetworkObserveResponse toResponse(
            String endpointManagerId,
            List<String> workerIds,
            AdapterCallOutcome outcome
    ) {
        if (!outcome.observed()
                || !"200".equals(outcome.outcomeCode())) {
            throw new IllegalStateException(
                    "Adapter Network observation was not successful"
            );
        }
        Map<String, Object> result = Jsons.parseObject(
                outcome.opaqueResultPayload()
        );
        if (result.size() != 1
                || !result.containsKey("stateByWorkerId")
                || !(result.get("stateByWorkerId") instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "Adapter Network response must contain stateByWorkerId"
            );
        }
        if (!raw.keySet().equals(new LinkedHashSet<>(workerIds))) {
            throw new IllegalArgumentException(
                    "Adapter Network response identities do not match"
            );
        }
        Map<String, String> states = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            Object value = raw.get(workerId);
            if (!(value instanceof String state)) {
                throw new IllegalArgumentException(
                        "Adapter Network state must be a string"
                );
            }
            states.put(workerId, wireState(state));
        }
        return new WorkerNetworkObserveResponse(
                endpointManagerId,
                Instant.now(),
                states
        );
    }

    private static String wireState(String state) {
        return switch (state) {
            case "CONNECTED" -> "connected";
            case "DISCONNECTED" -> "disconnected";
            case "UNKNOWN" -> "unknown";
            default -> throw new IllegalArgumentException(
                    "Adapter Network state is unknown"
            );
        };
    }

    private static void requireWorkerIds(List<String> workerIds) {
        if (workerIds == null
                || workerIds.isEmpty()
                || workerIds.size() > 100
                || new LinkedHashSet<>(workerIds).size()
                != workerIds.size()
                || workerIds.stream().anyMatch(
                        value -> value == null || value.isBlank()
                )) {
            throw new ServerException(
                    ServerErrorCode.MALFORMED_REQUEST,
                    OPERATION,
                    null,
                    null
            );
        }
    }

}
