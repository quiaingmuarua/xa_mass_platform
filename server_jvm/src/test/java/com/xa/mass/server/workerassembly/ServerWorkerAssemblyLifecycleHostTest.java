package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ServerWorkerAssemblyLifecycleHostTest {

    @Test
    void startsAdapterBeforeBundlesAndClosesInReverseOrder()
            throws Exception {
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ServerWorkerBundleManager bundleManager = mock(
                ServerWorkerBundleManager.class
        );
        ServerWorkerAssemblyLifecycleHost host =
                new ServerWorkerAssemblyLifecycleHost(
                        adapterManager,
                        bundleManager
                );

        host.start();
        host.start();
        host.destroy();
        host.destroy();

        InOrder order = inOrder(adapterManager, bundleManager);
        order.verify(adapterManager).start();
        order.verify(bundleManager).start();
        order.verify(bundleManager).close();
        order.verify(adapterManager).close();
        verify(adapterManager, times(1)).start();
        verify(bundleManager, times(1)).start();
    }

    @Test
    void bundleFailureClosesBundlesAndAdapterBeforeRethrow() {
        WorkerDeliveryAdapterManager adapterManager = mock(
                WorkerDeliveryAdapterManager.class
        );
        ServerWorkerBundleManager bundleManager = mock(
                ServerWorkerBundleManager.class
        );
        RuntimeException failure = new RuntimeException("bundle failed");
        doThrow(failure).when(bundleManager).start();
        ServerWorkerAssemblyLifecycleHost host =
                new ServerWorkerAssemblyLifecycleHost(
                        adapterManager,
                        bundleManager
                );

        assertThatThrownBy(host::start).isSameAs(failure);

        InOrder order = inOrder(adapterManager, bundleManager);
        order.verify(adapterManager).start();
        order.verify(bundleManager).start();
        order.verify(bundleManager).close();
        order.verify(adapterManager).close();
    }
}
