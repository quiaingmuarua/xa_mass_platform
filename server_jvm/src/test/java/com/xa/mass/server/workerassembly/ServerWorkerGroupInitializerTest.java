package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ServerWorkerGroupInitializerTest {

    @Test
    void initializesCatalogInDeclarationOrderAndIsIdempotent() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.upsertWorkerGroup(any()))
                .thenReturn(result(WorkerRuntimeStatus.OK))
                .thenReturn(result(WorkerRuntimeStatus.NOOP));
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
                        catalog
                );

        initializer.initialize();
        initializer.initialize();

        ArgumentCaptor<WorkerGroupDescriptor> descriptor =
                ArgumentCaptor.forClass(WorkerGroupDescriptor.class);
        verify(catalog, times(2)).upsertWorkerGroup(
                descriptor.capture()
        );
        assertThat(descriptor.getAllValues())
                .extracting(WorkerGroupDescriptor::workerGroupId)
                .containsExactly("phone-group", "string-group");
        assertThat(descriptor.getAllValues().get(0).attributes())
                .containsEntry("capability", "phone");
        assertThat(descriptor.getAllValues().get(1).attributes()).isEmpty();
        InOrder order = inOrder(catalog);
        order.verify(catalog).upsertWorkerGroup(
                descriptor.getAllValues().get(0)
        );
        order.verify(catalog).upsertWorkerGroup(
                descriptor.getAllValues().get(1)
        );
    }

    @Test
    void rejectsInvalidConfigurationAndOwnerFailure() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
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

        when(catalog.upsertWorkerGroup(any())).thenReturn(
                new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "stored descriptor is invalid"
                )
        );
        ServerWorkerGroupInitializer initializer =
                new ServerWorkerGroupInitializer(
                        ServerWorkerAssemblyManifest.fromJson(
                                "{\"group\":{\"eventCodes\":[]}}"
                        ),
                        catalog
                );

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group")
                .hasMessageContaining("invalid")
                .hasMessageContaining("stored descriptor is invalid");
    }

    private static WorkerRuntimeResult result(
            WorkerRuntimeStatus status
    ) {
        return new WorkerRuntimeResult(status);
    }
}
