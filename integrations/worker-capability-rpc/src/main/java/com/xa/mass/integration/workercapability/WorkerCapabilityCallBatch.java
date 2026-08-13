package com.xa.mass.integration.workercapability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

final class WorkerCapabilityCallBatch {

    private WorkerCapabilityCallBatch() {
    }

    static List<String> invoke(List<Callable<String>> calls) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(calls);
            List<String> results = new ArrayList<>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RPC call batch was interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("RPC call batch failed", cause);
        }
    }
}
