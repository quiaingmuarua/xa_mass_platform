package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class JavaWorkerHostResourcesTest {

    @Test
    void validatesStaticReplicaSizingInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerHostResources.create(
                        0,
                        "workers",
                        false
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerHostResources.create(
                        1,
                        " ",
                        false
                )
        );
    }

    @Test
    void createsOneBoundedNamedBundleAndRejectsAfterClose()
            throws Exception {
        JavaWorkerHostResources resources =
                JavaWorkerHostResources.create(
                        8,
                        "test-java-worker",
                        true
                );
        ExecutorService control = executor(resources, "controlExecutor");
        ExecutorService handler = executor(resources, "handlerExecutor");
        ExecutorService retry = executor(resources, "retryScheduler");

        assertEquals(4, ((ThreadPoolExecutor) control).getCorePoolSize());
        assertEquals(
                Math.min(
                        8,
                        Math.max(
                                2,
                                Runtime.getRuntime().availableProcessors()
                        )
                ),
                ((ThreadPoolExecutor) handler).getCorePoolSize()
        );
        assertThread(control, "test-java-worker-control-");
        assertThread(handler, "test-java-worker-handler-");
        assertThread(retry, "test-java-worker-retry-");

        resources.close();
        resources.close();

        assertTrue(control.isShutdown());
        assertTrue(handler.isShutdown());
        assertTrue(retry.isShutdown());
        assertThrows(
                RejectedExecutionException.class,
                () -> control.execute(() -> {
                })
        );
    }

    private static ExecutorService executor(
            JavaWorkerHostResources resources,
            String fieldName
    ) throws Exception {
        Field field = JavaWorkerHostResources.class.getDeclaredField(
                fieldName
        );
        field.setAccessible(true);
        return (ExecutorService) field.get(resources);
    }

    private static void assertThread(
            ExecutorService executor,
            String expectedPrefix
    ) throws Exception {
        Future<Thread> future = executor.submit(Thread::currentThread);
        Thread thread = future.get();
        assertTrue(thread.getName().startsWith(expectedPrefix));
        assertTrue(thread.isDaemon());
    }
}
