package com.xa.mass.api.internal;

import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.transport.WorkerTransportHints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class WorkerCapabilityViewSupport {

    private WorkerCapabilityViewSupport() {
    }

    static Map<String, List<Map<String, Object>>> groupConnectionsByWorker(RuntimeDiagnosticsOperations runtimeDiagnostics) {
        if (runtimeDiagnostics == null) {
            return Map.of();
        }

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<Map<String, Object>> sessions = runtimeDiagnostics.listSessions();
        if (sessions == null || sessions.isEmpty()) {
            return grouped;
        }

        for (Map<String, Object> session : sessions) {
            if (session == null || session.isEmpty()) {
                continue;
            }
            String workerId = readTrimmed(session.get("workerId"));
            if (workerId == null) {
                continue;
            }
            grouped.put(workerId, normalizeConnections(session.get("connections")));
        }
        return grouped;
    }

    static List<Map<String, Object>> deriveEventBindings(List<String> supportedEventCodes,
                                                         ControlPlaneCatalog catalog) {
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

    static boolean hasActiveConnection(List<Map<String, Object>> connections) {
        if (connections == null || connections.isEmpty()) {
            return false;
        }
        return connections.stream().anyMatch(connection ->
                connection != null && Boolean.TRUE.equals(connection.get("active"))
        );
    }

    static String resolveTransportHint(String onlineStrategy) {
        return WorkerTransportHints.normalize(onlineStrategy);
    }

    static String resolveAdapterId(String workerAdapterId, List<Map<String, Object>> connections) {
        workerAdapterId = readTrimmed(workerAdapterId);
        if (workerAdapterId != null) {
            return workerAdapterId;
        }
        if (connections == null || connections.isEmpty()) {
            return null;
        }
        for (Map<String, Object> connection : connections) {
            String connectionAdapterId = readTrimmed(connection == null ? null : connection.get("adapterId"));
            if (connectionAdapterId != null) {
                return connectionAdapterId.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static List<String> resolveBindingProjects(EventDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        List<String> definitionProjects = normalizeStringList(definition.getProjectCodes());
        return definitionProjects.isEmpty() ? List.of() : definitionProjects;
    }

    private static List<Map<String, Object>> normalizeConnections(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("active", Boolean.TRUE.equals(map.get("active")));
            normalized.put("endpointId", readTrimmed(map.get("endpointId")));
            normalized.put("routeKey", readTrimmed(map.get("routeKey")));
            normalized.put("adapterId", readTrimmed(map.get("adapterId")));
            connections.add(normalized);
        }
        return connections.isEmpty() ? List.of() : List.copyOf(connections);
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
