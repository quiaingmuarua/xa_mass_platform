package com.xa.mass.server.workerdelivery.application;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.ADAPTER_WORKER_AVAILABILITY_CHANGED_EVENT_NAME;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CHANGE_RESULT_FORWARD;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerdelivery.workerchange.WorkerChangeInbox;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates Adapter-produced Worker route evidence and hands it to the
 * bounded Worker-change inbox.
 */
public final class WorkerChangeReportIngress {

    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "workerId",
            "available"
    );

    private final WorkerChangeInbox inbox;
    private final WorkerBindingService bindings;

    public WorkerChangeReportIngress(
            WorkerChangeInbox inbox,
            WorkerBindingService bindings
    ) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public AppendCounts append(
            String endpointManagerId,
            List<DeliveryReport> reports
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        Objects.requireNonNull(reports, "reports");
        if (reports.isEmpty()) {
            return new AppendCounts(0, 0);
        }

        List<Candidate> candidates = new ArrayList<>(reports.size());
        int rejectedCount = 0;
        for (DeliveryReport report : reports) {
            Candidate candidate = parseCandidate(endpointManagerId, report);
            if (candidate == null) {
                rejectedCount++;
            } else {
                candidates.add(candidate);
            }
        }

        List<DeliveryReport> validated = new ArrayList<>(candidates.size());
        for (int start = 0; start < candidates.size();
                start += WorkerChangeInbox.MAX_APPEND_BATCH_SIZE) {
            int end = Math.min(
                    start + WorkerChangeInbox.MAX_APPEND_BATCH_SIZE,
                    candidates.size()
            );
            ValidationResult validation = validateBindings(
                    endpointManagerId,
                    candidates.subList(start, end)
            );
            validated.addAll(validation.accepted());
            rejectedCount += validation.rejectedCount();
        }

        int acceptedCount = appendValidated(validated);
        rejectedCount += validated.size() - acceptedCount;
        return new AppendCounts(acceptedCount, rejectedCount);
    }

    private ValidationResult validateBindings(
            String endpointManagerId,
            List<Candidate> candidates
    ) {
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            workerIds.add(candidate.workerId());
        }

        Map<String, String> endpointByWorker;
        try {
            endpointByWorker = bindings.currentEndpointManagerIds(
                    List.copyOf(workerIds)
            );
        } catch (RuntimeException error) {
            throw unavailable(error);
        }

        List<DeliveryReport> accepted = new ArrayList<>(candidates.size());
        int rejected = 0;
        for (Candidate candidate : candidates) {
            if (!endpointManagerId.equals(
                    endpointByWorker.get(candidate.workerId())
            )) {
                rejected++;
            } else {
                accepted.add(candidate.report());
            }
        }
        return new ValidationResult(List.copyOf(accepted), rejected);
    }

    private int appendValidated(List<DeliveryReport> reports) {
        int acceptedTotal = 0;
        for (int start = 0; start < reports.size();
                start += WorkerChangeInbox.MAX_APPEND_BATCH_SIZE) {
            int end = Math.min(
                    start + WorkerChangeInbox.MAX_APPEND_BATCH_SIZE,
                    reports.size()
            );
            List<DeliveryReport> batch = List.copyOf(
                    reports.subList(start, end)
            );
            int accepted;
            try {
                accepted = inbox.append(batch);
            } catch (RuntimeException error) {
                throw unavailable(error);
            }
            if (accepted < 0 || accepted > batch.size()) {
                throw unavailable(new IllegalStateException(
                        "Worker change inbox returned an invalid count"
                ));
            }
            acceptedTotal += accepted;
            if (accepted != batch.size()) {
                break;
            }
        }
        return acceptedTotal;
    }

    private static Candidate parseCandidate(
            String endpointManagerId,
            DeliveryReport report
    ) {
        if (report == null
                || report.src() != ADAPTER
                || !endpointManagerId.equals(report.sourceId())
                || report.dst() != SYSTEM
                || !ADAPTER_WORKER_AVAILABILITY_CHANGED_EVENT_NAME.equals(
                report.messageType()
        )
                || !"200".equals(report.outcomeCode())
                || !WORKER_CHANGE_RESULT_FORWARD.equals(report.forward())) {
            return null;
        }
        try {
            Map<String, Object> payload = Jsons.parseObject(report.payload());
            if (!payload.keySet().equals(PAYLOAD_FIELDS)) {
                return null;
            }
            Object workerId = payload.get("workerId");
            Object available = payload.get("available");
            if (!(workerId instanceof String worker)
                    || worker.isBlank()
                    || !(available instanceof Boolean)) {
                return null;
            }
            return new Candidate(report, worker);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static ServerException unavailable(Throwable cause) {
        return new ServerException(
                ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE,
                "workerChange.ingress",
                "Worker change evidence could not be accepted",
                cause
        );
    }

    public record AppendCounts(int acceptedCount, int rejectedCount) {
    }

    private record Candidate(
            DeliveryReport report,
            String workerId
    ) {
    }

    private record ValidationResult(
            List<DeliveryReport> accepted,
            int rejectedCount
    ) {
    }
}
