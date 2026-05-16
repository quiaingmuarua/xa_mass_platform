package com.xa.mass.storage.memory;

import com.xa.mass.base.model.Worker;
import com.xa.mass.storage.api.WorkerStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存 Worker 存储实现
 */
public class InMemoryWorkerStorage implements WorkerStorage {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
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
