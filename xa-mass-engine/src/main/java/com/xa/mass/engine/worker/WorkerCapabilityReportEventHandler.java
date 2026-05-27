package com.xa.mass.engine.worker;

import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.runtime.worker.WorkerCapabilityReport;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kernel-targeted event handler for worker capability self-report.
 *
 * <p>The event runtime owns routing only. This handler translates event payload
 * into a typed report and delegates mutation to {@link WorkerControlService}.</p>
 */
public final class WorkerCapabilityReportEventHandler {

    public static final String EVENT_CODE = "kernel.worker.capability.report";

    private final WorkerControlService workerControlService;

    public WorkerCapabilityReportEventHandler(WorkerControlService workerControlService) {
        this.workerControlService = Objects.requireNonNull(workerControlService, "workerControlService");
    }

    public void register(KernelEventHandlerRegistry registry) {
        Objects.requireNonNull(registry, "registry").registerWorkerControlEvent(EVENT_CODE, this::handle);
    }

    public CoreEventResponse handle(CoreEventRequest request, com.xa.mass.command.event.CoreEventPrincipal principal) {
        try {
            WorkerCapabilityReport report = reportFrom(request.getPayload());
            WorkerCapabilityReportResult result = workerControlService.applyWorkerCapabilityReport(report);
            if (result.success()) {
                return CoreEventResponse.success(responsePayload(result), request.getRequestId());
            }
            return CoreEventResponse.failure(result.status().name(), result.reason(), request.getRequestId());
        } catch (IllegalArgumentException e) {
            return CoreEventResponse.failure("INVALID_WORKER_CAPABILITY_REPORT", e.getMessage(),
                    request != null ? request.getRequestId() : null);
        }
    }

    private static WorkerCapabilityReport reportFrom(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String workerId = stringValue(payload.get("workerId"), "workerId");
        long capabilityVersion = longValue(payload.get("capabilityVersion"), "capabilityVersion");
        return WorkerCapabilityReport.builder(workerId, capabilityVersion)
                .availableEventCodes(stringList(payload.get("availableEventCodes"), "availableEventCodes"))
                .schedulingAttributes(stringMap(payload.get("schedulingAttributes"), "schedulingAttributes"))
                .agentVersion(optionalString(payload.get("agentVersion")))
                .build();
    }

    private static Map<String, Object> responsePayload(WorkerCapabilityReportResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        payload.put("workerId", result.workerId());
        payload.put("capabilityVersion", result.capabilityVersion());
        payload.put("snapshotChanged", result.snapshotChanged());
        payload.put("reason", result.reason());
        return payload;
    }

    private static String stringValue(Object value, String fieldName) {
        String text = optionalString(value);
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static long longValue(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = optionalString(value);
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number", e);
        }
    }

    private static List<String> stringList(Object value, String fieldName) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(fieldName + " must be a list");
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            String text = optionalString(item);
            if (text != null) {
                values.add(text);
            }
        }
        return values;
    }

    private static Map<String, String> stringMap(Object value, String fieldName) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(fieldName + " must be an object");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = optionalString(entry.getKey());
            String item = optionalString(entry.getValue());
            if (key != null && item != null) {
                values.put(key, item);
            }
        }
        return values;
    }
}
