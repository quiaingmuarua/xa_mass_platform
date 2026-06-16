package com.xa.mass.transport.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;

import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for opaque transport result ingress envelopes crossing a process
 * boundary.
 */
final class TransportResultIngressEnvelopeCodec {

    private final Gson gson;

    TransportResultIngressEnvelopeCodec() {
        this(new GsonBuilder().create());
    }

    TransportResultIngressEnvelopeCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(TransportResultIngressEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return gson.toJson(TransportResultIngressEnvelopeRecord.from(envelope));
    }

    TransportResultIngressEnvelope decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        TransportResultIngressEnvelopeRecord record =
                gson.fromJson(json, TransportResultIngressEnvelopeRecord.class);
        if (record == null || record.payload == null || record.payload.isBlank()) {
            throw new IllegalArgumentException("encoded result ingress envelope is incomplete");
        }
        return new TransportResultIngressEnvelope(
                record.ingressId,
                record.payload,
                record.correlation,
                record.partitionKey,
                record.diagnostics,
                record.receivedAtEpochMillis
        );
    }

    private record TransportResultIngressEnvelopeRecord(String ingressId,
                                                        String payload,
                                                        String correlation,
                                                        String partitionKey,
                                                        Map<String, String> diagnostics,
                                                        long receivedAtEpochMillis) {
        private static TransportResultIngressEnvelopeRecord from(TransportResultIngressEnvelope envelope) {
            return new TransportResultIngressEnvelopeRecord(
                    envelope.getIngressId(),
                    envelope.getPayload(),
                    envelope.getCorrelation(),
                    envelope.getPartitionKey(),
                    envelope.getDiagnostics(),
                    envelope.getReceivedAtEpochMillis()
            );
        }
    }
}
