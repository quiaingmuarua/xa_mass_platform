package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ScenarioWorkerStartupPlan {

    static final long MAX_STOP_DELAY_MILLIS = 86_400_000L;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion",
            "initialWorkers",
            "scheduledStops"
    );
    private static final Set<String> WORKER_FIELDS = Set.of(
            "workerGroupId",
            "clientWorkerKey"
    );
    private static final Set<String> STOP_FIELDS = Set.of(
            "workerGroupId",
            "clientWorkerKey",
            "delayMillis"
    );

    private final boolean startAll;
    private final List<ScenarioWorkerCoordinate> initialWorkers;
    private final List<ScheduledStop> scheduledStops;

    private ScenarioWorkerStartupPlan(
            boolean startAll,
            List<ScenarioWorkerCoordinate> initialWorkers,
            List<ScheduledStop> scheduledStops
    ) {
        this.startAll = startAll;
        this.initialWorkers = List.copyOf(initialWorkers);
        this.scheduledStops = List.copyOf(scheduledStops);
    }

    static ScenarioWorkerStartupPlan defaults() {
        return new ScenarioWorkerStartupPlan(true, List.of(), List.of());
    }

    static ScenarioWorkerStartupPlan load(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException(
                    "startup-plan must be non-blank"
            );
        }
        Path path;
        try {
            path = Path.of(configuredPath).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException(
                    "startup-plan must be a valid path",
                    error
            );
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Could not read startup-plan " + path,
                    error
            );
        }
    }

    static ScenarioWorkerStartupPlan parse(String encoded) {
        Map<String, Object> root = Jsons.parseObject(encoded);
        requireFields(root, ROOT_FIELDS, "startup plan");
        if (!(root.get("schemaVersion") instanceof Long version)
                || version != 1L) {
            throw new IllegalArgumentException(
                    "startup plan schemaVersion must be integer 1"
            );
        }

        List<ScenarioWorkerCoordinate> initial = parseWorkers(
                requiredArray(root, "initialWorkers")
        );
        Set<ScenarioWorkerCoordinate> initialSet = Set.copyOf(initial);
        List<ScheduledStop> stops = parseStops(
                requiredArray(root, "scheduledStops"),
                initialSet
        );
        return new ScenarioWorkerStartupPlan(false, initial, stops);
    }

    boolean startAll() {
        return startAll;
    }

    List<ScenarioWorkerCoordinate> initialWorkers() {
        return initialWorkers;
    }

    List<ScheduledStop> scheduledStops() {
        return scheduledStops;
    }

    private static List<ScenarioWorkerCoordinate> parseWorkers(
            List<Object> values
    ) {
        List<ScenarioWorkerCoordinate> workers = new ArrayList<>();
        Set<ScenarioWorkerCoordinate> unique = new LinkedHashSet<>();
        for (Object value : values) {
            Map<String, Object> worker = requiredObject(
                    value,
                    "initial worker"
            );
            requireFields(worker, WORKER_FIELDS, "initial worker");
            ScenarioWorkerCoordinate coordinate = coordinate(worker);
            if (!unique.add(coordinate)) {
                throw new IllegalArgumentException(
                        "startup plan contains duplicate initial Worker "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.clientWorkerKey()
                );
            }
            workers.add(coordinate);
        }
        return List.copyOf(workers);
    }

    private static List<ScheduledStop> parseStops(
            List<Object> values,
            Set<ScenarioWorkerCoordinate> initialWorkers
    ) {
        List<ScheduledStop> stops = new ArrayList<>();
        Set<ScenarioWorkerCoordinate> unique = new LinkedHashSet<>();
        for (Object value : values) {
            Map<String, Object> stop = requiredObject(
                    value,
                    "scheduled stop"
            );
            requireFields(stop, STOP_FIELDS, "scheduled stop");
            ScenarioWorkerCoordinate coordinate = coordinate(stop);
            if (!initialWorkers.contains(coordinate)) {
                throw new IllegalArgumentException(
                        "scheduled stop must reference an initial Worker: "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.clientWorkerKey()
                );
            }
            if (!unique.add(coordinate)) {
                throw new IllegalArgumentException(
                        "startup plan contains duplicate scheduled stop for "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.clientWorkerKey()
                );
            }
            Object rawDelay = stop.get("delayMillis");
            if (!(rawDelay instanceof Long delayMillis)
                    || delayMillis < 1L
                    || delayMillis > MAX_STOP_DELAY_MILLIS) {
                throw new IllegalArgumentException(
                        "scheduled stop delayMillis must be an integer in "
                                + "1.."
                                + MAX_STOP_DELAY_MILLIS
                );
            }
            stops.add(new ScheduledStop(coordinate, delayMillis));
        }
        return List.copyOf(stops);
    }

    private static ScenarioWorkerCoordinate coordinate(
            Map<String, Object> value
    ) {
        return new ScenarioWorkerCoordinate(
                requiredString(value, "workerGroupId"),
                requiredString(value, "clientWorkerKey")
        );
    }

    private static String requiredString(
            Map<String, Object> value,
            String field
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must be a non-blank string"
            );
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredArray(
            Map<String, Object> value,
            String field
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof List<?>)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (List<Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredObject(
            Object value,
            String owner
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(owner + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static void requireFields(
            Map<String, Object> value,
            Set<String> expected,
            String owner
    ) {
        Objects.requireNonNull(value, "value");
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    owner + " must contain exactly " + expected
            );
        }
    }

    record ScheduledStop(
            ScenarioWorkerCoordinate worker,
            long delayMillis
    ) {

        ScheduledStop {
            Objects.requireNonNull(worker, "worker");
        }
    }
}
