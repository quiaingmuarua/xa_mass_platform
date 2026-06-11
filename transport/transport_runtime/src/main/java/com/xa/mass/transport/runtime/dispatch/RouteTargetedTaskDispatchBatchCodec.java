package com.xa.mass.transport.runtime.dispatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;
import java.util.Objects;

/**
 * JSON codec for route-targeted dispatch handoff payloads.
 */
public final class RouteTargetedTaskDispatchBatchCodec {

    private final Gson gson;

    public RouteTargetedTaskDispatchBatchCodec() {
        this(new GsonBuilder().create());
    }

    RouteTargetedTaskDispatchBatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String encode(RouteTargetedTaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return gson.toJson(RouteTargetedTaskDispatchBatchRecord.from(batch));
    }

    public RouteTargetedTaskDispatchBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        RouteTargetedTaskDispatchBatchRecord record = gson.fromJson(json, RouteTargetedTaskDispatchBatchRecord.class);
        if (record == null || record.routeKey == null
                || record.targetTransportNodeId == null
                || record.task == null
                || record.deliveryBindings == null) {
            throw new IllegalArgumentException("encoded route-targeted dispatch batch is incomplete");
        }
        return new RouteTargetedTaskDispatchBatch(
                record.task.toContext(),
                record.routeKey,
                record.targetTransportNodeId,
                record.deliveryBindings.stream()
                        .map(delivery -> delivery.toBinding(record.routeKey))
                        .toList()
        );
    }

    private record RouteTargetedTaskDispatchBatchRecord(String routeKey,
                                                       String targetTransportNodeId,
                                                       TaskDispatchBatchCodec.TaskDispatchContextRecord task,
                                                       List<RouteTargetedTaskDispatchBindingRecord> deliveryBindings) {

        private static RouteTargetedTaskDispatchBatchRecord from(RouteTargetedTaskDispatchBatch batch) {
            return new RouteTargetedTaskDispatchBatchRecord(
                    batch.routeKey(),
                    batch.targetTransportNodeId(),
                    TaskDispatchBatchCodec.TaskDispatchContextRecord.from(batch.task()),
                    batch.deliveryBindings().stream()
                            .map(RouteTargetedTaskDispatchBindingRecord::from)
                            .toList()
            );
        }
    }

    private record RouteTargetedTaskDispatchBindingRecord(String routeKey,
                                                         String adapterId,
                                                         String lanePartition,
                                                         String selectedWorkerId,
                                                         TaskDispatchBatchCodec.TaskDispatchBindingRecord dispatchBinding) {

        private static RouteTargetedTaskDispatchBindingRecord from(RouteTargetedTaskDispatchBinding binding) {
            return new RouteTargetedTaskDispatchBindingRecord(
                    binding.routeKey(),
                    binding.adapterId(),
                    binding.lanePartition(),
                    binding.selectedWorkerId(),
                    TaskDispatchBatchCodec.TaskDispatchBindingRecord.from(binding.dispatchBinding())
            );
        }

        private RouteTargetedTaskDispatchBinding toBinding(String batchRouteKey) {
            String effectiveRouteKey = routeKey == null || routeKey.isBlank() ? batchRouteKey : routeKey;
            return new RouteTargetedTaskDispatchBinding(
                    effectiveRouteKey,
                    new AdapterDispatchLane(adapterId, lanePartition),
                    selectedWorkerId,
                    dispatchBinding.toBinding()
            );
        }
    }
}
