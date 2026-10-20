package com.xa.mass.workersimulator;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class WorkerSimulatorStartupPlan {

    static final long MAX_STOP_DELAY_MILLIS = 86_400_000L;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "initialWorkers",
            "scheduledStops"
    );
    private static final Set<String> WORKER_FIELDS = Set.of(
            "workerGroupId",
            "labWorkerKey"
    );
    private static final Set<String> STOP_FIELDS = Set.of(
            "workerGroupId",
            "labWorkerKey",
            "delayMillis"
    );

    private final boolean startAll;
    private final List<WorkerSimulatorCoordinate> initialWorkers;
    private final List<ScheduledStop> scheduledStops;
    private final Map<String, Integer> workerCounts;

    private WorkerSimulatorStartupPlan(
            boolean startAll,
            List<WorkerSimulatorCoordinate> initialWorkers,
            List<ScheduledStop> scheduledStops
    ) {
        this(startAll, initialWorkers, scheduledStops, Map.of());
    }

    private WorkerSimulatorStartupPlan(boolean startAll, List<WorkerSimulatorCoordinate> initialWorkers,
            List<ScheduledStop> scheduledStops, Map<String, Integer> workerCounts) {
        this.startAll = startAll;
        this.initialWorkers = List.copyOf(initialWorkers);
        this.scheduledStops = List.copyOf(scheduledStops);
        this.workerCounts = Map.copyOf(workerCounts);
    }

    static WorkerSimulatorStartupPlan defaults() {
        return new WorkerSimulatorStartupPlan(true, List.of(), List.of());
    }

    static WorkerSimulatorStartupPlan parse(String encoded) {
        return parse(Jsons.parseObject(encoded));
    }

    static WorkerSimulatorStartupPlan parse(Map<String, Object> root) {
        if (root.containsKey("workerCounts")) {
            requireFields(root, Set.of("workerCounts"), "quantity startup plan");
            Map<String, Object> values = requiredObject(root.get("workerCounts"), "workerCounts");
            if (values.isEmpty()) throw new IllegalArgumentException("workerCounts must not be empty");
            Map<String, Integer> counts = new LinkedHashMap<>();
            values.forEach((group, value) -> {
                if (group.isBlank() || !(value instanceof Long count)
                        || count < 0 || count > WorkerSimulatorLab.MAX_WORKERS_PER_GROUP) {
                    throw new IllegalArgumentException("workerCounts requires Group names and integers in 0..15000");
                }
                counts.put(group, ((Long) value).intValue());
            });
            return new WorkerSimulatorStartupPlan(false, List.of(), List.of(), counts);
        }
        requireFields(root, ROOT_FIELDS, "startup plan");

        List<WorkerSimulatorCoordinate> initial = parseWorkers(
                requiredArray(root, "initialWorkers")
        );
        Set<WorkerSimulatorCoordinate> initialSet = Set.copyOf(initial);
        List<ScheduledStop> stops = parseStops(
                requiredArray(root, "scheduledStops"),
                initialSet
        );
        return new WorkerSimulatorStartupPlan(false, initial, stops);
    }

    boolean startAll() {
        return startAll;
    }

    Map<String, Integer> workerCounts() {
        return workerCounts;
    }

    List<WorkerSimulatorCoordinate> initialWorkers() {
        return initialWorkers;
    }

    List<ScheduledStop> scheduledStops() {
        return scheduledStops;
    }

    private static List<WorkerSimulatorCoordinate> parseWorkers(
            List<Object> values
    ) {
        List<WorkerSimulatorCoordinate> workers = new ArrayList<>();
        Set<WorkerSimulatorCoordinate> unique = new LinkedHashSet<>();
        for (Object value : values) {
            Map<String, Object> worker = requiredObject(
                    value,
                    "initial worker"
            );
            requireFields(worker, WORKER_FIELDS, "initial worker");
            WorkerSimulatorCoordinate coordinate = coordinate(worker);
            if (!unique.add(coordinate)) {
                throw new IllegalArgumentException(
                        "startup plan contains duplicate initial Worker "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.labWorkerKey()
                );
            }
            workers.add(coordinate);
        }
        return List.copyOf(workers);
    }

    private static List<ScheduledStop> parseStops(
            List<Object> values,
            Set<WorkerSimulatorCoordinate> initialWorkers
    ) {
        List<ScheduledStop> stops = new ArrayList<>();
        Set<WorkerSimulatorCoordinate> unique = new LinkedHashSet<>();
        for (Object value : values) {
            Map<String, Object> stop = requiredObject(
                    value,
                    "scheduled stop"
            );
            requireFields(stop, STOP_FIELDS, "scheduled stop");
            WorkerSimulatorCoordinate coordinate = coordinate(stop);
            if (!initialWorkers.contains(coordinate)) {
                throw new IllegalArgumentException(
                        "scheduled stop must reference an initial Worker: "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.labWorkerKey()
                );
            }
            if (!unique.add(coordinate)) {
                throw new IllegalArgumentException(
                        "startup plan contains duplicate scheduled stop for "
                                + coordinate.workerGroupId()
                                + "/"
                                + coordinate.labWorkerKey()
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

    private static WorkerSimulatorCoordinate coordinate(
            Map<String, Object> value
    ) {
        return new WorkerSimulatorCoordinate(
                requiredString(value, "workerGroupId"),
                requiredString(value, "labWorkerKey")
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
            WorkerSimulatorCoordinate worker,
            long delayMillis
    ) {

        ScheduledStop {
            Objects.requireNonNull(worker, "worker");
        }
    }
}
