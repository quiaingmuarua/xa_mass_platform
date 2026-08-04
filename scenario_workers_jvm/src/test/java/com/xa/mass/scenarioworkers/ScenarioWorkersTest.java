package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ScenarioWorkersTest {

    @Test
    void fromJsonIsInert() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        WorkerPropertyIndexRuntime index = mock(
                WorkerPropertyIndexRuntime.class
        );

        ScenarioWorkers workers = ScenarioWorkers.fromJson(
                """
                        {
                          "phone": {
                            "type":"PHONE_NUMBER",
                            "endpointManagerId":"adapter",
                            "websocketUri":"ws://127.0.0.1:18083/connect",
                            "workerGroupId":"group",
                            "workers":[{"workerId":"worker-1"}]
                          }
                        }
                        """,
                catalog,
                runtime,
                index
        );

        verifyNoInteractions(catalog, runtime, index);
        workers.close();
    }

    @Test
    void startsInOrderAndClosesInReverseOrder() {
        ScenarioWorkerBundleLifecycle first = mock(
                ScenarioWorkerBundleLifecycle.class
        );
        ScenarioWorkerBundleLifecycle second = mock(
                ScenarioWorkerBundleLifecycle.class
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(first, second)
        );

        workers.start();
        workers.start();
        workers.close();
        workers.close();

        InOrder order = inOrder(first, second);
        order.verify(first).start();
        order.verify(second).start();
        order.verify(second).close();
        order.verify(first).close();
        verify(first, times(1)).start();
        verify(second, times(1)).start();
    }

    @Test
    void startupFailureClosesStartingAndStartedBundles() {
        ScenarioWorkerBundleLifecycle first = mock(
                ScenarioWorkerBundleLifecycle.class
        );
        ScenarioWorkerBundleLifecycle second = mock(
                ScenarioWorkerBundleLifecycle.class
        );
        RuntimeException failure = new RuntimeException("start failed");
        doThrow(failure).when(second).start();
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(first, second)
        );

        assertThatThrownBy(workers::start).isSameAs(failure);

        InOrder order = inOrder(first, second);
        order.verify(first).start();
        order.verify(second).start();
        order.verify(second).close();
        order.verify(first).close();
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class);
    }
}
