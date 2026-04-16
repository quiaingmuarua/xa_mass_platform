package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

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
    private final Set<String> lockedWorkers = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void addWorker(Worker worker) {
        workers.put(worker.getWorkerId(), worker);
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    @Override
    public boolean updateWorker(Worker worker) {
        if (worker.getWorkerId() == null || !workers.containsKey(worker.getWorkerId())) {
            return false;
        }
        workers.put(worker.getWorkerId(), worker);
        return true;
    }

    @Override
    public boolean deleteWorker(String workerId) {
        Worker removed = workers.remove(workerId);
        if (removed != null) {
            LinkedHashMap<String, WorkerContext> removedContexts = workerContextsByWorker.remove(workerId);
            if (removedContexts != null) {
                removedContexts.keySet().forEach(workerIdByContextId::remove);
            }
            lockedWorkers.remove(workerId);
        }
        return removed != null;
    }

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        return workers.values().stream()
                .filter(w -> workerGroupId != null && workerGroupId.equals(w.getWorkerGroupId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Worker> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    @Override
    public void addWorkerContext(String workerId, WorkerContext workerContext) {
        synchronized (this) {
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
    public Optional<WorkerContext> getWorkerContext(String workerId) {
        synchronized (this) {
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            return Optional.ofNullable(selectCompatibilityContext(contexts));
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
    public boolean updateWorkerContext(String workerId, WorkerContext workerContext) {
        synchronized (this) {
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            if (contexts == null || contexts.isEmpty()) {
                return false;
            }

            String targetContextId = workerContext.getWorkerContextId();
            if (targetContextId != null && contexts.containsKey(targetContextId)) {
                contexts.put(targetContextId, workerContext);
                workerIdByContextId.put(targetContextId, workerId);
                return true;
            }

            if (contexts.size() != 1) {
                return false;
            }

            WorkerContext compatibility = selectCompatibilityContext(contexts);
            if (compatibility == null) {
                return false;
            }
            targetContextId = compatibility.getWorkerContextId();
            if (targetContextId == null || targetContextId.isBlank()) {
                return false;
            }

            String newContextId = workerContext.getWorkerContextId();
            if (newContextId == null || newContextId.isBlank()) {
                workerContext.setWorkerContextId(targetContextId);
                newContextId = targetContextId;
            }
            if (!targetContextId.equals(newContextId)) {
                contexts.remove(targetContextId);
                workerIdByContextId.remove(targetContextId);
            }

            contexts.put(newContextId, workerContext);
            workerIdByContextId.put(newContextId, workerId);
            return true;
        }
    }

    @Override
    public boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        synchronized (this) {
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
    public boolean deleteWorkerContext(String workerId) {
        synchronized (this) {
            LinkedHashMap<String, WorkerContext> contexts = workerContextsByWorker.get(workerId);
            WorkerContext compatibility = selectCompatibilityContext(contexts);
            if (compatibility == null) {
                return false;
            }
            return workerContextByIdRemove(workerId, compatibility.getWorkerContextId());
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

    private WorkerContext selectCompatibilityContext(LinkedHashMap<String, WorkerContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return null;
        }

        return contexts.values().stream()
                .filter(WorkerContext::isInUse)
                .findFirst()
                .or(() -> contexts.values().stream().filter(WorkerContext::isAllocatable).findFirst())
                .orElseGet(() -> contexts.values().iterator().next());
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
}
