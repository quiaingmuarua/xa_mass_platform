package com.xa.mass.server.delivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.workerdelivery.adapter.application.WorkerRouteVerifier.Decision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerRouteVerificationBatcherTest {

    private final List<WorkerRouteVerificationBatcher> batchers =
            new ArrayList<>();

    @AfterEach
    void closeBatchers() {
        batchers.forEach(WorkerRouteVerificationBatcher::close);
    }

    @Test
    void submitsOneRequestImmediatelyOnTheResidentVirtualThread()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        when(bindings.currentEndpointManagerIdsAsync(List.of("worker-1")))
                .thenAnswer(ignored -> {
                    ownerThread.set(Thread.currentThread());
                    called.countDown();
                    return CompletableFuture.completedFuture(Map.of(
                            "worker-1",
                            "adapter-1"
                    ));
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                10,
                Duration.ofSeconds(1)
        );
        batcher.start();

        CompletableFuture<Decision> result = batcher.verify(
                "adapter-1",
                "worker-1"
        );

        assertThat(called.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.VERIFIED
        );
        assertThat(ownerThread.get().isVirtual()).isTrue();
        assertThat(ownerThread.get().getName()).isEqualTo(
                "worker-route-verification"
        );
        verify(bindings).currentEndpointManagerIdsAsync(List.of("worker-1"));
    }

    @Test
    void drainsTwoHundredAndFiveQueuedRequestsInBoundedBatches()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        CompletableFuture<Map<String, String>> firstLookup =
                new CompletableFuture<>();
        CountDownLatch firstCalled = new CountDownLatch(1);
        List<List<String>> ownerBatches = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(bindings.currentEndpointManagerIdsAsync(anyList()))
                .thenAnswer(invocation -> {
                    List<String> workerIds = List.copyOf(
                            invocation.getArgument(0)
                    );
                    ownerBatches.add(workerIds);
                    if (calls.getAndIncrement() == 0) {
                        firstCalled.countDown();
                        return firstLookup;
                    }
                    LinkedHashMap<String, String> result =
                            new LinkedHashMap<>();
                    workerIds.forEach(workerId -> result.put(
                            workerId,
                            "adapter-1"
                    ));
                    return CompletableFuture.completedFuture(result);
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                300,
                Duration.ofSeconds(2)
        );
        batcher.start();
        CompletableFuture<Decision> warmup = batcher.verify(
                "adapter-1",
                "warmup"
        );
        assertThat(firstCalled.await(1, TimeUnit.SECONDS)).isTrue();

        List<CompletableFuture<Decision>> results = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            results.add(batcher.verify(
                    "adapter-1",
                    "worker-" + index
            ));
        }
        firstLookup.complete(Map.of("warmup", "adapter-1"));

        assertThat(warmup.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.VERIFIED
        );
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
                .get(2, TimeUnit.SECONDS);
        assertThat(results).allSatisfy(result ->
                assertThat(result.join()).isEqualTo(Decision.VERIFIED)
        );
        assertThat(ownerBatches.stream().skip(1).map(List::size).toList())
                .containsExactly(100, 100, 5);
        assertThat(ownerBatches.stream()
                .skip(1)
                .flatMap(List::stream)
                .distinct()
                .count()).isEqualTo(205);
    }

    @Test
    void deduplicatesBindingReadsButCompletesEachAdapterDecision()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        CompletableFuture<Map<String, String>> firstLookup =
                new CompletableFuture<>();
        CountDownLatch firstCalled = new CountDownLatch(1);
        List<List<String>> ownerBatches = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(bindings.currentEndpointManagerIdsAsync(anyList()))
                .thenAnswer(invocation -> {
                    List<String> ids = List.copyOf(invocation.getArgument(0));
                    ownerBatches.add(ids);
                    if (calls.getAndIncrement() == 0) {
                        firstCalled.countDown();
                        return firstLookup;
                    }
                    return CompletableFuture.completedFuture(Map.of(
                            "worker-1",
                            "adapter-a"
                    ));
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                10,
                Duration.ofSeconds(1)
        );
        batcher.start();
        CompletableFuture<Decision> warmup = batcher.verify(
                "adapter-a",
                "warmup"
        );
        assertThat(firstCalled.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Decision> accepted = batcher.verify(
                "adapter-a",
                "worker-1"
        );
        CompletableFuture<Decision> rejected = batcher.verify(
                "adapter-b",
                "worker-1"
        );
        CompletableFuture<Decision> missing = batcher.verify(
                "adapter-a",
                "worker-missing"
        );
        firstLookup.complete(Map.of("warmup", "adapter-a"));

        assertThat(warmup.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.VERIFIED
        );
        assertThat(accepted.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.VERIFIED
        );
        assertThat(rejected.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.REJECTED
        );
        assertThat(missing.get(1, TimeUnit.SECONDS)).isEqualTo(
                Decision.REJECTED
        );
        assertThat(ownerBatches).containsExactly(
                List.of("warmup"),
                List.of("worker-1", "worker-missing")
        );
    }

    @Test
    void queueFullAndCloseCompleteEveryRequestExceptionally()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        CompletableFuture<Map<String, String>> blocked =
                new CompletableFuture<>();
        CountDownLatch called = new CountDownLatch(1);
        when(bindings.currentEndpointManagerIdsAsync(anyList()))
                .thenAnswer(ignored -> {
                    called.countDown();
                    return blocked;
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                1,
                Duration.ofSeconds(1)
        );
        batcher.start();
        CompletableFuture<Decision> active = batcher.verify(
                "adapter-1",
                "worker-1"
        );
        assertThat(called.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Decision> queued = batcher.verify(
                "adapter-1",
                "worker-2"
        );
        CompletableFuture<Decision> full = batcher.verify(
                "adapter-1",
                "worker-3"
        );

        assertThatThrownBy(full::join).hasCauseInstanceOf(
                IllegalStateException.class
        );
        batcher.close();
        assertThatThrownBy(active::join).hasCauseInstanceOf(
                IllegalStateException.class
        );
        assertThatThrownBy(queued::join).hasCauseInstanceOf(
                IllegalStateException.class
        );
        assertThatThrownBy(() -> batcher.verify(
                "adapter-1",
                "worker-4"
        ).join()).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutFailsCurrentBacklogWithoutBlockingLaterRequests()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        CountDownLatch firstCalled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(bindings.currentEndpointManagerIdsAsync(anyList()))
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        firstCalled.countDown();
                        return new CompletableFuture<Map<String, String>>();
                    }
                    List<String> ids = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(Map.of(
                            ids.get(0),
                            "adapter-1"
                    ));
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                10,
                Duration.ofMillis(50)
        );
        batcher.start();
        CompletableFuture<Decision> active = batcher.verify(
                "adapter-1",
                "worker-1"
        );
        assertThat(firstCalled.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Decision> queued = batcher.verify(
                "adapter-1",
                "worker-2"
        );

        assertThatThrownBy(active::join).hasCauseInstanceOf(
                java.util.concurrent.TimeoutException.class
        );
        assertThatThrownBy(queued::join).hasCauseInstanceOf(
                java.util.concurrent.TimeoutException.class
        );
        assertThat(batcher.verify("adapter-1", "worker-3")
                .get(1, TimeUnit.SECONDS)).isEqualTo(Decision.VERIFIED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void ownerFailureIsNotRetriedAndDoesNotPoisonTheNextBatch()
            throws Exception {
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        AtomicInteger calls = new AtomicInteger();
        RuntimeException ownerFailure = new RuntimeException("offline");
        when(bindings.currentEndpointManagerIdsAsync(anyList()))
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        return CompletableFuture.failedFuture(ownerFailure);
                    }
                    List<String> ids = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(Map.of(
                            ids.get(0),
                            "adapter-1"
                    ));
                });
        WorkerRouteVerificationBatcher batcher = batcher(
                bindings,
                10,
                Duration.ofSeconds(1)
        );
        batcher.start();

        assertThatThrownBy(() -> batcher.verify(
                "adapter-1",
                "worker-1"
        ).join()).hasCause(ownerFailure);
        assertThat(batcher.verify("adapter-1", "worker-2")
                .get(1, TimeUnit.SECONDS)).isEqualTo(Decision.VERIFIED);
        assertThat(calls).hasValue(2);
    }

    private WorkerRouteVerificationBatcher batcher(
            WorkerBindingService bindings,
            int capacity,
            Duration timeout
    ) {
        WorkerRouteVerificationBatcher batcher =
                new WorkerRouteVerificationBatcher(
                        bindings,
                        capacity,
                        timeout
                );
        batchers.add(batcher);
        return batcher;
    }
}
