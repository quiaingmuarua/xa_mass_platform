package com.xa.mass.server.worker.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerIdentityServiceTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String CLIENT_REGISTRATION_KEY =
            "client-key:14:installation-1";
    private WorkerIdentityRegistry registry;
    private WorkerResourceCatalog catalog;
    private WorkerIdentityService service;

    @BeforeEach
    void setUp() {
        registry = mock(WorkerIdentityRegistry.class);
        catalog = mock(WorkerResourceCatalog.class);
        service = new WorkerIdentityService(registry, catalog);
    }

    @Test
    void registersOnlyInsideAnExistingWorkerGroup() {
        when(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .thenReturn(Map.of("group-1", group("group-1")));
        when(registry.register(
                "group-1",
                CLIENT_REGISTRATION_KEY
        ))
                .thenReturn(WORKER_ID);

        assertThat(service.register(
                "group-1",
                properties("installation-1", 1)
        )).isEqualTo(WORKER_ID);
        assertThat(service.register(
                "group-1",
                properties("installation-1", 2)
        )).isEqualTo(WORKER_ID);
        verify(registry, times(2)).register(
                "group-1",
                CLIENT_REGISTRATION_KEY
        );
    }

    @Test
    void distinguishesMissingGroupAndInvalidStoredIdentity() {
        var missingGroup = new LinkedHashMap<
                String,
                WorkerGroupDescriptor
                >();
        missingGroup.put("missing", null);
        when(catalog.getWorkerGroupDescriptors(List.of("missing")))
                .thenReturn(missingGroup);
        assertThatThrownBy(() -> service.register(
                "missing",
                properties("installation-1", 1)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ServerErrorCode.WORKER_GROUP_NOT_FOUND));

        when(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .thenReturn(Map.of("group-1", group("group-1")));
        when(registry.register(
                "group-1",
                CLIENT_REGISTRATION_KEY
        ))
                .thenReturn("not-a-uuid");
        assertThatThrownBy(() -> service.register(
                "group-1",
                properties("installation-1", 2)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ServerErrorCode.WORKER_IDENTITY_CONFLICT));
    }

    @Test
    void verifiesTheCompleteIdentityCoordinate() {
        when(registry.matches(
                "group-1",
                CLIENT_REGISTRATION_KEY,
                WORKER_ID
        ))
                .thenReturn(true);
        service.requireRegistration(
                "group-1",
                properties("installation-1", 1),
                WORKER_ID
        );

        when(registry.matches(
                "group-1",
                "client-key:5:other",
                WORKER_ID
        ))
                .thenReturn(false);
        assertThatThrownBy(() -> service.requireRegistration(
                "group-1",
                properties("other", 1),
                WORKER_ID
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ServerErrorCode.WORKER_IDENTITY_NOT_FOUND));

        assertThatThrownBy(() -> service.requireRegistration(
                "group-1",
                properties("installation-1", 2),
                "not-a-uuid"
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST
                ));
    }

    @Test
    void clientKeyRegistrationUsesTheClientWorkerKeyProperty() {
        Map<String, Object> properties = properties("installation-1", 1);

        assertThat(service.registrationKey(
                WorkerRegistrationKind.CLIENT_KEY,
                properties
        )).isEqualTo(CLIENT_REGISTRATION_KEY);
    }

    @Test
    void scenarioLabIdentityUsesImmutableInventoryCoordinates() {
        Map<String, Object> first = Map.of(
                "labInventoryKey", "workers-a.jsonl",
                "labInventoryLine", 7L,
                "labSlot", 1L
        );
        Map<String, Object> changed = Map.of(
                "labInventoryKey", "workers-a.jsonl",
                "labInventoryLine", 7L,
                "labSlot", 9L
        );
        String key = service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                first
        );
        assertThat(service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                changed
        )).isEqualTo(key);

        when(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .thenReturn(Map.of("group-1", group("group-1")));
        when(registry.register(
                "group-1",
                key
        )).thenReturn(WORKER_ID);
        assertThat(service.register(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                changed
        )).isEqualTo(WORKER_ID);
        verify(registry).register(
                "group-1",
                key
        );
    }

    @Test
    void scenarioLabIdentityRejectsMissingOrInvalidLineCoordinates() {
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of("labInventoryKey", "workers-a.jsonl")
        )).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of(
                        "labInventoryKey", "workers-a.jsonl",
                        "labInventoryLine", 101L
                )
        )).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of(
                        "labInventoryKey", "workers-a.jsonl",
                        "labInventoryLine", 1L,
                        "clientWorkerKey", "must-not-be-used"
                )
        )).isInstanceOf(ServerException.class);
    }

    private static WorkerGroupDescriptor group(String workerGroupId) {
        return new WorkerGroupDescriptor(
                workerGroupId,
                Map.of(),
                Set.of("event")
        );
    }

    private static Map<String, Object> properties(
            String clientWorkerKey,
            int version
    ) {
        return Map.of(
                "clientWorkerKey",
                clientWorkerKey,
                "version",
                version
        );
    }
}
