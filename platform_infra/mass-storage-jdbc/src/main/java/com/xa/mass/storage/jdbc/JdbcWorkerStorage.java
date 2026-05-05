package com.xa.mass.storage.jdbc;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.WorkerStorage;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JdbcWorkerStorage extends JdbcStorageSupport implements WorkerStorage {

    private final JdbcDialect dialect;
    private final JdbcWorkerCompatibilityProjection runtimeProjection = new JdbcWorkerCompatibilityProjection();
    private final Map<String, String> durableWorkerJsonById = new HashMap<>();
    private final Map<String, String> durableWorkerContextJsonById = new HashMap<>();
    private boolean loadedFromDb;

    public JdbcWorkerStorage(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = dialect;
    }

    @Override
    public synchronized void addWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null) {
            throw new IllegalArgumentException("worker and workerId are required");
        }
        ensureLoaded();
        runtimeProjection.addWorker(worker);
        persistWorkerDefinition(worker);
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        ensureLoaded();
        return runtimeProjection.getWorker(workerId);
    }

    @Override
    public synchronized boolean updateWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null) {
            return false;
        }
        ensureLoaded();
        boolean updated = runtimeProjection.updateWorker(worker);
        if (!updated) {
            return false;
        }
        persistWorkerDefinition(worker);
        return true;
    }

    @Override
    public synchronized boolean deleteWorker(String workerId) {
        ensureLoaded();
        List<String> ownedContextIds = runtimeProjection.getWorkerContexts(workerId).stream()
                .map(WorkerContext::getWorkerContextId)
                .toList();
        try (var conn = connection()) {
            conn.setAutoCommit(false);
            try {
                update(conn, "DELETE FROM xa_worker_context WHERE worker_id = ?", workerId);
                int deleted = update(conn, "DELETE FROM xa_worker WHERE worker_id = ?", workerId);
                conn.commit();
                if (deleted > 0) {
                    runtimeProjection.deleteWorker(workerId);
                    durableWorkerJsonById.remove(workerId);
                    ownedContextIds.forEach(durableWorkerContextJsonById::remove);
                }
                return deleted > 0;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete worker " + workerId, e);
        }
    }

    @Override
    public List<Worker> getWorkersByGroupId(String workerGroupId) {
        ensureLoaded();
        return runtimeProjection.getWorkersByGroupId(workerGroupId);
    }

    @Override
    public List<Worker> getWorkersBySupportedProject(String project) {
        ensureLoaded();
        return runtimeProjection.getWorkersBySupportedProject(project);
    }

    @Override
    public List<Worker> getWorkersBySupportedEventCode(String eventCode) {
        ensureLoaded();
        return runtimeProjection.getWorkersBySupportedEventCode(eventCode);
    }

    @Override
    public List<Worker> getAllWorkers() {
        ensureLoaded();
        return runtimeProjection.getAllWorkers();
    }

    @Override
    public synchronized void addWorkerContext(WorkerContext workerContext) {
        if (workerContext == null || workerContext.getWorkerId() == null || workerContext.getWorkerContextId() == null) {
            throw new IllegalArgumentException("workerContext, workerId, and workerContextId are required");
        }
        ensureLoaded();
        runtimeProjection.addWorkerContext(workerContext);
        persistWorkerContextDefinition(workerContext);
    }

    @Override
    public List<WorkerContext> getWorkerContexts(String workerId) {
        ensureLoaded();
        return runtimeProjection.getWorkerContexts(workerId);
    }

    @Override
    public Optional<WorkerContext> getWorkerContextById(String workerContextId) {
        ensureLoaded();
        return runtimeProjection.getWorkerContextById(workerContextId);
    }

    @Override
    public synchronized boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        ensureLoaded();
        if (workerContext == null || workerContext.getWorkerContextId() == null || workerContext.getWorkerId() == null) {
            return false;
        }
        if (!workerContextId.equals(workerContext.getWorkerContextId())) {
            return false;
        }
        boolean updated = runtimeProjection.updateWorkerContextById(workerContextId, workerContext);
        if (!updated) {
            return false;
        }
        persistWorkerContextDefinition(workerContext);
        return true;
    }

    @Override
    public synchronized boolean deleteWorkerContextById(String workerContextId) {
        ensureLoaded();
        try (var conn = connection()) {
            boolean deleted = update(conn, "DELETE FROM xa_worker_context WHERE worker_context_id = ?", workerContextId) > 0;
            if (deleted) {
                runtimeProjection.deleteWorkerContextById(workerContextId);
                durableWorkerContextJsonById.remove(workerContextId);
            }
            return deleted;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete worker context " + workerContextId, e);
        }
    }

    @Override
    public List<WorkerContext> getAllWorkerContexts() {
        ensureLoaded();
        return runtimeProjection.getAllWorkerContexts();
    }

    @Override
    public List<WorkerContext> getWorkerContextsByWorkerIds(List<String> workerIds) {
        ensureLoaded();
        return runtimeProjection.getWorkerContextsByWorkerIds(workerIds);
    }

    @Override
    public synchronized boolean tryLockWorker(String workerId) {
        ensureLoaded();
        return runtimeProjection.tryLockWorker(workerId);
    }

    @Override
    public synchronized void unlockWorker(String workerId) {
        ensureLoaded();
        runtimeProjection.unlockWorker(workerId);
    }

    @Override
    public boolean isLocked(String workerId) {
        ensureLoaded();
        return runtimeProjection.isLocked(workerId);
    }

    @Override
    public List<String> getLockedWorkers() {
        ensureLoaded();
        return runtimeProjection.getLockedWorkers();
    }

    private List<Worker> queryWorkers(String sql, String... args) {
        return queryJson(sql, Worker.class, args);
    }

    private List<WorkerContext> queryWorkerContexts(String sql, String... args) {
        return queryJson(sql, WorkerContext.class, args);
    }

    private <T> List<T> queryJson(String sql, Class<T> type, String... args) {
        try (var conn = connection(); var ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            List<T> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readJson(rs.getString(1), type));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query " + type.getSimpleName(), e);
        }
    }

    private synchronized void ensureLoaded() {
        if (loadedFromDb) {
            return;
        }
        for (Worker worker : queryWorkers("SELECT json FROM xa_worker")) {
            Worker durableWorker = durableWorker(worker);
            runtimeProjection.addWorker(durableWorker);
            durableWorkerJsonById.put(durableWorker.getWorkerId(), json(durableWorker));
        }
        for (WorkerContext workerContext : queryWorkerContexts("SELECT json FROM xa_worker_context")) {
            WorkerContext durableContext = durableWorkerContext(workerContext);
            runtimeProjection.addWorkerContext(durableContext);
            durableWorkerContextJsonById.put(durableContext.getWorkerContextId(), json(durableContext));
        }
        loadedFromDb = true;
    }

    private void persistWorkerDefinition(Worker worker) {
        Worker durableWorker = durableWorker(worker);
        String workerId = durableWorker.getWorkerId();
        String durableJson = json(durableWorker);
        if (durableJson.equals(durableWorkerJsonById.get(workerId))) {
            return;
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.workerUpsertSql())) {
            ps.setString(1, workerId);
            ps.setString(2, durableWorker.getWorkerGroupId());
            ps.setString(3, durableJson);
            ps.executeUpdate();
            durableWorkerJsonById.put(workerId, durableJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save worker " + workerId, e);
        }
    }

    private void persistWorkerContextDefinition(WorkerContext workerContext) {
        WorkerContext durableContext = durableWorkerContext(workerContext);
        String workerContextId = durableContext.getWorkerContextId();
        String durableJson = json(durableContext);
        if (durableJson.equals(durableWorkerContextJsonById.get(workerContextId))) {
            return;
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.workerContextUpsertSql())) {
            ps.setString(1, workerContextId);
            ps.setString(2, durableContext.getWorkerId());
            ps.setString(3, durableJson);
            ps.executeUpdate();
            durableWorkerContextJsonById.put(workerContextId, durableJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save worker context " + workerContextId, e);
        }
    }

    private Worker durableWorker(Worker source) {
        Worker worker = new Worker();
        worker.setWorkerId(source.getWorkerId());
        worker.setAgentVersion(source.getAgentVersion());
        worker.setSupportedProjects(source.getSupportedProjects());
        worker.setSupportedEventCodes(source.getSupportedEventCodes());
        worker.setWorkerGroupId(source.getWorkerGroupId());
        worker.setAdapterId(source.getAdapterId());
        worker.setOnlineStrategy(source.getOnlineStrategy());
        worker.setAttributes(source.getAttributes());
        worker.setCreateTime(source.getCreateTime());
        worker.setUpdateTime(source.getCreateTime());
        return worker;
    }

    private WorkerContext durableWorkerContext(WorkerContext source) {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId(source.getWorkerContextId());
        workerContext.setWorkerId(source.getWorkerId());
        workerContext.setProject(source.getProject());
        workerContext.setRoutingTags(source.getRoutingTags());
        workerContext.setAttributes(source.getAttributes());
        workerContext.setExpireTime(source.getExpireTime());
        workerContext.setCreateTime(source.getCreateTime());
        workerContext.setUpdateTime(source.getCreateTime());
        return workerContext;
    }

    private int update(java.sql.Connection conn, String sql, String arg) throws Exception {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            return ps.executeUpdate();
        }
    }

    private void bind(java.sql.PreparedStatement ps, String... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setString(i + 1, args[i]);
        }
    }
}

