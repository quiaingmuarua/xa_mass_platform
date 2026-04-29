package com.xa.mass.storage.jdbc;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-local worker/runtime residue projection used by JDBC worker storage.
 */
final class JdbcWorkerCompatibilityProjection {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashMap<String, WorkerContext>> workerContextsByWorker = new ConcurrentHashMap<>();
    private final Map<String, String> workerIdByContextId = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashSet<String>> workerIdsByProject = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashSet<String>> workerIdsByEventCode = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> indexedProjectsByWorker = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> indexedEventCodesByWorker = new ConcurrentHashMap<>();
    private final Set<String> lockedWorkers = Collections.synchronizedSet(new HashSet<>());

    void addWorker(Worker worker) {
        synchronized (this) {
            Worker previous = workers.put(worker.getWorkerId(), worker);
            removeWorkerIndexes(previous);
            addWorkerIndexes(worker);
        }
    }

    Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    boolean updateWorker(Worker worker) {
        synchronized (this) {
            if (worker.getWorkerId() == null || !workers.containsKey(worker.getWorkerId())) {
                return false;
            }
            Worker previous = workers.put(worker.getWorkerId(), worker);
            removeWorkerIndexes(previous);
            addWorkerIndexes(worker);
            return true;
        }
    }

    boolean deleteWorker(String workerId) {
        synchronized (this) {
            Worker removed = workers.remove(workerId);
            if (removed != null) {
                removeWorkerIndexes(removed);
                LinkedHashMap<String, WorkerContext> removedContexts = workerContextsByWorker.remove(workerId);
                if (removedContexts != null) {
                    removedContexts.keySet().forEach(workerIdByContextId::remove);
                }
                lockedWorkers.remove(workerId);
            }
            return removed != null;
        }
    }

