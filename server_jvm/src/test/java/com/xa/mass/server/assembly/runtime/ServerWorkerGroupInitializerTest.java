package com.xa.mass.server.assembly.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ServerWorkerGroupInitializerTest {

    @Test
    void registersEveryDeclaredGroupWithItsTaskCallInDeclarationOrder() {
        WorkerGroupRegistrationService registrations = mock(
                WorkerGroupRegistrationService.class
        );
        ServerWorkerGroupInitializer initializer =
                new ServerWorkerGroupInitializer(
                        ServerWorkerAssemblyManifest.fromJson("""
                        {
                          "phone-group": {
                            "attributes":{"capability":"phone"},
                            "eventCodes":["phone.lookup"]
                          },
                          "string-group": {
                            "eventCodes":["string.hash"]
                          }
                        }
                        """),
                        registrations
                );

        initializer.initialize();
        initializer.initialize();

        InOrder order = inOrder(registrations);
        order.verify(registrations).register(
                "phone-group",
                Map.of("capability", "phone"),
                List.of("phone.lookup")
        );
        order.verify(registrations).register(
                "string-group",
                Map.of(),
                List.of("string.hash")
        );
        verify(registrations, times(2)).register(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> ServerWorkerAssemblyManifest.fromJson(
                """
                        {"group":{"eventCodes":[],"workers":[]}}
                        """
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field workers");
        assertThatThrownBy(() -> ServerWorkerAssemblyManifest.fromJson(
                """
                        {"group":{"eventCodes":["a","a"]}}
                        """
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void aRegistrationFailureDoesNotMarkInitializationComplete() {
        WorkerGroupRegistrationService registrations = mock(
                WorkerGroupRegistrationService.class
        );
        RuntimeException failure = new RuntimeException(
                "Task Call registration unavailable"
        );
        when(registrations.register(
                "group",
                Map.of(),
                List.of()
        )).thenThrow(failure).thenReturn(
                new WorkerGroupRegistrationService.Registration(
                        "group",
                        "registered"
                )
        );
        ServerWorkerGroupInitializer initializer =
                new ServerWorkerGroupInitializer(
                        ServerWorkerAssemblyManifest.fromJson(
                                "{\"group\":{\"eventCodes\":[]}}"
                        ),
                        registrations
                );

        assertThatThrownBy(initializer::initialize).isSameAs(failure);
        initializer.initialize();

        verify(registrations, times(2)).register(
                "group",
                Map.of(),
                List.of()
        );
    }

    @Test
    void keepsRuntimeTruthWhenSeedConflictsWithAlreadyModifiedGroup() {
        WorkerGroupRegistrationService registrations = mock(
                WorkerGroupRegistrationService.class
        );
        when(registrations.register("seeded", Map.of(), List.of("ev.old")))
                .thenThrow(new com.xa.mass.server.error.ServerException(
                        com.xa.mass.server.error.ServerErrorCode.WORKER_GROUP_REGISTRATION_CONFLICT,
                        "workerGroup.register",
                        "already registered with a different descriptor",
                        null
                ));
        ServerWorkerGroupInitializer initializer = new ServerWorkerGroupInitializer(
                ServerWorkerAssemblyManifest.fromJson("""
                        {
                          "seeded": {"eventCodes": ["ev.old"]},
                          "fresh": {"eventCodes": ["ev.new"]}
                        }
                        """),
                registrations
        );

        initializer.initialize();

        verify(registrations).register("seeded", Map.of(), List.of("ev.old"));
        verify(registrations).register("fresh", Map.of(), List.of("ev.new"));
    }
}
