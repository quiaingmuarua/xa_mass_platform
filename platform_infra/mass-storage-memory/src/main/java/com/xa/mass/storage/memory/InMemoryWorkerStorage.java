package com.xa.mass.storage.memory;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.WorkerStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存 Worker 存储实现
 */
public class InMemoryWorkerStorage implements WorkerStorage {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashMap<String, WorkerContext>> workerContextsByWorker = new ConcurrentHashMap<>();
    private final Map<String, String> workerIdByContextId = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashSet<String>> workerIdsByProject = new ConcurrentHashMap<>();
    private final Map<String, LinkedHashSet<String>> workerIdsByEventCode = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> indexedProjectsByWorker = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> indexedEventCodesByWorker = new ConcurrentHashMap<>();
    private final Set<String> lockedWorkers = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void addWorker(Worker worker) {
        synchronized (this) {
            Worker previous = workers.put(worker.getWorkerId(), worker);
            removeWorkerIndexes(previous);
            addWorkerIndexes(worker);
        }
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    @Override
    public boolean updateWorker(Worker worker) {
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

    @Override
    public boolean deleteWorker(String workerId) {
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

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workers.values().stream()
                .filter(w -> workerGroupId != null && workerGroupId.equals(w.getWorkerGroupId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Worker> getWorkersBySupportedProject(String project) {
        synchronized (this) {
            String normalizedProject = normalize(project);
            return normalizedProject == null
                    ? List.of()
                    : workersByIndexedIds(workerIdsByProject.get(normalizedProject));
        }
    }

    @Override
    public List<Worker> getWorkersBySupportedEventCode(String eventCode) {
        synchronized (this) {
            String normalizedEventCode = normalize(eventCode);
            return normalizedEventCode == null
                    ? List.of()
                    : workersByIndexedIds(workerIdsByEventCode.get(normalizedEventCode));
        }
    }

    @Override
    public List<Worker> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    @Override
    public void addWorkerContext(WorkerContext workerContext) {
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

    @Override
    public List<WorkerContext> getWorkerContexts(String workerId) {
        synchronized (this) {
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            if (contexts == null || contexts.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(contexts.values());
        }
    }

    @Override
    public List<WorkerContext> getWorkerContextsByWorkerIds(List<String> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }
        synchronized (this) {
            List<WorkerContext> result = new ArrayList<>();
            for (String workerId : workerIds) {
                LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
                if (contexts != null) {
                    result.addAll(contexts.values());
                }
            }
            return result;
        }
    }

    @Override
    public Optional<WorkerContext> getWorkerContextById(String workerContextId) {
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

    @Override
    public boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
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

    @Override
    public boolean deleteWorkerContextById(String workerContextId) {
        synchronized (this) {
            String workerId = workerIdByContextId.get(workerContextId);
            if (workerId == null) {
                return false;
            }
            return workerContextByIdRemove(workerId, workerContextId);
        }
    }

    @Override
    public List<WorkerContext> getAllWorkerContexts() {
        synchronized (this) {
            return workerContextsByWorker.values().stream()
                    .flatMap(contexts -> contexts.values().stream())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    @Override
    public boolean tryLockWorker(String workerId) {
        return lockedWorkers.add(workerId);
    }

    @Override
    public void unlockWorker(String workerId) {
        lockedWorkers.remove(workerId);
    }

    @Override
    public boolean isLocked(String workerId) {
        return lockedWorkers.contains(workerId);
    }

    @Override
    public List<String> getLockedWorkers() {
        return new ArrayList<>(lockedWorkers);
    }

    private boolean workerContextByIdRemove(String workerId, String workerContextId) {
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
