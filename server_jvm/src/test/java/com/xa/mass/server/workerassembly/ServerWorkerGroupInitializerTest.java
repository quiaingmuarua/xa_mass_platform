package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.workergroup.WorkerGroupRegistrationService;
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
}
