package com.xa.mass.server.worker.observation;

import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;

/** One immutable startup expansion used by Matching, dependency checks, projections and display. */
public final class WorkerGroupBindings {
    public record WindowPool(String poolName, String functionName, String timestampProperty,
                             String countProperty, long windowMillis, long maxCount) {}
    public record Feedback(String name, String poolName, String observationEventName, List<String> messageEvents,
                           String timestampProperty, String countProperty, long windowMillis) {
        public Feedback { messageEvents = List.copyOf(messageEvents); }
    }
    public record Group(List<WindowPool> fixedWindowPools, List<Feedback> feedbacks) {
        public Group { fixedWindowPools = List.copyOf(fixedWindowPools); feedbacks = List.copyOf(feedbacks); }
    }
    private final Map<String, MatchingGroup> matchingGroups;
    private final List<FixedWindowPoolDefinition> definitions;
    private final List<WorkerPropertyProjection> projections;
    private final Map<String, Group> groups;
    private final Map<String, Set<String>> requiredEvents;

    public WorkerGroupBindings(Map<String, MatchingGroup> original, FixedWindowBindingsProperties config,
                               List<FixedWindowPoolDefinition> externalDefinitions, ProjectDirectory projects) {
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
        config.feedbacks().forEach((name, value) -> requirePool(config, value.poolName(), "feedbacks." + name));
        var expanded = new TreeMap<String, ResolvedBinding>();
        config.groups().forEach((group, binding) -> merge(expanded, group, binding, "groups." + group, config));
        config.projects().forEach((id, binding) -> {
            String path = "projects." + id;
            var project = projects.projects().get(id);
            if (project == null) throw invalid(path, "unknown Project " + id);
            if (!"declared".equals(project.workerGroupSource()))
                throw invalid(path, "Project must have declared WorkerGroups; registered scope is not a binding source");
            project.workerGroupIds().forEach(group -> merge(expanded, group, binding, path, config));
        });
        var views = new TreeMap<String, Group>();
        var selections = new LinkedHashMap<String, List<WorkerPropertyProjection.Projection>>();
        var required = new TreeMap<String, Set<String>>();
        expanded.forEach((group, binding) -> {
            String path = "groups." + group;
            var previous = enabled.getOrDefault(group, new MatchingGroup(Set.of(), Set.of()));
            var pools = new TreeSet<>(previous.pools()); var functions = new TreeSet<>(previous.functions());
            var poolViews = new ArrayList<WindowPool>();
            binding.pools.forEach((name, limit) -> {
                var pool = requirePool(config, name, path + ".pools." + name);
                limits.get(name).put(group, limit);
                pools.add(name); functions.add(pool.functionName());
                poolViews.add(new WindowPool(name, pool.functionName(), pool.timestampProperty(), pool.countProperty(),
                        limit.windowMillis(), limit.maxCount()));
            });
            enabled.put(group, new MatchingGroup(pools, functions));
            var feedbackViews = new ArrayList<Feedback>();
            var groupSelections = new ArrayList<WorkerPropertyProjection.Projection>();
            var events = new TreeSet<String>(); var fields = new HashSet<String>();
            for (String name : binding.feedbackSources.keySet()) {
                String source = binding.feedbackSources.get(name) + " group=" + group;
                var feedback = config.feedbacks().get(name);
                var pool = config.pools().get(feedback.poolName());
                var limit = binding.pools.getOrDefault(feedback.poolName(), limit(pool, null));
                if (!fields.add(pool.timestampProperty()) || !fields.add(pool.countProperty()))
                    throw invalid(source, "feedback " + name + " has overlapping Platform Properties fields");
                var projection = new AssignmentWindowProjection(limit.windowMillis(), pool.timestampProperty(), pool.countProperty());
                for (String event : feedback.messageEvents()) {
                    if (!events.add(event)) throw invalid(source, "duplicate worker.assigned selection: " + event);
                    groupSelections.add(new WorkerPropertyProjection.Projection(event, projection::project));
                }
                feedbackViews.add(new Feedback(name, feedback.poolName(), "worker.assigned", feedback.messageEvents(),
                        pool.timestampProperty(), pool.countProperty(), limit.windowMillis()));
            }
            if (!groupSelections.isEmpty()) selections.put(group, List.copyOf(groupSelections));
            required.put(group, Collections.unmodifiableSet(events));
            views.put(group, new Group(poolViews, feedbackViews));
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

    /** Constructor-local accumulation only; all runtime consumers receive the immutable expansion. */
    private static final class ResolvedBinding {
        final Map<String, WindowLimit> pools = new TreeMap<>();
        final Map<String, String> poolSources = new TreeMap<>();
        final Map<String, String> feedbackSources = new TreeMap<>();
    }

    private static void merge(Map<String, ResolvedBinding> expanded, String group,
                              FixedWindowBindingsProperties.Group binding, String source,
                              FixedWindowBindingsProperties config) {
        var resolved = expanded.computeIfAbsent(group, ignored -> new ResolvedBinding());
        binding.pools().forEach((name, overrides) -> {
            String path = source + ".pools." + name;
            var limit = limit(requirePool(config, name, path), overrides);
            var previous = resolved.pools.putIfAbsent(name, limit);
            if (previous != null && !previous.equals(limit))
                throw invalid(path, "conflicting limits for group=" + group + " Pool=" + name
                        + "; also declared at " + resolved.poolSources.get(name));
            resolved.poolSources.putIfAbsent(name, path);
        });
        for (String name : binding.feedbacks()) {
            String path = source + ".feedbacks";
            if (!config.feedbacks().containsKey(name)) throw invalid(path, "unknown feedback " + name + " group=" + group);
            resolved.feedbackSources.putIfAbsent(name, path);
        }
    }

    private static FixedWindowBindingsProperties.Pool requirePool(FixedWindowBindingsProperties config, String name, String path) {
        var pool = config.pools().get(name);
        if (pool == null) throw invalid(path, "unknown Pool " + name);
        return pool;
    }
    private static WindowLimit limit(FixedWindowBindingsProperties.Pool pool, FixedWindowBindingsProperties.Limits overrides) {
        return new WindowLimit(overrides == null || overrides.windowMillis() == null ? pool.windowMillis() : overrides.windowMillis(),
                overrides == null || overrides.maxCount() == null ? pool.maxCount() : overrides.maxCount());
    }
    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("operation=workerBindings.assemble xa.mass.worker-assembly.fixed-window." + path + ": " + message);
    }
    public Map<String, MatchingGroup> matchingGroups() { return matchingGroups; }
    public List<FixedWindowPoolDefinition> definitions() { return definitions; }
    public List<WorkerPropertyProjection> projections() { return projections; }
    public Map<String, Set<String>> requiredEvents() { return requiredEvents; }
    public Group group(String group) { return groups.getOrDefault(group, new Group(List.of(), List.of())); }
}
