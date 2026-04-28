package com.xa.mass.server.storage;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.storage.WorkerStorage;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JdbcWorkerStorage extends JdbcStorageSupport implements WorkerStorage {

    private final JdbcDialect dialect;

    public JdbcWorkerStorage(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = dialect;
    }

    @Override
    public synchronized void addWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null) {
            throw new IllegalArgumentException("worker and workerId are required");
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.workerUpsertSql())) {
            ps.setString(1, worker.getWorkerId());
            ps.setString(2, worker.getWorkerGroupId());
            ps.setString(3, json(worker));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save worker " + worker.getWorkerId(), e);
        }
    }

    @Override
    public Optional<Worker> getWorker(String workerId) {
        return queryWorkers("SELECT json FROM xa_worker WHERE worker_id = ?", workerId).stream().findFirst();
    }

    @Override
    public synchronized boolean updateWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null) {
            return false;
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                UPDATE xa_worker SET worker_group_id = ?, json = ? WHERE worker_id = ?
                """)) {
            ps.setString(1, worker.getWorkerGroupId());
            ps.setString(2, json(worker));
            ps.setString(3, worker.getWorkerId());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update worker " + worker.getWorkerId(), e);
        }
    }

    @Override
    public synchronized boolean deleteWorker(String workerId) {
        try (var conn = connection()) {
            conn.setAutoCommit(false);
            try {
                update(conn, "DELETE FROM xa_worker_context WHERE worker_id = ?", workerId);
                update(conn, "DELETE FROM xa_worker_lock WHERE worker_id = ?", workerId);
                int deleted = update(conn, "DELETE FROM xa_worker WHERE worker_id = ?", workerId);
                conn.commit();
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
        return queryWorkers("SELECT json FROM xa_worker WHERE worker_group_id = ?", workerGroupId);
    }

    @Override
    public List<Worker> getAllWorkers() {
        return queryWorkers("SELECT json FROM xa_worker");
    }

    @Override
    public List<Worker> findWorkerCandidates(String project, String eventCode, String targetWorkerId) {
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return getWorker(targetWorkerId.trim()).map(List::of).orElse(List.of());
        }
        String normalizedEventCode = normalize(eventCode);
        String normalizedProject = normalize(project);
        List<Worker> candidates = new ArrayList<>();
        for (Worker worker : getAllWorkers()) {
            if (normalizedEventCode != null && containsNormalized(worker.getSupportedEventCodes(), normalizedEventCode)) {
                candidates.add(worker);
            } else if (normalizedEventCode == null
                    && normalizedProject != null
                    && containsNormalized(worker.getSupportedProjects(), normalizedProject)) {
                candidates.add(worker);
            } else if (normalizedEventCode == null && normalizedProject == null) {
                candidates.add(worker);
            }
        }
        return candidates;
    }

    @Override
    public synchronized void addWorkerContext(WorkerContext workerContext) {
        if (workerContext == null || workerContext.getWorkerId() == null || workerContext.getWorkerContextId() == null) {
            throw new IllegalArgumentException("workerContext, workerId, and workerContextId are required");
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.workerContextUpsertSql())) {
            ps.setString(1, workerContext.getWorkerContextId());
            ps.setString(2, workerContext.getWorkerId());
            ps.setString(3, json(workerContext));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save worker context " + workerContext.getWorkerContextId(), e);
        }
    }

    @Override
    public List<WorkerContext> getWorkerContexts(String workerId) {
        return queryWorkerContexts("SELECT json FROM xa_worker_context WHERE worker_id = ?", workerId);
    }

    @Override
    public Optional<WorkerContext> getWorkerContextById(String workerContextId) {
        return queryWorkerContexts("SELECT json FROM xa_worker_context WHERE worker_context_id = ?", workerContextId)
                .stream().findFirst();
    }

    @Override
    public synchronized boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext) {
        if (workerContext == null || workerContext.getWorkerContextId() == null || workerContext.getWorkerId() == null) {
            return false;
        }
        if (!workerContextId.equals(workerContext.getWorkerContextId())) {
            return false;
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                UPDATE xa_worker_context SET worker_id = ?, json = ? WHERE worker_context_id = ?
                """)) {
            ps.setString(1, workerContext.getWorkerId());
            ps.setString(2, json(workerContext));
            ps.setString(3, workerContextId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update worker context " + workerContextId, e);
        }
    }

    @Override
    public synchronized boolean deleteWorkerContextById(String workerContextId) {
        try (var conn = connection()) {
            return update(conn, "DELETE FROM xa_worker_context WHERE worker_context_id = ?", workerContextId) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete worker context " + workerContextId, e);
        }
    }

    @Override
    public List<WorkerContext> getAllWorkerContexts() {
        return queryWorkerContexts("SELECT json FROM xa_worker_context");
    }

    @Override
    public synchronized boolean tryLockWorker(String workerId) {
        try (var conn = connection(); var ps = conn.prepareStatement("INSERT INTO xa_worker_lock(worker_id) VALUES (?)")) {
            ps.setString(1, workerId);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized void unlockWorker(String workerId) {
        try (var conn = connection()) {
            update(conn, "DELETE FROM xa_worker_lock WHERE worker_id = ?", workerId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unlock worker " + workerId, e);
        }
    }

    @Override
    public boolean isLocked(String workerId) {
        return queryStrings("SELECT worker_id FROM xa_worker_lock WHERE worker_id = ?", workerId).contains(workerId);
    }

    @Override
    public List<String> getLockedWorkers() {
        return queryStrings("SELECT worker_id FROM xa_worker_lock");
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

    private List<String> queryStrings(String sql, String... args) {
        try (var conn = connection(); var ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query worker locks", e);
        }
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

    private boolean containsNormalized(Collection<String> values, String expected) {
        return normalizedSet(values).contains(expected);
    }

    private Set<String> normalizedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
