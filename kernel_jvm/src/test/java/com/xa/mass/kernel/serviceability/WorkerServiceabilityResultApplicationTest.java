package com.xa.mass.kernel.serviceability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerServiceabilityResultApplicationTest {

    @Test
    void isolatesRoundFailureAndStopsPromptly() {
        AtomicInteger invocations = new AtomicInteger();
        WorkerServiceabilityRuntime runtime = new WorkerServiceabilityRuntime() {
            @Override
            public Map<String, ProbeRequestOfferStatus> offerProbeRequests(
                    String adapterId,
                    List<String> workerIds
            ) {
                throw new AssertionError("offer is not used");
            }

            @Override
            public List<String> consumeProbeRequests(
                    String adapterId,
                    int limit
            ) {
                throw new AssertionError("probe consume is not used");
            }

            @Override
            public int appendAdapterEvidenceResults(
                    List<DeliveryReport> reports
            ) {
                throw new AssertionError("append is not used");
            }

            @Override
            public List<DeliveryReport> consumeAdapterEvidenceResults(
                    int limit
            ) {
                if (invocations.getAndIncrement() == 0) {
                    throw new IllegalStateException("transient");
                }
                return List.of();
            }
        };
        WorkerServiceabilityResultPacer pacer =
                new WorkerServiceabilityResultPacer(
                        runtime,
                        unused(WorkerResourceCatalog.class),
                        unused(WorkerScoreCore.class)
                );
        WorkerServiceabilityResultApplication application =
                new WorkerServiceabilityResultApplication(pacer);
        WorkerServiceabilityResultApplicationConfig config =
                new WorkerServiceabilityResultApplicationConfig(
                        5,
                        WorkerServiceabilityResultConfig.defaults()
                );

        application.start(config, 1_000);
        assertThrows(
                IllegalStateException.class,
                () -> application.start(config, 1_000)
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

    @Test
    void invalidHotFloorCannotStartTheApplication() {
        WorkerServiceabilityResultApplication application =
                new WorkerServiceabilityResultApplication(
                        new WorkerServiceabilityResultPacer(
                                unused(WorkerServiceabilityRuntime.class),
                                unused(WorkerResourceCatalog.class),
                                unused(WorkerScoreCore.class)
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> application.start(
                        WorkerServiceabilityResultApplicationConfig.defaults(),
                        0
                )
        );
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
