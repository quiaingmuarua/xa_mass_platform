package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingView;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Default load-aware candidate ranker.
 *
 * <p>Lower score is better. The defaults intentionally keep load dominant
 * while allowing routing affinity and scheduling-resource availability to
 * break ties.</p>
 */
public final class DefaultWorkerCandidateRanker implements WorkerCandidateRanker {

    static final double DEFAULT_LOAD_WEIGHT = 0.6d;
    static final double DEFAULT_AFFINITY_WEIGHT = 0.3d;
    static final double DEFAULT_AVAILABILITY_WEIGHT = 0.1d;

    private final double loadWeight;
    private final double affinityWeight;
    private final double availabilityWeight;

    public DefaultWorkerCandidateRanker() {
        this(DEFAULT_LOAD_WEIGHT, DEFAULT_AFFINITY_WEIGHT, DEFAULT_AVAILABILITY_WEIGHT);
    }

    DefaultWorkerCandidateRanker(double loadWeight,
                                 double affinityWeight,
                                 double availabilityWeight) {
        this.loadWeight = loadWeight;
        this.affinityWeight = affinityWeight;
        this.availabilityWeight = availabilityWeight;
    }

    @Override
    public List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, Task task) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(candidate -> score(candidate, task)))
                .toList();
    }

    double score(WorkerMatchContext candidate, Task task) {
        if (candidate == null) {
            return Double.POSITIVE_INFINITY;
        }
        WorkerSchedulingView view = candidate.getSchedulingView();
        double loadRatio = view != null ? view.estimatedLoadRatio() : 1.0d;
        double affinityScore = affinityScore(view, task);
        double availabilityPenalty = availabilityPenalty(view);
        return loadWeight * loadRatio
                + affinityWeight * (1.0d - affinityScore)
                + availabilityWeight * availabilityPenalty;
    }

    private double affinityScore(WorkerSchedulingView view, Task task) {
        String routingCode = TaskSharedConfig.routingCode(task);
        if (routingCode == null || routingCode.isBlank()) {
            return 1.0d;
        }
        if (view == null) {
            return 0.0d;
        }
        String normalizedRoutingCode = normalize(routingCode);
        if (view.schedulingRoutingTagsContain(routingCode)) {
            return 1.0d;
        }
        if (containsValue(view.schedulingAttributes(), normalizedRoutingCode)
                || containsValue(view.workerAttributes(), normalizedRoutingCode)) {
            return 1.0d;
        }
        if (view.schedulingRoutingTags().stream()
                .map(DefaultWorkerCandidateRanker::normalize)
                .anyMatch(tag -> partialMatch(tag, normalizedRoutingCode))) {
            return 0.5d;
        }
        return 0.0d;
    }

    private double availabilityPenalty(WorkerSchedulingView view) {
        if (view == null) {
            return 10.0d;
        }
        if (view.schedulingResourceAvailable()) {
            return 0.0d;
        }
        if (view.schedulingResourceUsable()) {
            return 1.0d;
        }
        return 10.0d;
    }

    private static boolean containsValue(Map<String, String> attributes, String expected) {
        if (attributes == null || attributes.isEmpty() || expected == null || expected.isBlank()) {
            return false;
        }
        return attributes.values().stream()
                .map(DefaultWorkerCandidateRanker::normalize)
                .anyMatch(expected::equals);
    }

    private static boolean partialMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
