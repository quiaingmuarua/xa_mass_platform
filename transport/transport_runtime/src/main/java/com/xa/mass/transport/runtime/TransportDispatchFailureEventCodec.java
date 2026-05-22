package com.xa.mass.transport.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.transport.runtime.dispatch.TaskDispatchBatchCodec;

import java.util.Objects;

/**
 * JSON codec for retryable dispatch failure events.
 */
final class TransportDispatchFailureEventCodec {

    private final Gson gson;
    private final TaskDispatchBatchCodec batchCodec;

    TransportDispatchFailureEventCodec() {
        this(new GsonBuilder().create(), new TaskDispatchBatchCodec());
    }

    TransportDispatchFailureEventCodec(Gson gson, TaskDispatchBatchCodec batchCodec) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.batchCodec = Objects.requireNonNull(batchCodec, "batchCodec");
    }

    String encode(TransportDispatchFailureEvent event) {
        Objects.requireNonNull(event, "event");
        return gson.toJson(new TransportDispatchFailureEventRecord(
                batchCodec.encode(new TaskDispatchBatch(event.task(), event.dispatchBindings())),
                event.detail()
        ));
    }

    TransportDispatchFailureEvent decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        TransportDispatchFailureEventRecord record = gson.fromJson(json, TransportDispatchFailureEventRecord.class);
        if (record == null || record.batchJson == null) {
            throw new IllegalArgumentException("encoded dispatch failure event is incomplete");
        }
        TaskDispatchBatch batch = batchCodec.decode(record.batchJson);
        return new TransportDispatchFailureEvent(batch.task(), batch.dispatchBindings(), record.detail);
    }

    private record TransportDispatchFailureEventRecord(String batchJson, String detail) {
    }
}
