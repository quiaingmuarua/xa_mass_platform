package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dispatch consumes Matching inventory and resolves current addresses; it never obtains initial holds. */
final class WorkerCandidateSelectionPolicy {
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerCandidateIndex index;

    WorkerCandidateSelectionPolicy(WorkerResourceCatalog workerCatalog, WorkerCandidateIndex index) {
        this.workerCatalog=Objects.requireNonNull(workerCatalog,"workerCatalog");
        this.index=Objects.requireNonNull(index,"index");
    }

    Map<String,HeldWorkerCandidate> takeCandidates(String ruleId, String workerGroupId,
            Map<String,EligibilityQuery> selectors, Set<String> roundWorkerIds) {
        if (selectors.size()>100) throw new IllegalArgumentException("at most 100 Item selectors");
        if (selectors.isEmpty()) return Map.of();
        var items=new LinkedHashMap<EligibilityQuery,List<String>>();
        selectors.forEach((id,selector) -> {
            var normalized = index.normalizeQuery(workerGroupId,ruleId,selector);
            items.computeIfAbsent(normalized,ignored -> new ArrayList<>()).add(id);
        });
        var limits=new LinkedHashMap<EligibilityQuery,Integer>();
        items.forEach((selector,ids) -> limits.put(selector,ids.size()));
        var taken=index.take(workerGroupId,ruleId,limits);
        var selected=new LinkedHashMap<String,String>();
        var scores=new LinkedHashMap<String,Long>();
        items.forEach((selector,ids) -> {
            int i=0;
            for (var held:taken.getOrDefault(selector,List.of())) {
                if (i==ids.size()) break;
                if (roundWorkerIds.add(held.workerId())) {
                    selected.put(ids.get(i++),held.workerId()); scores.put(held.workerId(),held.score());
                }
            }
        });
        var described=describeById(workerGroupId,scores);
        var result=new LinkedHashMap<String,HeldWorkerCandidate>();
        selectors.keySet().forEach(item -> {
            String worker=selected.get(item);
            var candidate=worker==null ? null : described.get(worker);
            if (candidate!=null) result.put(item,candidate);
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, HeldWorkerCandidate> describeById(
            String workerGroupId,
            Map<String, Long> heldScores
    ) {
        if (heldScores.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        List.copyOf(heldScores.keySet())
                );
        LinkedHashMap<String, HeldWorkerCandidate> result =
                new LinkedHashMap<>();
        heldScores.forEach((workerId, heldScore) -> {
            WorkerDescriptor descriptor = descriptors.get(workerId);
            if (descriptor != null
                    && workerGroupId.equals(descriptor.workerGroupId())
                    && workerId.equals(descriptor.workerId())) {
                result.put(workerId, new HeldWorkerCandidate(
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
