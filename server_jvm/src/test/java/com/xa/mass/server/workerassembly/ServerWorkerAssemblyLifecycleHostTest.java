package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ServerWorkerAssemblyLifecycleHostTest {

    @Test
    void initializesGroupsBeforeAdapterAndScenarioThenClosesInReverseOrder()
            throws Exception {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ScenarioWorkers scenarioWorkers = mock(
                ScenarioWorkers.class
        );
        ServerWorkerAssemblyLifecycleHost host =
                new ServerWorkerAssemblyLifecycleHost(
                        groupInitializer,
                        adapterManager,
                        scenarioWorkers
                );

        host.start();
        host.start();
        host.destroy();
        host.destroy();

        InOrder order = inOrder(
                groupInitializer,
                adapterManager,
                scenarioWorkers
        );
        order.verify(groupInitializer).initialize();
        order.verify(adapterManager).start();
        order.verify(scenarioWorkers).start();
        order.verify(scenarioWorkers).close();
        order.verify(adapterManager).close();
        verify(adapterManager, times(1)).start();
        verify(scenarioWorkers, times(1)).start();
        verify(groupInitializer, times(1)).initialize();
    }

    @Test
    void bundleFailureClosesBundlesAndAdapterBeforeRethrow() {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ScenarioWorkers scenarioWorkers = mock(
                ScenarioWorkers.class
        );
        RuntimeException failure = new RuntimeException("bundle failed");
        doThrow(failure).when(scenarioWorkers).start();
        ServerWorkerAssemblyLifecycleHost host =
                new ServerWorkerAssemblyLifecycleHost(
                        groupInitializer,
                        adapterManager,
                        scenarioWorkers
                );

        assertThatThrownBy(host::start).isSameAs(failure);

        InOrder order = inOrder(
                groupInitializer,
                adapterManager,
                scenarioWorkers
        );
        order.verify(groupInitializer).initialize();
        order.verify(adapterManager).start();
        order.verify(scenarioWorkers).start();
        order.verify(scenarioWorkers).close();
        order.verify(adapterManager).close();
    }

    @Test
    void groupInitializationFailurePreventsAdapterAndScenarioStartup() {
        ServerWorkerGroupInitializer groupInitializer = mock(
                ServerWorkerGroupInitializer.class
        );
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ScenarioWorkers scenarioWorkers = mock(ScenarioWorkers.class);
        RuntimeException failure = new RuntimeException("group failed");
        doThrow(failure).when(groupInitializer).initialize();
        ServerWorkerAssemblyLifecycleHost host =
                new ServerWorkerAssemblyLifecycleHost(
                        groupInitializer,
                        adapterManager,
                        scenarioWorkers
                );

        assertThatThrownBy(host::start).isSameAs(failure);

        verify(adapterManager, never()).start();
        verify(scenarioWorkers, never()).start();
        verify(adapterManager, never()).close();
        verify(scenarioWorkers, never()).close();
    }
}
