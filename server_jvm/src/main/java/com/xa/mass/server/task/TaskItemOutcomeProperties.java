package com.xa.mass.server.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Application meanings for generic Kernel terminal tags. */
@ConfigurationProperties("xa.mass.task-item-outcomes")
public record TaskItemOutcomeProperties(Map<Integer, String> names) {

    public static final int FAILED_TAG = 5;
    public static final int SUCCEEDED_TAG = 6;

    public TaskItemOutcomeProperties {
        Map<Integer, String> configured = new LinkedHashMap<>();
        configured.put(SUCCEEDED_TAG, "succeeded");
        if (names != null) {
            names.forEach((tag, name) -> {
                if (tag == null || tag < 6 || tag > 9
                        || name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            "Outcome names require tags 6..9 and non-blank names"
                    );
                }
                configured.put(tag, name);
            });
        }
        var unique = new HashSet<String>();
        unique.add("failed");
        for (String name : configured.values()) {
            if (!unique.add(name)) {
                throw new IllegalArgumentException("Outcome names must be unique");
            }
        }
        names = Map.copyOf(configured);
    }

    public String outcomeName(int tag) {
        return tag == FAILED_TAG ? "failed" : names.get(tag);
    }
}
