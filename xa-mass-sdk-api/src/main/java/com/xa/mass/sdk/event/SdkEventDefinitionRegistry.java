package com.xa.mass.sdk.event;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local registry for SDK event definitions.
 */
public final class SdkEventDefinitionRegistry {

    private final Map<String, SdkEventDefinition> definitions = new LinkedHashMap<>();

    public synchronized void register(SdkEventDefinition definition) {
        SdkEventDefinition normalized = Objects.requireNonNull(definition, "definition");
        definitions.put(normalized.getEventCode(), normalized);
    }

    public synchronized boolean contains(String eventCode) {
        return definitions.containsKey(eventCode);
    }

    public synchronized SdkEventDefinition get(String eventCode) {
        return definitions.get(eventCode);
    }

    public synchronized List<SdkEventDefinition> list() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(SdkEventDefinition::getEventCode, String::compareToIgnoreCase))
                .toList();
    }

    public synchronized List<SdkEventDefinition> listForProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return List.of();
        }
        String normalizedProjectCode = projectCode.trim();
        return list().stream()
                .filter(definition -> definition.getProjectCodes().contains(normalizedProjectCode))
                .toList();
    }
}
