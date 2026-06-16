package com.xa.mass.api.internal;

import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.transport.WorkerTransportHints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class WorkerCapabilityViewSupport {

    private static final Map<String, String> WORKER_FIELD_SOURCES = Map.ofEntries(
            Map.entry("workerId", "declaration"),
            Map.entry("workerGroupId", "declaration"),
            Map.entry("agentVersion", "declaration"),
            Map.entry("maxConcurrentWork", "declaration"),
            Map.entry("attributes", "declaration"),
            Map.entry("transportHint", "declaration"),
            Map.entry("runtimeStatus", "runtimeStatusDisplay"),
            Map.entry("locked", "runtime"),
            Map.entry("reachability", "workerRuntimeReachability"),
            Map.entry("reachable", "workerRuntimeReachability"),
            Map.entry("supportedProjects", "workerGroupCapability"),
            Map.entry("supportedEventCodes", "workerGroupCapability"),
            Map.entry("eventBindings", "workerGroupCapability")
    );
    private static final Map<String, String> CATALOG_WORKER_FIELD_SOURCES = Map.ofEntries(
            Map.entry("workerId", "declaration"),
            Map.entry("workerGroupId", "declaration"),
            Map.entry("agentVersion", "declaration"),
            Map.entry("maxConcurrentWork", "declaration"),
            Map.entry("attributes", "declaration"),
            Map.entry("transportHint", "declaration"),
            Map.entry("runtimeStatus", "runtimeStatusDisplay"),
            Map.entry("locked", "runtime"),
            Map.entry("reachability", "workerRuntimeReachability"),
            Map.entry("reachable", "workerRuntimeReachability"),
            Map.entry("supportedProjects", "workerGroupCapability"),
            Map.entry("supportedEventCodes", "workerGroupCapability"),
            Map.entry("eventBindings", "workerGroupCapability")
    );

    private WorkerCapabilityViewSupport() {
    }

    static List<Map<String, Object>> deriveEventBindings(List<String> supportedEventCodes,
                                                         ControlPlaneCatalog catalog) {
        return deriveEventBindings(null, supportedEventCodes, catalog);
    }

    static List<Map<String, Object>> deriveEventBindings(List<WorkerEventBinding> workerEventBindings,
                                                         List<String> supportedEventCodes,
                                                         ControlPlaneCatalog catalog) {
        if (workerEventBindings != null && !workerEventBindings.isEmpty()) {
            List<Map<String, Object>> bindings = new ArrayList<>(workerEventBindings.size());
            for (WorkerEventBinding binding : workerEventBindings) {
                if (binding == null || binding.getEventCode() == null || binding.getEventCode().isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventCode", binding.getEventCode());
                item.put("projectCodes", normalizeStringList(binding.getProjectCodes()));
                bindings.add(item);
            }
            return bindings.isEmpty() ? List.of() : List.copyOf(bindings);
        }

        supportedEventCodes = normalizeStringList(supportedEventCodes);
        if (supportedEventCodes.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> bindings = new ArrayList<>(supportedEventCodes.size());
        for (String eventCode : supportedEventCodes) {
            EventDefinition definition = catalog == null ? null : catalog.getEvent(eventCode);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventCode", eventCode);
            item.put("projectCodes", resolveBindingProjects(definition));
            bindings.add(item);
        }
        return List.copyOf(bindings);
    }

    static Map<String, String> workerFieldSources() {
        return WORKER_FIELD_SOURCES;
    }

    static Map<String, String> catalogWorkerFieldSources() {
        return CATALOG_WORKER_FIELD_SOURCES;
    }

    static String resolveTransportHint(String transportHint) {
        return WorkerTransportHints.normalize(transportHint);
    }

    private static List<String> resolveBindingProjects(EventDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        List<String> definitionProjects = normalizeStringList(definition.getProjectCodes());
        return definitionProjects.isEmpty() ? List.of() : definitionProjects;
    }

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = readTrimmed(value);
            if (text != null) {
                normalized.add(text);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static String readTrimmed(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