    List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workers.values().stream()
                .filter(w -> workerGroupId != null && workerGroupId.equals(w.getWorkerGroupId()))
                .collect(Collectors.toList());
    }

    List<Worker> getWorkersBySupportedProject(String project) {
        synchronized (this) {
            String normalizedProject = normalize(project);
            return normalizedProject == null
                    ? List.of()
                    : workersByIndexedIds(workerIdsByProject.get(normalizedProject));
        }
    }

    List<Worker> getWorkersBySupportedEventCode(String eventCode) {
        synchronized (this) {
            String normalizedEventCode = normalize(eventCode);
            return normalizedEventCode == null
                    ? List.of()
                    : workersByIndexedIds(workerIdsByEventCode.get(normalizedEventCode));
        }
    }

    List<Worker> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    void addWorkerContext(WorkerContext workerContext) {
        synchronized (this) {
            if (workerContext == null) {
                throw new IllegalArgumentException("workerContext is required");
            }
            String workerId = workerContext.getWorkerId();
            if (workerId == null || workerId.isBlank()) {
                throw new IllegalArgumentException("workerId is required on workerContext");
            }
            LinkedHashMap<String, WorkerContext> contexts =
                    workerContextsByWorker.computeIfAbsent(workerId, ignored -> new LinkedHashMap<>());
            String workerContextId = workerContext.getWorkerContextId();
            if (workerContextId == null || workerContextId.isBlank()) {
                throw new IllegalArgumentException("workerContextId is required");
            }

            String previousWorkerId = workerIdByContextId.put(workerContextId, workerId);
            if (previousWorkerId != null && !previousWorkerId.equals(workerId)) {
                LinkedHashMap<String, WorkerContext> previousContexts = workerContextsByWorker.get(previousWorkerId);
                if (previousContexts != null) {
                    previousContexts.remove(workerContextId);
                    if (previousContexts.isEmpty()) {
                        workerContextsByWorker.remove(previousWorkerId);
                    }
                }
            }

            contexts.put(workerContextId, workerContext);
        }
    }

    List<WorkerContext> getWorkerContexts(String workerId) {
        synchronized (this) {
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            if (contexts == null || contexts.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(contexts.values());
        }
    }

    Optional<WorkerContext> getWorkerContextById(String workerContextId) {
        synchronized (this) {
            String workerId = workerIdByContextId.get(workerContextId);
            if (workerId == null) {
                return Optional.empty();
            }
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            if (contexts == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(contexts.get(workerContextId));
        }
    }

    boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        synchronized (this) {
            if (workerContext == null) {
                return false;
            }
            String workerId = workerIdByContextId.get(workerContextId);
            if (workerId == null) {
                return false;
            }
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            if (contexts == null || !contexts.containsKey(workerContextId)) {
                return false;
            }

            String newContextId = workerContext.getWorkerContextId();
            if (newContextId == null || newContextId.isBlank()) {
                return false;
            }
            String ownerWorkerId = workerContext.getWorkerId();
            if (ownerWorkerId == null || ownerWorkerId.isBlank() || !workerId.equals(ownerWorkerId)) {
                return false;
            }
            if (!workerContextId.equals(newContextId)) {
                contexts.remove(workerContextId);
                workerIdByContextId.remove(workerContextId);
            }
            contexts.put(newContextId, workerContext);
            workerIdByContextId.put(newContextId, workerId);
            return true;
        }
    }

    boolean deleteWorkerContextById(String workerContextId) {
        synchronized (this) {
            String workerId = workerIdByContextId.get(workerContextId);
            if (workerId == null) {
                return false;
            }
            return removeWorkerContextById(workerId, workerContextId);
        }
    }

    List<WorkerContext> getAllWorkerContexts() {
        synchronized (this) {
            return workerContextsByWorker.values().stream()
                    .flatMap(contexts -> contexts.values().stream())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    boolean tryLockWorker(String workerId) {
        return lockedWorkers.add(workerId);
    }

    void unlockWorker(String workerId) {
        lockedWorkers.remove(workerId);
    }

    boolean isLocked(String workerId) {
        return lockedWorkers.contains(workerId);
    }

    List<String> getLockedWorkers() {
        return new ArrayList<>(lockedWorkers);
    }

    private boolean removeWorkerContextById(String workerId, String workerContextId) {
        LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
        if (contexts == null) {
            return false;
        }
        WorkerContext removed = contexts.remove(workerContextId);
        if (removed == null) {
            return false;
        }
        workerIdByContextId.remove(workerContextId);
        if (contexts.isEmpty()) {
            workerContextsByWorker.remove(workerId);
        }
        return true;
    }

    private void addWorkerIndexes(Worker worker) {
        String workerId = worker != null ? normalize(worker.getWorkerId()) : null;
        if (workerId == null) {
            return;
        }
        Set<String> projects = normalizedSet(worker.getSupportedProjects());
        Set<String> eventCodes = normalizedSet(worker.getSupportedEventCodes());
        indexedProjectsByWorker.put(workerId, projects);
        indexedEventCodesByWorker.put(workerId, eventCodes);

        for (String project : projects) {
            addWorkerIndex(workerIdsByProject, project, workerId);
        }
        for (String eventCode : eventCodes) {
            addWorkerIndex(workerIdsByEventCode, eventCode, workerId);
        }
    }

    private void removeWorkerIndexes(Worker worker) {
        String workerId = worker != null ? normalize(worker.getWorkerId()) : null;
        if (workerId == null) {
            return;
        }
        Set<String> projects = indexedProjectsByWorker.remove(workerId);
        if (projects == null) {
            projects = normalizedSet(worker.getSupportedProjects());
        }
        Set<String> eventCodes = indexedEventCodesByWorker.remove(workerId);
        if (eventCodes == null) {
            eventCodes = normalizedSet(worker.getSupportedEventCodes());
        }

        for (String project : projects) {
            removeWorkerIndex(workerIdsByProject, project, workerId);
        }
        for (String eventCode : eventCodes) {
            removeWorkerIndex(workerIdsByEventCode, eventCode, workerId);
        }
    }

    private void addWorkerIndex(Map<String, LinkedHashSet<String>> index, String key, String workerId) {
        String normalizedKey = normalize(key);
        if (normalizedKey == null) {
            return;
        }
        index.computeIfAbsent(normalizedKey, ignored -> new LinkedHashSet<>()).add(workerId);
    }

    private void removeWorkerIndex(Map<String, LinkedHashSet<String>> index, String key, String workerId) {
        String normalizedKey = normalize(key);
        if (normalizedKey == null) {
            return;
        }
        LinkedHashSet<String> workerIds = index.get(normalizedKey);
        if (workerIds == null) {
            return;
        }
        workerIds.remove(workerId);
        if (workerIds.isEmpty()) {
            index.remove(normalizedKey);
        }
    }

    private List<Worker> workersByIndexedIds(LinkedHashSet<String> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }
        List<Worker> candidates = new ArrayList<>(workerIds.size());
        for (String workerId : workerIds) {
            Worker worker = workers.get(workerId);
            if (worker != null) {
                candidates.add(worker);
            }
        }
        return candidates;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Set<String> normalizedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }
}
