package com.xa.mass.transport.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.routing.RoutingEnvelope;
import com.xa.mass.transport.routing.RoutingTarget;

import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for routing envelopes crossing the result inbox process boundary.
 */
final class RoutingEnvelopeCodec {

    private final Gson gson;

    RoutingEnvelopeCodec() {
        this(new GsonBuilder().create());
    }

    RoutingEnvelopeCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(RoutingEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return gson.toJson(RoutingEnvelopeRecord.from(envelope));
    }

    RoutingEnvelope decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        RoutingEnvelopeRecord record = gson.fromJson(json, RoutingEnvelopeRecord.class);
        if (record == null || record.payload == null || record.payload.isBlank()) {
            throw new IllegalArgumentException("encoded routing envelope is incomplete");
        }
        if (record.target == null) {
            throw new IllegalArgumentException("encoded routing envelope target is required");
        }
        return new RoutingEnvelope(
                record.envelopeId,
                new RoutingTarget(record.target.ownerKind, record.target.ownerRef),
                record.payload,
                record.diagnostics,
                record.createdAtEpochMillis
        );
    }

    private record RoutingEnvelopeRecord(String envelopeId,
                                         RoutingTargetRecord target,
                                         String payload,
                                         Map<String, String> diagnostics,
                                         long createdAtEpochMillis) {
        private static RoutingEnvelopeRecord from(RoutingEnvelope envelope) {
            return new RoutingEnvelopeRecord(
                    envelope.envelopeId(),
                    RoutingTargetRecord.from(envelope.target()),
                    envelope.payload(),
                    envelope.diagnostics(),
                    envelope.createdAtEpochMillis()
            );
        }
    }

    private record RoutingTargetRecord(String ownerKind,
                                       String ownerRef) {
        private static RoutingTargetRecord from(RoutingTarget target) {
            return new RoutingTargetRecord(target.ownerKind(), target.ownerRef());
        }
    }
}
