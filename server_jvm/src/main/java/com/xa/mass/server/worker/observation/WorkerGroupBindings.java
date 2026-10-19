package com.xa.mass.server.worker.observation;

import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;

/** One immutable host Group expansion. Project admission does not enable resources. */
public final class WorkerGroupBindings {
    public record Feedback(String observationEventName, List<String> messageEvents) {
        public Feedback { messageEvents = List.copyOf(messageEvents); }
    }
    public record WindowPool(String poolName, String functionName, String timestampProperty,
                             String countProperty, long windowMillis, long maxCount, Feedback feedback) {}
    public record Group(List<WindowPool> fixedWindowPools) {
        public Group { fixedWindowPools = List.copyOf(fixedWindowPools); }
    }
    private final Map<String, MatchingGroup> matchingGroups;
    private final List<FixedWindowPoolDefinition> definitions;
    private final List<WorkerPropertyProjection> projections;
    private final Map<String, Group> groups;
    private final Map<String, Set<String>> requiredEvents;

    public WorkerGroupBindings(Map<String, MatchingGroup> original, FixedWindowBindingsProperties config,
                               List<FixedWindowPoolDefinition> externalDefinitions) {
        var enabled = new TreeMap<>(original);
        var limits = new TreeMap<String, Map<String, WindowLimit>>();
        var functionNames = new HashSet<String>();
        config.pools().forEach((name, pool) -> {
            if (!functionNames.add(pool.functionName())) throw invalid("pools." + name, "duplicate function " + pool.functionName());
            limits.put(name, new TreeMap<>());
        });
        original.forEach((group, value) -> {
            var pools = new TreeSet<>(value.pools()); pools.retainAll(config.pools().keySet());
            var functions = new TreeSet<>(value.functions()); functions.retainAll(functionNames);
            if (!pools.isEmpty() || !functions.isEmpty())
                throw invalid("groups." + group, "duplicate raw Matching enablement: Pools " + pools + ", functions " + functions);
        });
        var views = new TreeMap<String, Group>();
        var selections = new LinkedHashMap<String, List<WorkerPropertyProjection.Projection>>();
        var required = new TreeMap<String, Set<String>>();
        config.groups().forEach((group, binding) -> {
            String path = "groups." + group;
            var previous = enabled.getOrDefault(group, new MatchingGroup(Set.of(), Set.of()));
            var pools = new TreeSet<>(previous.pools()); var functions = new TreeSet<>(previous.functions());
            var poolViews = new ArrayList<WindowPool>();
            var groupSelections = new ArrayList<WorkerPropertyProjection.Projection>();
            var events = new TreeSet<String>(); var fields = new HashSet<String>();
            binding.pools().forEach((name, overrides) -> {
                var pool = config.pools().get(name);
                if (pool == null) throw invalid(path + ".pools." + name, "unknown Pool " + name);
                var limit = new WindowLimit(overrides.windowMillis() == null ? pool.windowMillis() : overrides.windowMillis(),
                        overrides.maxCount() == null ? pool.maxCount() : overrides.maxCount());
                if (!fields.add(pool.timestampProperty()) || !fields.add(pool.countProperty()))
                    throw invalid(path + ".pools." + name, "overlapping feedback Platform Properties fields");
                var projection = new AssignmentWindowProjection(limit.windowMillis(), pool.timestampProperty(), pool.countProperty());
                for (String event : pool.feedback().messageEvents()) {
                    if (!events.add(event)) throw invalid(path + ".pools." + name, "duplicate worker.assigned selection: " + event);
                    groupSelections.add(new WorkerPropertyProjection.Projection(event, projection::project));
                }
                limits.get(name).put(group, limit);
                pools.add(name); functions.add(pool.functionName());
                poolViews.add(new WindowPool(name, pool.functionName(), pool.timestampProperty(), pool.countProperty(),
                        limit.windowMillis(), limit.maxCount(), new Feedback("worker.assigned", pool.feedback().messageEvents())));
            });
            enabled.put(group, new MatchingGroup(pools, functions));
            if (!groupSelections.isEmpty()) selections.put(group, List.copyOf(groupSelections));
            required.put(group, Collections.unmodifiableSet(events));
            views.put(group, new Group(poolViews));
        });
        var definitions = new ArrayList<>(externalDefinitions);
        config.pools().forEach((name, pool) -> definitions.add(new FixedWindowPoolDefinition(name, pool.functionName(),
                pool.timestampProperty(), pool.countProperty(), limits.get(name))));
        this.matchingGroups = Collections.unmodifiableMap(enabled);
        this.definitions = List.copyOf(definitions);
        this.projections = selections.isEmpty() ? List.of() : List.of(new WorkerPropertyProjection("worker.assigned", selections));
        this.groups = Collections.unmodifiableMap(views);
        this.requiredEvents = Collections.unmodifiableMap(required);
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("operation=workerBindings.assemble xa.mass.worker-assembly.fixed-window." + path + ": " + message);
    }
    public Map<String, MatchingGroup> matchingGroups() { return matchingGroups; }
    public List<FixedWindowPoolDefinition> definitions() { return definitions; }
    public List<WorkerPropertyProjection> projections() { return projections; }
    public Map<String, Set<String>> requiredEvents() { return requiredEvents; }
    public Group group(String group) { return groups.getOrDefault(group, new Group(List.of())); }
}
