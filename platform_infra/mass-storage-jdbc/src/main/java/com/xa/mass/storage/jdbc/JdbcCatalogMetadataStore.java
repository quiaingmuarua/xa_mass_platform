package com.xa.mass.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.CatalogProjectRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JdbcCatalogMetadataStore extends JdbcStorageSupport implements CatalogMetadataStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcDialect dialect;

    public JdbcCatalogMetadataStore(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = dialect;
    }

    @Override
    public synchronized void upsertEvent(CatalogEventRecord event) {
        upsertCatalog(List.of(event), List.of());
    }

    @Override
    public synchronized void upsertProject(CatalogProjectRecord project) {
        upsertCatalog(List.of(), List.of(project));
    }

    @Override
    public synchronized void validateUpsertCatalog(Collection<CatalogEventRecord> events,
                                                   Collection<CatalogProjectRecord> projects) {
        List<CatalogEventRecord> eventList = normalizeEvents(events);
        List<CatalogProjectRecord> projectList = normalizeProjects(projects);
        try (var conn = connection()) {
            validateReferences(conn, eventList, projectList);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate catalog metadata", e);
        }
    }

    @Override
    public synchronized void upsertCatalog(Collection<CatalogEventRecord> events,
                                           Collection<CatalogProjectRecord> projects) {
        List<CatalogEventRecord> eventList = normalizeEvents(events);
        List<CatalogProjectRecord> projectList = normalizeProjects(projects);
        try (var conn = connection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                validateReferences(conn, eventList, projectList);
                for (CatalogEventRecord event : eventList) {
                    upsertEvent(conn, event);
                    deleteBindingsForEvent(conn, event.code());
                }
                for (CatalogProjectRecord project : projectList) {
                    upsertProject(conn, project);
                    deleteBindingsForProject(conn, project.code());
                }
                for (CatalogEventRecord event : eventList) {
                    for (String projectCode : event.projectCodes()) {
                        insertBinding(conn, projectCode, event.code());
                    }
                }
                for (CatalogProjectRecord project : projectList) {
                    for (String eventCode : project.eventCodes()) {
                        insertBinding(conn, project.code(), eventCode);
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upsert catalog metadata", e);
        }
    }

    @Override
    public Optional<CatalogEventRecord> getEvent(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return Optional.empty();
        }
        try (var conn = connection();
             var ps = conn.prepareStatement("""
                     SELECT event_code, name, description, payload_types_json, task_modes_json, enabled,
                            default_routing_code, priority_class, response_mode, delivery_acknowledgement_mode,
                            convergence_mode, target_scope
                     FROM xa_catalog_event WHERE event_code = ?
                     """)) {
            ps.setString(1, eventCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEvent(conn, rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get catalog event " + eventCode, e);
        }
    }

    @Override
    public Optional<CatalogProjectRecord> getProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return Optional.empty();
        }
        try (var conn = connection();
             var ps = conn.prepareStatement("""
                     SELECT project_code, tenant_id, name, description, enabled, owner_principal_id
                     FROM xa_catalog_project WHERE project_code = ?
                     """)) {
            ps.setString(1, projectCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readProject(conn, rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get catalog project " + projectCode, e);
        }
    }

    @Override
    public List<CatalogEventRecord> listEvents() {
        try (var conn = connection();
             var ps = conn.prepareStatement("""
                     SELECT event_code, name, description, payload_types_json, task_modes_json, enabled,
                            default_routing_code, priority_class, response_mode, delivery_acknowledgement_mode,
                            convergence_mode, target_scope
                     FROM xa_catalog_event ORDER BY event_code
                     """);
             ResultSet rs = ps.executeQuery()) {
            List<CatalogEventRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(readEvent(conn, rs));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list catalog events", e);
        }
    }

    @Override
    public List<CatalogProjectRecord> listProjects() {
        try (var conn = connection();
             var ps = conn.prepareStatement("""
                     SELECT project_code, tenant_id, name, description, enabled, owner_principal_id
                     FROM xa_catalog_project ORDER BY project_code
                     """);
             ResultSet rs = ps.executeQuery()) {
            List<CatalogProjectRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(readProject(conn, rs));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list catalog projects", e);
        }
    }

    @Override
    public synchronized void clear() {
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM xa_catalog_project_event");
            stmt.executeUpdate("DELETE FROM xa_catalog_project");
            stmt.executeUpdate("DELETE FROM xa_catalog_event");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear catalog metadata", e);
        }
    }

    private void validateReferences(Connection conn,
                                    List<CatalogEventRecord> events,
                                    List<CatalogProjectRecord> projects) throws Exception {
        Set<String> knownEvents = new LinkedHashSet<>(existingCodes(conn, "xa_catalog_event", "event_code"));
        events.stream().map(CatalogEventRecord::code).forEach(knownEvents::add);

        Set<String> knownProjects = new LinkedHashSet<>(existingCodes(conn, "xa_catalog_project", "project_code"));
        projects.stream().map(CatalogProjectRecord::code).forEach(knownProjects::add);

        for (CatalogProjectRecord project : projects) {
            for (String eventCode : project.eventCodes()) {
                if (!knownEvents.contains(eventCode)) {
                    throw new IllegalArgumentException("project " + project.code()
                            + " references unknown event code " + eventCode);
                }
            }
        }
        for (CatalogEventRecord event : events) {
            for (String projectCode : event.projectCodes()) {
                if (!knownProjects.contains(projectCode)) {
                    throw new IllegalArgumentException("event " + event.code()
                            + " references unknown project code " + projectCode);
                }
            }
        }
    }

    private void upsertEvent(Connection conn, CatalogEventRecord event) throws Exception {
        try (var ps = conn.prepareStatement(dialect.catalogEventUpsertSql())) {
            ps.setString(1, event.code());
            ps.setString(2, event.name());
            ps.setString(3, event.description());
            ps.setString(4, json(event.payloadTypes()));
            ps.setString(5, json(event.taskModes()));
            ps.setBoolean(6, event.enabled());
            ps.setString(7, event.defaultRoutingCode());
            ps.setString(8, event.priorityClass());
            ps.setString(9, event.responseMode());
            ps.setString(10, event.deliveryAcknowledgementMode());
            ps.setString(11, event.convergenceMode());
            ps.setString(12, event.targetScope());
            ps.executeUpdate();
        }
    }

    private void upsertProject(Connection conn, CatalogProjectRecord project) throws Exception {
        try (var ps = conn.prepareStatement(dialect.catalogProjectUpsertSql())) {
            ps.setString(1, project.code());
            ps.setString(2, project.tenantId());
            ps.setString(3, project.name());
            ps.setString(4, project.description());
            ps.setBoolean(5, project.enabled());
            ps.setString(6, project.ownerPrincipalId());
            ps.executeUpdate();
        }
    }

    private void deleteBindingsForEvent(Connection conn, String eventCode) throws Exception {
        try (var ps = conn.prepareStatement("DELETE FROM xa_catalog_project_event WHERE event_code = ?")) {
            ps.setString(1, eventCode);
            ps.executeUpdate();
        }
    }

    private void deleteBindingsForProject(Connection conn, String projectCode) throws Exception {
        try (var ps = conn.prepareStatement("DELETE FROM xa_catalog_project_event WHERE project_code = ?")) {
            ps.setString(1, projectCode);
            ps.executeUpdate();
        }
    }

    private void insertBinding(Connection conn, String projectCode, String eventCode) throws Exception {
        if (projectCode == null || eventCode == null) {
            return;
        }
        try (var ps = conn.prepareStatement("""
                INSERT INTO xa_catalog_project_event(project_code, event_code)
                SELECT ?, ?
                WHERE NOT EXISTS (
                  SELECT 1 FROM xa_catalog_project_event WHERE project_code = ? AND event_code = ?
                )
                """)) {
            ps.setString(1, projectCode);
            ps.setString(2, eventCode);
            ps.setString(3, projectCode);
            ps.setString(4, eventCode);
            ps.executeUpdate();
        }
    }

    private CatalogEventRecord readEvent(Connection conn, ResultSet rs) throws Exception {
        String eventCode = rs.getString("event_code");
        return new CatalogEventRecord(
                eventCode,
                rs.getString("name"),
                rs.getString("description"),
                readStringList(rs.getString("payload_types_json")),
                readStringList(rs.getString("task_modes_json")),
                rs.getBoolean("enabled"),
                rs.getString("default_routing_code"),
                projectCodesForEvent(conn, eventCode),
                rs.getString("priority_class"),
                rs.getString("response_mode"),
                rs.getString("delivery_acknowledgement_mode"),
                rs.getString("convergence_mode"),
                rs.getString("target_scope")
        );
    }

    private CatalogProjectRecord readProject(Connection conn, ResultSet rs) throws Exception {
        String projectCode = rs.getString("project_code");
        return new CatalogProjectRecord(
                rs.getString("tenant_id"),
                projectCode,
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getString("owner_principal_id"),
                eventCodesForProject(conn, projectCode)
        );
    }

    private List<String> projectCodesForEvent(Connection conn, String eventCode) throws Exception {
        return queryCodes(conn,
                "SELECT project_code FROM xa_catalog_project_event WHERE event_code = ? ORDER BY project_code",
                eventCode);
    }

    private List<String> eventCodesForProject(Connection conn, String projectCode) throws Exception {
        return queryCodes(conn,
                "SELECT event_code FROM xa_catalog_project_event WHERE project_code = ? ORDER BY event_code",
                projectCode);
    }

    private List<String> queryCodes(Connection conn, String sql, String value) throws Exception {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
            return result;
        }
    }

    private List<String> existingCodes(Connection conn, String table, String column) throws Exception {
        try (var ps = conn.prepareStatement("SELECT " + column + " FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            List<String> result = new ArrayList<>();
            while (rs.next()) {
                result.add(rs.getString(1));
            }
            return result;
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = mapper.readValue(json, STRING_LIST);
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read catalog string list", e);
        }
    }

    private static List<CatalogEventRecord> normalizeEvents(Collection<CatalogEventRecord> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().toList();
    }

    private static List<CatalogProjectRecord> normalizeProjects(Collection<CatalogProjectRecord> projects) {
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        return projects.stream().toList();
    }
}
