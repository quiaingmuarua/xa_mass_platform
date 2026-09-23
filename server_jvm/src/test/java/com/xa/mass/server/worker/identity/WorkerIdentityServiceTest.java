package com.xa.mass.server.worker.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerIdentityServiceTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String CLIENT_REGISTRATION_KEY =
            "client-key:14:installation-1";
    private WorkerIdentityRegistry registry;
    private WorkerIdentityService service;

    @BeforeEach
    void setUp() {
        registry = mock(WorkerIdentityRegistry.class);
        service = new WorkerIdentityService(registry);
    }

    @Test
    void oneBatchPreservesIdentityOrderAndValidatesStoredUuids() {
        when(registry.registerAll("group-1", List.of(CLIENT_REGISTRATION_KEY)))
                .thenReturn(List.of(WORKER_ID));
        assertThat(service.registerAll("group-1", List.of(CLIENT_REGISTRATION_KEY))).containsExactly(WORKER_ID);
        verify(registry).registerAll("group-1", List.of(CLIENT_REGISTRATION_KEY));
        when(registry.registerAll("group-1", List.of(CLIENT_REGISTRATION_KEY)))
                .thenReturn(List.of("not-a-uuid"));
        assertThatThrownBy(() -> service.registerAll("group-1", List.of(CLIENT_REGISTRATION_KEY)))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ServerErrorCode.WORKER_IDENTITY_CONFLICT));
    }

    @Test
    void malformedBatchesHaveNoIdentityWrites() {
        assertThatThrownBy(() -> service.registerAll("group-1", List.of())).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registerAll("group-1", List.of("same", "same"))).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registerAll("", List.of("key"))).isInstanceOf(ServerException.class);
        org.mockito.Mockito.verifyNoInteractions(registry);
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
                "labInventoryLine", "7",
                "labSlot", "1"
        );
        Map<String, Object> changed = Map.of(
                "labInventoryKey", "workers-a.jsonl",
                "labInventoryLine", "7",
                "labSlot", "9"
        );
        String key = service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                first
        );
        assertThat(key).isEqualTo("scenario-lab:15:workers-a.jsonl:7");
        assertThat(service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                changed
        )).isEqualTo(key);

    }

    @Test
    void scenarioLabIdentityRejectsMissingOrInvalidLineCoordinates() {
        for (Object invalid : List.of(1, 1L, "0", "-1", "101", "1.0", "true", " ", "999999999999")) {
            assertThatThrownBy(() -> service.registrationKey(
                    WorkerRegistrationKind.SCENARIO_LAB,
                    Map.of("labInventoryKey", "workers-a.jsonl", "labInventoryLine", invalid)
            )).isInstanceOf(ServerException.class);
        }
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of("labInventoryKey", "workers-a.jsonl")
        )).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of(
                        "labInventoryKey", "workers-a.jsonl",
                        "labInventoryLine", "101"
                )
        )).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                Map.of(
                        "labInventoryKey", "workers-a.jsonl",
                        "labInventoryLine", "1",
                        "clientWorkerKey", "must-not-be-used"
                )
        )).isInstanceOf(ServerException.class);
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
