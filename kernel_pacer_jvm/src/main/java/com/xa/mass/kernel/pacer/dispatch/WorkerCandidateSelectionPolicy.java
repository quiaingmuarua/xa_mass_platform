package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dispatch consumes Matching inventory and resolves current addresses; candidateization belongs to the separate Refill Producer. */
final class WorkerCandidateSelectionPolicy {
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerMatching matching;

    WorkerCandidateSelectionPolicy(WorkerResourceCatalog workerCatalog, WorkerMatching matching) {
        this.workerCatalog=Objects.requireNonNull(workerCatalog,"workerCatalog");
        this.matching=Objects.requireNonNull(matching,"matching");
    }

    Map<String,RoutedWorkerCandidate> takeCandidates(String workerGroupId,
            Map<String,WorkerQuery> selectors, Set<String> roundWorkerIds) {
        if (selectors.size()>100) throw new IllegalArgumentException("at most 100 Item selectors");
        if (selectors.isEmpty()) return Map.of();
        var taken=matching.take(workerGroupId,selectors);
        var selected=new LinkedHashMap<String,String>();
        var scores=new LinkedHashMap<String,Long>();
        selectors.keySet().forEach(id -> {
            var held=taken.get(id);
            if (held!=null && roundWorkerIds.add(held.workerId())) {
                selected.put(id,held.workerId()); scores.put(held.workerId(),held.expectedScore());
            }
        });
        var described=describeById(workerGroupId,scores);
        var result=new LinkedHashMap<String,RoutedWorkerCandidate>();
        selectors.keySet().forEach(item -> {
            String worker=selected.get(item);
            var candidate=worker==null ? null : described.get(worker);
            if (candidate!=null) result.put(item,candidate);
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, RoutedWorkerCandidate> describeById(
            String workerGroupId,
            Map<String, Long> expectedScores
    ) {
        if (expectedScores.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        List.copyOf(expectedScores.keySet())
                );
        LinkedHashMap<String, RoutedWorkerCandidate> result =
                new LinkedHashMap<>();
        expectedScores.forEach((workerId, heldScore) -> {
            WorkerDescriptor descriptor = descriptors.get(workerId);
            if (descriptor != null
                    && workerGroupId.equals(descriptor.workerGroupId())
                    && workerId.equals(descriptor.workerId())) {
                result.put(workerId, new RoutedWorkerCandidate(
                        descriptor.workerId(),
                        descriptor.workerGroupId(),
                        descriptor.endpointManagerId(),
                        heldScore
                ));
            }
        });
        return Collections.unmodifiableMap(result);
    }

}
