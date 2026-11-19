package com.xa.mass.server.task.retention;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TerminalItemPage;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.*;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RpcTaskItemRetentionTest {
    private static final long NOW = 2_000_000_000_000L;
    private final TaskRuntime runtime = mock(TaskRuntime.class);
    private final TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
    private final TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
    private final TaskItemScoreBandCore items = mock(TaskItemScoreBandCore.class);
    private final AtomicLong ticker = new AtomicLong();
    private final TaskScoreState parkObservation = parked("rpc");

    private static TaskScoreState parked(String id) {
        var observation = mock(TaskScoreState.class);
        when(observation.taskId()).thenReturn(id);
        when(observation.isIdleParked()).thenReturn(true);
        return observation;
    }
    private static TaskDescriptor descriptor(String id, TaskIdleDisposition disposition, Map<String, String> metadata) {
        return new TaskDescriptor(id, "g", disposition, Map.of("priority", "1", "maxRetryTimes", "1"), List.of(), null, metadata);
    }
    private static Map<String, String> raw() {
        return new TaskAttribution("p", 1L, "1000").stamp(Map.of(TaskCaller.INPUT_OWNER, TaskCaller.RAW_INPUT));
    }
    private static TaskRetentionProperties properties(boolean parent, boolean rpc) {
        return new TaskRetentionProperties(parent, Duration.ofHours(1), Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofHours(1), Path.of("build/rpc-policy-test.sqlite"), rpc, Duration.ofHours(1), Duration.ofHours(24));
    }
    private RpcTaskItemRetention policy(int rows, int tasks, Duration duration) {
        return new RpcTaskItemRetention(runtime, catalog, scores, items, new OperationGuard(), properties(true, true),
                new RpcTaskItemRetention.Limits(duration, rows, tasks), ticker::get);
    }
    private RpcTaskItemRetention policy() { return policy(2_000_000, 1000, Duration.ofSeconds(120)); }
    private static LinkedHashMap<String, Long> observations(int from, int count) {
        var result = new LinkedHashMap<String, Long>();
        for (int i = from; i < from + count; i++) result.put("item-%04d".formatted(i), (long) i + 1);
        return result;
    }
    @BeforeEach void configure() {
        when(scores.scanParkedTasks(anyString(), anyInt())).thenReturn(new ParkedTaskPage(List.of(parkObservation), "0", 1));
        when(scores.getScoreStates(anyList())).thenReturn(Map.of("rpc", parkObservation));
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("rpc", descriptor("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw())));
        when(runtime.loadTaskDataStates(anyList())).thenReturn(Map.of("rpc", new TaskDataState(TaskDataPhase.OPEN, null)));
        when(items.scanTerminalItems(anyString(), anyInt(), anyLong(), anyString(), anyInt()))
                .thenReturn(new TerminalItemPage(Map.of(), "0", 0));
        when(runtime.reclaimRpcItems(anyString(), anyMap(), any())).thenAnswer(call -> {
            Map<String, Long> batch = call.getArgument(1);
            boolean purge = call.getArgument(2) == RpcItemRetentionStage.PURGE_TERMINAL;
            return new RpcItemRetentionResult(batch.size(), batch.size(), batch.size(), purge ? batch.size() : 0, purge ? batch.size() : 0);
        });
    }

    @Test void parentAndRpcFlagsBothGateEveryRpcRead() {
        for (var config : List.of(properties(true, false), properties(false, true), properties(false, false))) {
            var policy = new RpcTaskItemRetention(runtime, catalog, scores, items, new OperationGuard(), config);
            assertThat(policy.runPass(NOW).scannedItems()).isZero();
        }
        verifyNoInteractions(runtime, catalog, scores, items);
    }

    @Test void onlyOpenAttributedRawParkedRpcTasksAreRead() {
        var malformed = new LinkedHashMap<>(raw()); malformed.put(TaskAttribution.CREATED, "broken");
        var sms = new LinkedHashMap<>(raw()); sms.put(TaskCaller.INPUT_OWNER, "sms");
        var descriptors = new ArrayList<Map<String, TaskDescriptor>>();
        descriptors.add(Map.of());
        descriptors.add(Map.of("rpc", descriptor("rpc", TaskIdleDisposition.CLOSE_WHEN_IDLE, raw())));
        descriptors.add(Map.of("rpc", descriptor("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, sms)));
        descriptors.add(Map.of("rpc", descriptor("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, malformed)));
        descriptors.add(Map.of("rpc", descriptor("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(TaskCaller.INPUT_OWNER, TaskCaller.RAW_INPUT))));
        for (var view : descriptors) {
            when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(view);
            assertThat(policy().runPass(NOW).skippedTasks()).isEqualTo(1);
        }
        verifyNoInteractions(items);
        verify(runtime, never()).reclaimRpcItems(anyString(), anyMap(), any());
    }

    @Test void missingNullOrChangedSchedulingAndDataEvidenceNeverReclaims() {
        when(scores.getScoreStates(anyList())).thenReturn(Map.of());
        assertThat(policy().runPass(NOW).skippedTasks()).isEqualTo(1);
        when(scores.getScoreStates(anyList())).thenReturn(Map.of("rpc", new TaskScoreState("rpc", 3, TaskScoreBand.PRE_REVIEW, null, null)));
        assertThat(policy().runPass(NOW).skippedTasks()).isEqualTo(1);
        when(scores.getScoreStates(anyList())).thenReturn(Map.of("rpc", parkObservation));
        for (var view : List.of(Map.<String, TaskDataState>of(), Map.of("rpc", new TaskDataState(TaskDataPhase.FROZEN, NOW)))) {
            when(runtime.loadTaskDataStates(anyList())).thenReturn(view);
            assertThat(policy().runPass(NOW).skippedTasks()).isEqualTo(1);
        }
        verifyNoInteractions(items);
        verify(runtime, never()).reclaimRpcItems(anyString(), anyMap(), any());
    }

    @Test void purgesAllTerminalTagsBeforeReleasingInputsAndNeverQueriesActiveTag() {
        when(items.scanTerminalItems(eq("rpc"), eq(2), anyLong(), eq("0"), anyInt()))
                .thenReturn(new TerminalItemPage(observations(0, 1), "0", 1));
        var policy = policy();
        var result = policy.runPass(NOW);
        assertThat(result.cycleComplete()).isTrue();
        assertThat(result.scannedItems()).isEqualTo(2);
        var tags = ArgumentCaptor.forClass(Integer.class);
        var cutoff = ArgumentCaptor.forClass(Long.class);
        verify(items, times(16)).scanTerminalItems(eq("rpc"), tags.capture(), cutoff.capture(), eq("0"), anyInt());
        assertThat(tags.getAllValues()).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(cutoff.getAllValues().subList(0, 8)).containsOnly(NOW - Duration.ofHours(24).toMillis());
        assertThat(cutoff.getAllValues().subList(8, 16)).containsOnly(NOW - Duration.ofHours(1).toMillis());
        var order = inOrder(runtime);
        order.verify(runtime).reclaimRpcItems("rpc", observations(0, 1), RpcItemRetentionStage.PURGE_TERMINAL);
        order.verify(runtime).reclaimRpcItems("rpc", observations(0, 1), RpcItemRetentionStage.RELEASE_INPUTS);
    }

    @Test void unknownMutationResponseRescansItsUncommittedPageWithoutReplayingBusinessWork() {
        long cutoff = NOW - Duration.ofHours(24).toMillis();
        var firstPage = observations(0, 251);
        var secondPage = observations(251, 1);
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt()))
                .thenAnswer(call -> new TerminalItemPage(new LinkedHashMap<>(firstPage), "page-two", firstPage.size()));
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("page-two"), anyInt()))
                .thenAnswer(call -> new TerminalItemPage(new LinkedHashMap<>(secondPage), "0", secondPage.size()));
        var calls = new AtomicInteger();
        doAnswer(call -> {
            Map<String, Long> batch = call.getArgument(1);
            batch.keySet().forEach(id -> { firstPage.remove(id); secondPage.remove(id); });
            if (calls.getAndIncrement() == 1) throw new IllegalStateException("response lost after commit");
            return new RpcItemRetentionResult(batch.size(), batch.size(), batch.size(), batch.size(), batch.size());
        }).when(runtime).reclaimRpcItems(anyString(), anyMap(), any());
        var policy = policy();
        var failed = policy.runPass(NOW);
        assertThat(failed.failureKind()).isEqualTo("IllegalStateException");
        assertThat(failed.deletedScores()).isEqualTo(100);
        var resumed = policy.runPass(NOW);
        assertThat(resumed.failureKind()).isNull();
        assertThat(resumed.deletedScores()).isEqualTo(52);
        assertThat(firstPage).isEmpty(); assertThat(secondPage).isEmpty();
        verify(items, times(2)).scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt());
        var batches = ArgumentCaptor.forClass(Map.class);
        verify(runtime, times(4)).reclaimRpcItems(eq("rpc"), batches.capture(), eq(RpcItemRetentionStage.PURGE_TERMINAL));
        assertThat(batches.getAllValues().stream().map(Map::size)).containsExactly(100, 100, 51, 1);
        assertThat(resumed.cycleComplete()).isTrue();
    }

    @Test void corruptScoreAfterScanIsFilteredOnRescanAndLaterPagesStillComplete() {
        long cutoff = NOW - Duration.ofHours(24).toMillis();
        var changed = observations(0, 4); changed.remove("item-0001");
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt()))
                .thenReturn(new TerminalItemPage(observations(0, 4), "next", 4), new TerminalItemPage(changed, "next", 4));
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("next"), anyInt()))
                .thenReturn(new TerminalItemPage(observations(4, 1), "0", 1));
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            Map<String, Long> batch = call.getArgument(1);
            if (first.getAndSet(false)) throw new IllegalStateException("Score became corrupt after scan");
            assertThat(batch).doesNotContainKey("item-0001");
            return new RpcItemRetentionResult(batch.size(), batch.size(), batch.size(), batch.size(), batch.size());
        }).when(runtime).reclaimRpcItems(anyString(), anyMap(), any());
        var policy = policy();
        assertThat(policy.runPass(NOW).failureKind()).isEqualTo("IllegalStateException");
        var resumed = policy.runPass(NOW);
        assertThat(resumed.cycleComplete()).isTrue();
        assertThat(resumed.deletedScores()).isEqualTo(4);
        assertThat(resumed.scannedItems()).isEqualTo(5);
        verify(items, times(2)).scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt());
        verify(items).scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("next"), anyInt());
    }

    @Test void actualScannedRowsIncludingEmptyFilteredPagesShareOnePassBudgetAndResume() {
        long cutoff = NOW - Duration.ofHours(24).toMillis();
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), eq(5)))
                .thenReturn(new TerminalItemPage(Map.of(), "filtered-page", 3));
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("filtered-page"), eq(2)))
                .thenReturn(new TerminalItemPage(Map.of(), "0", 2));
        var policy = policy(5, 1000, Duration.ofSeconds(120));
        var bounded = policy.runPass(NOW);
        assertThat(bounded.scannedItems()).isEqualTo(5);
        assertThat(bounded.scanLimitHit()).isTrue();
        verify(items, never()).scanTerminalItems(eq("rpc"), eq(3), anyLong(), anyString(), anyInt());
        assertThat(policy.runPass(NOW).cycleComplete()).isTrue();
        verify(items).scanTerminalItems(eq("rpc"), eq(3), eq(cutoff), eq("0"), eq(5));
    }

    @Test void deadlineStopsBeforeAnyMoreRequestsAndRetainsTheRestOfItsPage() {
        long cutoff = NOW - Duration.ofHours(24).toMillis();
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt()))
                .thenReturn(new TerminalItemPage(observations(0, 151), "0", 151));
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            Map<String, Long> batch = call.getArgument(1);
            if (first.getAndSet(false)) ticker.set(100);
            return new RpcItemRetentionResult(batch.size(), batch.size(), batch.size(), batch.size(), batch.size());
        }).when(runtime).reclaimRpcItems(anyString(), anyMap(), any());
        var policy = policy(2000, 1000, Duration.ofNanos(100));
        var bounded = policy.runPass(NOW);
        assertThat(bounded.deadlineHit()).isTrue();
        assertThat(bounded.deletedScores()).isEqualTo(100);
        verify(runtime, times(1)).reclaimRpcItems(anyString(), anyMap(), any());
        var resumed = policy.runPass(NOW);
        assertThat(resumed.deletedScores()).isEqualTo(51);
        assertThat(resumed.cycleComplete()).isTrue();
        verify(items).scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt());
    }

    @Test void theInputStageUsesOnlyTheScanBudgetLeftByTerminalPurge() {
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(NOW - Duration.ofHours(24).toMillis()), eq("0"), eq(5)))
                .thenReturn(new TerminalItemPage(observations(0, 1), "0", 4));
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(NOW - Duration.ofHours(1).toMillis()), eq("0"), eq(1)))
                .thenReturn(new TerminalItemPage(observations(1, 1), "input-next", 1));
        var policy = policy(5, 1000, Duration.ofSeconds(120));
        var bounded = policy.runPass(NOW);
        assertThat(bounded.scannedItems()).isEqualTo(5);
        assertThat(bounded.scanLimitHit()).isTrue();
        verify(runtime).reclaimRpcItems("rpc", observations(0, 1), RpcItemRetentionStage.PURGE_TERMINAL);
        verify(runtime).reclaimRpcItems("rpc", observations(1, 1), RpcItemRetentionStage.RELEASE_INPUTS);
        verify(items, never()).scanTerminalItems(eq("rpc"), eq(2), anyLong(), eq("input-next"), anyInt());
        assertThat(policy.runPass(NOW).cycleComplete()).isTrue();
        verify(items).scanTerminalItems(eq("rpc"), eq(2), anyLong(), eq("input-next"), eq(5));
    }

    @Test void taskCandidatePagesAreBoundedAndResumeAtTheSavedOwnerCursor() {
        when(scores.scanParkedTasks("0", 2)).thenReturn(new ParkedTaskPage(List.of(parkObservation), "tasks-next", 2));
        when(scores.scanParkedTasks("tasks-next", 2)).thenReturn(new ParkedTaskPage(List.of(), "0", 0));
        var policy = policy(2000, 2, Duration.ofSeconds(120));
        var first = policy.runPass(NOW);
        assertThat(first.taskCandidates()).isEqualTo(2);
        assertThat(first.taskLimitHit()).isTrue();
        verify(scores, never()).scanParkedTasks(eq("tasks-next"), anyInt());
        assertThat(policy.runPass(NOW).cycleComplete()).isTrue();
        verify(scores).scanParkedTasks("0", 2);
        verify(scores).scanParkedTasks("tasks-next", 2);
    }

    @Test void schedulingChangeBetweenChunksStopsThisTaskAndRetriesItOnTheNextCycle() {
        long cutoff = NOW - Duration.ofHours(24).toMillis();
        when(items.scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt()))
                .thenReturn(new TerminalItemPage(observations(0, 151), "0", 151));
        doAnswer(call -> {
            when(scores.getScoreStates(anyList())).thenReturn(Map.of("rpc", new TaskScoreState("rpc", 3, TaskScoreBand.PRE_REVIEW, null, null)));
            return new RpcItemRetentionResult(100, 100, 100, 100, 100);
        }).when(runtime).reclaimRpcItems(anyString(), anyMap(), any());
        var policy = policy();
        var stopped = policy.runPass(NOW);
        assertThat(stopped.skippedTasks()).isEqualTo(1);
        assertThat(stopped.cycleComplete()).isTrue();
        verify(runtime, times(1)).reclaimRpcItems(anyString(), anyMap(), any());
        when(scores.getScoreStates(anyList())).thenReturn(Map.of("rpc", parkObservation));
        policy.runPass(NOW);
        verify(items, times(2)).scanTerminalItems(eq("rpc"), eq(2), eq(cutoff), eq("0"), anyInt());
    }

    @Test void invalidRpcDurationsAreRejectedEvenWhenTheFeatureIsOff() {
        for (var duration : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofHours(24)))
            assertThatThrownBy(() -> new TaskRetentionProperties(true, Duration.ofHours(1), Duration.ofDays(7), Duration.ofDays(30),
                    Duration.ofHours(1), Path.of("build/invalid-rpc.sqlite"), false, duration, Duration.ofHours(24)))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void interruptionStopsWithoutARequestAndDoesNotConsumeSavedProgress() {
        var policy = policy();
        Thread.currentThread().interrupt();
        try {
            assertThat(policy.runPass(NOW).interrupted()).isTrue();
            verifyNoInteractions(runtime, catalog, scores, items);
        } finally {
            Thread.interrupted();
        }
        assertThat(policy.runPass(NOW).cycleComplete()).isTrue();
        verify(scores).scanParkedTasks("0", 100);
    }
}
