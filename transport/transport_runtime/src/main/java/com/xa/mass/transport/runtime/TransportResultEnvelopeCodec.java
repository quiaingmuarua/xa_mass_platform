package com.xa.mass.transport.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for transport result envelopes crossing a process boundary.
 */
final class TransportResultEnvelopeCodec {

    private final Gson gson;

    TransportResultEnvelopeCodec() {
        this(new GsonBuilder().create());
    }

    TransportResultEnvelopeCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(TransportResultEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return gson.toJson(TransportResultEnvelopeRecord.from(envelope));
    }

    TransportResultEnvelope decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        TransportResultEnvelopeRecord record = gson.fromJson(json, TransportResultEnvelopeRecord.class);
        if (record == null || record.report == null) {
            throw new IllegalArgumentException("encoded result envelope is incomplete");
        }
        return new TransportResultEnvelope(
                record.adapterId,
                record.routeKey,
                record.attemptId,
                record.leaseToken,
                record.traceId,
                record.report.toReport()
        );
    }

    private record TransportResultEnvelopeRecord(String adapterId,
                                                 String routeKey,
                                                 String attemptId,
                                                 String leaseToken,
                                                 String traceId,
                                                 TaskResultReportRecord report) {

        private static TransportResultEnvelopeRecord from(TransportResultEnvelope envelope) {
            return new TransportResultEnvelopeRecord(
                    envelope.getAdapterId(),
                    envelope.getRouteKey(),
                    envelope.getAttemptId(),
                    envelope.getLeaseToken(),
                    envelope.getTraceId(),
                    TaskResultReportRecord.from(envelope.getReport())
            );
        }
    }

    private record TaskResultReportRecord(String taskId,
                                          String messageId,
                                          boolean success,
                                          String detail,
                                          String errorCode,
                                          Map<String, Object> output) {

        private static TaskResultReportRecord from(TaskResultReport report) {
            return new TaskResultReportRecord(
                    report.getTaskId(),
                    report.getMessageId(),
                    report.isSuccess(),
                    report.getDetail(),
                    report.getErrorCode(),
                    report.getOutput()
            );
        }

        private TaskResultReport toReport() {
            return new TaskResultReport(
                    taskId,
                    messageId,
                    success,
                    detail,
                    errorCode,
                    TransportJsonValueNormalizer.freezeDecodedObject(output)
            );
        }
    }
}
