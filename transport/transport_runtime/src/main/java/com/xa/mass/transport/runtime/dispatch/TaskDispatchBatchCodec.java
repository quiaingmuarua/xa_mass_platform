package com.xa.mass.transport.runtime.dispatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for dispatch-ready batches crossing a process boundary.
 */
public final class TaskDispatchBatchCodec {

    private final Gson gson;

    public TaskDispatchBatchCodec() {
        this(new GsonBuilder().create());
    }

    TaskDispatchBatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String encode(TaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return gson.toJson(TaskDispatchBatchRecord.from(batch));
    }

    public TaskDispatchBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        TaskDispatchBatchRecord record = gson.fromJson(json, TaskDispatchBatchRecord.class);
        if (record == null || record.task == null || record.dispatchBindings == null) {
            throw new IllegalArgumentException("encoded task dispatch batch is incomplete");
        }
        return new TaskDispatchBatch(record.task.toContext(), record.dispatchBindings.stream()
                .map(TaskDispatchBindingRecord::toBinding)
                .toList());
    }

    private record TaskDispatchBatchRecord(TaskDispatchContextRecord task,
                                           List<TaskDispatchBindingRecord> dispatchBindings) {

        private static TaskDispatchBatchRecord from(TaskDispatchBatch batch) {
            return new TaskDispatchBatchRecord(
                    TaskDispatchContextRecord.from(batch.task()),
                    batch.dispatchBindings().stream()
                            .map(TaskDispatchBindingRecord::from)
                            .toList()
            );
        }
    }

    private record TaskDispatchContextRecord(String taskId,
                                             String taskName,
                                             String project,
                                             String userId,
                                             String eventCode,
                                             Map<String, Object> sharedConfig) {

        private static TaskDispatchContextRecord from(TaskDispatchContext context) {
            return new TaskDispatchContextRecord(
                    context.taskId(),
                    context.taskName(),
                    context.project(),
                    context.userId(),
                    context.eventCode(),
                    context.sharedConfig()
            );
        }

        private TaskDispatchContext toContext() {
            return new TaskDispatchContext(
                    taskId,
                    taskName,
                    project,
                    userId,
                    eventCode,
                    TransportJsonValueNormalizer.freezeDecodedObject(sharedConfig)
            );
        }
    }

    private record TaskDispatchBindingRecord(String taskId,
                                             String messageId,
                                             String eventCode,
                                             Map<String, Object> payload,
                                             String payloadRef,
                                             int retryCount,
                                             String attemptId,
                                             int attemptNo,
                                             String leaseToken,
                                             String workerId,
                                             String batchId) {

        private static TaskDispatchBindingRecord from(TaskDispatchBinding binding) {
            return new TaskDispatchBindingRecord(
                    binding.taskId(),
                    binding.messageId(),
                    binding.eventCode(),
                    binding.payload(),
                    binding.payloadRef(),
                    binding.retryCount(),
                    binding.attemptId(),
                    binding.attemptNo(),
                    binding.leaseToken(),
                    binding.workerId(),
                    binding.batchId()
            );
        }

        private TaskDispatchBinding toBinding() {
            return TaskDispatchBinding.workerLevel(
                    taskId,
                    messageId,
                    eventCode,
                    TransportJsonValueNormalizer.freezeDecodedObject(payload),
                    payloadRef,
                    retryCount,
                    attemptId,
                    attemptNo,
                    leaseToken,
                    workerId,
                    batchId
            );
        }
    }
}
