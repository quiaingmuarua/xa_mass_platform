package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResultRoutingApplicationTest {

    @Test
    void isolatesRoundFailureAndStopsPromptly() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        TaskResultRuntime runtime = new TaskResultRuntime() {
            @Override
            public int appendTaskResults(
                    TaskResultClass resultClass,
                    List<DeliveryReport> results
            ) {
                return 0;
            }

            @Override
            public List<DeliveryReport> consumeTaskResults(
                    TaskResultClass resultClass,
                    int limit
            ) {
                if (invocations.getAndIncrement() == 0) {
                    throw new IllegalStateException("transient");
                }
                return List.of();
            }
        };
        ResultRoutingPacer pacer = new ResultRoutingPacer(
                runtime,
                unused(TaskRuntime.class),
                unused(TaskItemScoreBandCore.class),
                unused(WorkerScoreCore.class)
        );
        ResultRoutingApplication application =
                new ResultRoutingApplication(pacer);
        ResultRoutingApplicationConfig config =
                new ResultRoutingApplicationConfig(5);

        application.start(config);
        assertThrows(
                IllegalStateException.class,
                () -> application.start(config)
        );
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (invocations.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(invocations.get() >= 2);
        assertTrue(application.isRunning());

        application.stop(1_000);
        application.stop(1_000);
        assertFalse(application.isRunning());
        assertTrue(application.state().equals("STOPPED"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> contract) {
        return (T) Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                (_proxy, method, _args) -> {
                    throw new AssertionError(
                            "Unexpected owner call: " + method.getName()
                    );
                }
        );
    }
}
