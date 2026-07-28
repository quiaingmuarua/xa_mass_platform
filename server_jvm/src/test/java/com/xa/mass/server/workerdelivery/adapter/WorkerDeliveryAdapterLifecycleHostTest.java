package com.xa.mass.server.workerdelivery.adapter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterLifecycleHostTest {

    @Test
    void delegatesHostLifecycleWithoutOwningTheDispatchLoop() {
        WorkerDeliveryAdapterManager manager = mock(
                WorkerDeliveryAdapterManager.class
        );
        WorkerDeliveryAdapterLifecycleHost host =
                new WorkerDeliveryAdapterLifecycleHost(manager);

        host.start();
        host.stop();

        verify(manager).start();
        verify(manager).close();
    }
}
