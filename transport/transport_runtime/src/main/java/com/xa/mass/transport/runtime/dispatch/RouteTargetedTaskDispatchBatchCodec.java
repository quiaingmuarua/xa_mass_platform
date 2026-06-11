package com.xa.mass.transport.runtime.dispatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.List;
import java.util.Objects;

/**
 * JSON codec for route-targeted dispatch handoff payloads.
 */
public final class RouteTargetedTaskDispatchBatchCodec {

    private final Gson gson;
    private final TaskDispatchBatchCodec batchCodec;

    public RouteTargetedTaskDispatchBatchCodec() {
        this(new GsonBuilder().create(), new TaskDispatchBatchCodec());
    }

    RouteTargetedTaskDispatchBatchCodec(Gson gson, TaskDispatchBatchCodec batchCodec) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.batchCodec = Objects.requireNonNull(batchCodec, "batchCodec");
    }

    public String encode(RouteTargetedTaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return gson.toJson(RouteTargetedTaskDispatchBatchRecord.from(batch, batchCodec));
    }

    public RouteTargetedTaskDispatchBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        RouteTargetedTaskDispatchBatchRecord record = gson.fromJson(json, RouteTargetedTaskDispatchBatchRecord.class);
        if (record == null || record.routeKey == null
                || record.targetTransportNodeId == null
                || record.taskBatchJson == null) {
            throw new IllegalArgumentException("encoded route-targeted dispatch batch is incomplete");
        }
        TaskDispatchBatch taskBatch = batchCodec.decode(record.taskBatchJson);
        List<String> adapterIds = record.adapterIds == null ? List.of() : record.adapterIds;
        List<RouteTargetedTaskDispatchBinding> deliveries = new java.util.ArrayList<>(taskBatch.dispatchBindings().size());
        for (int index = 0; index < taskBatch.dispatchBindings().size(); index++) {
            TaskDispatchBinding binding = taskBatch.dispatchBindings().get(index);
            deliveries.add(new RouteTargetedTaskDispatchBinding(
                    record.routeKey,
                    adapterIdAt(adapterIds, index, binding),
                    binding
            ));
        }
        return new RouteTargetedTaskDispatchBatch(
                taskBatch.task(),
                record.routeKey,
                record.targetTransportNodeId,
                deliveries
        );
    }

    private static String adapterIdAt(List<String> adapterIds, int index, TaskDispatchBinding binding) {
        if (index >= 0 && index < adapterIds.size()) {
            String adapterId = adapterIds.get(index);
            if (adapterId != null && !adapterId.isBlank()) {
                return adapterId;
            }
        }
        return binding.adapterId();
    }

    private record RouteTargetedTaskDispatchBatchRecord(String routeKey,
                                                       List<String> adapterIds,
                                                       String targetTransportNodeId,
                                                       String taskBatchJson) {

        private static RouteTargetedTaskDispatchBatchRecord from(RouteTargetedTaskDispatchBatch batch,
                                                                 TaskDispatchBatchCodec batchCodec) {
            return new RouteTargetedTaskDispatchBatchRecord(
                    batch.routeKey(),
                    batch.deliveryBindings().stream()
                            .map(RouteTargetedTaskDispatchBinding::adapterId)
                            .toList(),
                    batch.targetTransportNodeId(),
                    batchCodec.encode(new TaskDispatchBatch(
                            batch.task(),
                            batch.deliveryBindings().stream()
                                    .map(RouteTargetedTaskDispatchBinding::dispatchBinding)
                                    .toList()
                    ))
            );
        }
    }
}
