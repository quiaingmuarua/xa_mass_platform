package com.xa.mass.sdk.event;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local projection cache for SDK event definitions.
 *
 * <p>The SDK facade reads from this registry, but the canonical event metadata
 * may be derived from a lower-level runtime source such as
 * {@code com.xa.mass.command.event.MassEventRuntime}.
 */
public final class EventDefinitionRegistry {

    private final Map<String, EventDefinition> definitions = new LinkedHashMap<>();

    public synchronized void register(EventDefinition definition) {
        EventDefinition normalized = Objects.requireNonNull(definition, "definition");
        definitions.put(normalized.getEventCode(), normalized);
    }

    public synchronized void replaceAll(Iterable<EventDefinition> definitions) {
        this.definitions.clear();
        if (definitions == null) {
            return;
        }
        for (EventDefinition definition : definitions) {
            if (definition != null) {
                register(definition);
            }
        }
    }

    public synchronized boolean contains(String eventCode) {
        return definitions.containsKey(eventCode);
    }

    public synchronized EventDefinition get(String eventCode) {
        return definitions.get(eventCode);
    }

    public synchronized List<EventDefinition> list() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(EventDefinition::getEventCode, String::compareToIgnoreCase))
                .toList();
    }

    public synchronized List<EventDefinition> listForProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return List.of();
        }
        String normalizedProjectCode = projectCode.trim();
        return list().stream()
                .filter(definition -> definition.getProjectCodes().contains(normalizedProjectCode))
                .toList();
    }
}
