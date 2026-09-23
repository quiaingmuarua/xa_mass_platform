package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Serial read/project/patch consumer; owns no queue, thread or independent lifecycle. */
final class PlatformPropertiesHandler implements FunctionHandler {
    private record Selection(String group, EventKey event) {}

    private final Map<Selection, WorkerPropertyProjection> projections;
    private final WorkerProperties properties;
    private final WorkerResourceCommandService commands;
    private final BooleanSupplier running;
    private final LongConsumer failures;

    PlatformPropertiesHandler(List<WorkerPropertyProjection> projections, WorkerProperties properties,
                              WorkerResourceCommandService commands, BooleanSupplier running,
                              LongConsumer failures) {
        var selected = new LinkedHashMap<Selection, WorkerPropertyProjection>();
        for (var projection : projections) {
            var key = new Selection(projection.workerGroupId(),
                    new EventKey(projection.messageEventName(), projection.observationEventName()));
            if (selected.putIfAbsent(key, projection) != null) {
                throw new IllegalArgumentException("operation=workerObservation.assemble duplicate projection selection");
            }
        }
        this.projections = Collections.unmodifiableMap(selected);
        this.properties = properties;
        this.commands = commands;
        this.running = running;
        this.failures = failures;
    }

    @Override public void handle(List<WorkerObservation> observations) {
        var byGroup = new LinkedHashMap<String, Map<String, Map<WorkerPropertyProjection, List<Long>>>>();
        for (var observation : observations) {
            var projection = projections.get(new Selection(observation.workerGroupId(),
                    new EventKey(observation.messageEventName(), observation.observationEventName())));
            var workers = byGroup.computeIfAbsent(observation.workerGroupId(), ignored -> new LinkedHashMap<>());
            for (String worker : observation.workerIds()) {
                workers.computeIfAbsent(worker, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(projection, ignored -> new ArrayList<>()).add(observation.observedAtMillis());
            }
        }
        for (var group : byGroup.entrySet()) {
            var ids = List.copyOf(group.getValue().keySet());
            for (int start = 0; start < ids.size() && running.getAsBoolean(); start += WorkerProperties.MAX_BATCH_SIZE) {
                var page = ids.subList(start, Math.min(start + WorkerProperties.MAX_BATCH_SIZE, ids.size()));
                Map<String, WorkerFacts> facts;
                try { facts = properties.loadWorkerFacts(group.getKey(), page); }
                catch (RuntimeException ignored) { failures.accept(page.size()); continue; }
                for (String worker : page) {
                    if (!running.getAsBoolean()) return;
                    var fact = facts.get(worker);
                    if (fact == null) continue;
                    try {
                        var current = new LinkedHashMap<>(fact.platformProperties());
                        var patch = new LinkedHashMap<String, Object>();
                        for (var projection : group.getValue().get(worker).entrySet()) {
                            var update = projection.getKey().project().apply(
                                    Collections.unmodifiableMap(new LinkedHashMap<>(current)),
                                    List.copyOf(projection.getValue()));
                            update.forEach((key, value) -> {
                                patch.put(key, value);
                                if (value == null) current.remove(key); else current.put(key, value);
                            });
                        }
                        if (running.getAsBoolean() && !patch.isEmpty()) {
                            commands.patchPlatformProperties(group.getKey(), worker, patch);
                        }
                    } catch (RuntimeException ignored) { failures.accept(1); }
                }
            }
        }
    }
}
