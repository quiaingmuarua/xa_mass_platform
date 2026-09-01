package com.xa.mass.server.assembly.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

class ServerConfiguredRuntimeLifecycleHostTest {

    @Test
    void initializesGroupsBeforeAdapterAndClosesAdapterOnce() {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ServerConfiguredRuntimeLifecycleHost host =
                new ServerConfiguredRuntimeLifecycleHost(
                        groupInitializer,
                        adapterManager
                );

        host.start();
        host.start();
        host.stop();
        host.stop();

        InOrder order = inOrder(groupInitializer, adapterManager);
        order.verify(groupInitializer).initialize();
        order.verify(adapterManager).start();
        order.verify(adapterManager).close();
        verify(groupInitializer, times(1)).initialize();
        verify(adapterManager, times(1)).start();
        verify(adapterManager, times(1)).close();
        assertThat(host.isRunning()).isFalse();
        assertThat(host.getPhase()).isEqualTo(
                WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1
        );
    }

    @Test
    void adapterFailureClosesAdapterBeforeRethrow() {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        RuntimeException failure = new RuntimeException("adapter failed");
        doThrow(failure).when(adapterManager).start();
        ServerConfiguredRuntimeLifecycleHost host =
                new ServerConfiguredRuntimeLifecycleHost(
                        groupInitializer,
                        adapterManager
                );

        assertThatThrownBy(host::start).isSameAs(failure);

        InOrder order = inOrder(groupInitializer, adapterManager);
        order.verify(groupInitializer).initialize();
        order.verify(adapterManager).start();
        order.verify(adapterManager).close();
    }

    @Test
    void groupOrTaskCallInitializationFailurePreventsAdapterStartup() {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        RuntimeException failure = new RuntimeException(
                "group Task Call registration failed"
        );
        doThrow(failure).when(groupInitializer).initialize();
        ServerConfiguredRuntimeLifecycleHost host =
                new ServerConfiguredRuntimeLifecycleHost(
                        groupInitializer,
                        adapterManager
                );

        assertThatThrownBy(host::start).isSameAs(failure);

        verify(adapterManager, never()).start();
        verify(adapterManager, never()).close();
    }
}
