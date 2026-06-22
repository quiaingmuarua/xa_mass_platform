package com.xa.mass.transport.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;

import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for result ingress entries crossing the result inbox process boundary.
 */
final class ResultIngressEntryCodec {

    private final Gson gson;

    ResultIngressEntryCodec() {
        this(new GsonBuilder().create());
    }

    ResultIngressEntryCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(ResultIngressEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return gson.toJson(ResultIngressEntryRecord.from(entry));
    }

    ResultIngressEntry decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        ResultIngressEntryRecord record = gson.fromJson(json, ResultIngressEntryRecord.class);
        if (record == null || record.partitionKey == null || record.message == null) {
            throw new IllegalArgumentException("encoded result ingress entry is incomplete");
        }
        ResultIngressMessageRecord message = record.message;
        return new ResultIngressEntry(
                record.partitionKey,
                new ResultIngressMessage(
                        message.resultMessageId,
                        message.resultCorrelationRef,
                        message.payload,
                        message.deadlineEpochMillis,
                        message.createdAtEpochMillis
                ),
                new ResultIngressDiagnostics(record.diagnostics)
        );
    }

    private record ResultIngressEntryRecord(String partitionKey,
                                            ResultIngressMessageRecord message,
                                            Map<String, String> diagnostics) {
        private static ResultIngressEntryRecord from(ResultIngressEntry entry) {
            return new ResultIngressEntryRecord(
                    entry.partitionKey(),
                    ResultIngressMessageRecord.from(entry.message()),
                    entry.diagnostics().values()
            );
        }
    }

    private record ResultIngressMessageRecord(String resultMessageId,
                                              String resultCorrelationRef,
                                              String payload,
                                              long deadlineEpochMillis,
                                              long createdAtEpochMillis) {
        private static ResultIngressMessageRecord from(ResultIngressMessage message) {
            return new ResultIngressMessageRecord(
                    message.resultMessageId(),
                    message.resultCorrelationRef(),
                    message.payload(),
                    message.deadlineEpochMillis(),
                    message.createdAtEpochMillis()
            );
        }
    }
}
