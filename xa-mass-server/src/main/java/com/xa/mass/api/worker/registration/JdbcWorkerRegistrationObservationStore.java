package com.xa.mass.api.worker.registration;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcWorkerRegistrationObservationStore implements WorkerRegistrationObservationStore {

    private final DataSource dataSource;

    public JdbcWorkerRegistrationObservationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
    }

    @Override
    public WorkerRegistrationObservationRecord append(WorkerRegistrationObservationRecord record) {
        WorkerRegistrationObservationRecord normalized = Objects.requireNonNull(record, "record is required");
        String sql = """
                INSERT INTO xa_worker_registration_observation (
                    observation_id,
                    resource_type,
                    resource_id,
                    action,
                    principal_id,
                    principal_type,
                    request_hash,
                    payload_json,
                    occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             var ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalized.observationId());
            ps.setString(2, normalized.resourceType());
            ps.setString(3, normalized.resourceId());
            ps.setString(4, normalized.action());
            ps.setString(5, normalized.principalId());
            ps.setString(6, normalized.principalType());
            ps.setString(7, normalized.requestHash());
            ps.setString(8, normalized.payloadJson());
            ps.setTimestamp(9, Timestamp.from(normalized.occurredAt()));
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append worker registration observation", e);
        }
    }

    @Override
    public List<WorkerRegistrationObservationRecord> listByResource(String resourceType, String resourceId) {
        String sql = """
                SELECT observation_id,
                       resource_type,
                       resource_id,
                       action,
                       principal_id,
                       principal_type,
                       request_hash,
                       payload_json,
                       occurred_at
                  FROM xa_worker_registration_observation
                 WHERE resource_type = ?
                   AND resource_id = ?
                 ORDER BY occurred_at, observation_id
                """;
        try (var connection = dataSource.getConnection();
             var ps = connection.prepareStatement(sql)) {
            ps.setString(1, resourceType);
            ps.setString(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<WorkerRegistrationObservationRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(read(rs));
                }
                return List.copyOf(records);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list worker registration observations", e);
        }
    }

    private WorkerRegistrationObservationRecord read(ResultSet rs) throws Exception {
        return new WorkerRegistrationObservationRecord(
                rs.getString("observation_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("action"),
                rs.getString("principal_id"),
                rs.getString("principal_type"),
                rs.getString("request_hash"),
                rs.getString("payload_json"),
                rs.getTimestamp("occurred_at").toInstant()
        );
    }
}
