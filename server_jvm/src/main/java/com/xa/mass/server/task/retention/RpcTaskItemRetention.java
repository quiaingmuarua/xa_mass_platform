package com.xa.mass.server.task.retention;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.RpcItemRetentionStage;
import com.xa.mass.kernel.task.TaskRuntime.TaskDataPhase;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Bounded process-local progress for the existing retention executor, not scheduling. */
final class RpcTaskItemRetention {
    private static final System.Logger LOG = System.getLogger(RpcTaskItemRetention.class.getName());
    private static final int TASK_PAGE = 100, ITEM_PAGE = 1000, DELETE_BATCH = 100;
    record Limits(Duration duration, int itemRows, int taskCandidates) {
        Limits {
            if (duration.isNegative() || duration.isZero() || itemRows < 1 || taskCandidates < 1)
                throw new IllegalArgumentException("RPC retention pass budgets must be positive");
        }
    }
    record Progress(long scannedItems, long taskCandidates, long examinedItems, long matchedItems,
            long deletedInputs, long deletedResults, long deletedScores, long skippedTasks,
            boolean deadlineHit, boolean scanLimitHit, boolean taskLimitHit, boolean interrupted,
            boolean cycleComplete, @Nullable String failureKind, Map<String, Long> skippedReasons, long elapsedMillis) { }
    private static final Limits DEFAULT_LIMITS = new Limits(Duration.ofSeconds(120), 2_000_000, 1000);
    private final TaskRuntime runtime;
    private final TaskResourceCatalog catalog;
    private final TaskScoreBandCore scores;
    private final TaskItemScoreBandCore items;
    private final OperationGuard operations;
    private final TaskRetentionProperties properties;
    private final Limits limits;
    private final LongSupplier ticker;
    private String taskCursor = "0", nextTaskCursor = "0";
    private List<TaskScoreState> taskPage = List.of();
    private int nextTask;
    private boolean taskPageLoaded;
    private @Nullable String taskId;
    private RpcItemRetentionStage stage = RpcItemRetentionStage.PURGE_TERMINAL;
    private int tag = 2;
    private String itemCursor = "0", nextItemCursor = "0";
    private List<Map.Entry<String, Long>> itemPage = List.of();
    private int nextItem;
    private boolean itemPageLoaded;

    RpcTaskItemRetention(TaskRuntime runtime, TaskResourceCatalog catalog, TaskScoreBandCore scores,
            TaskItemScoreBandCore items, OperationGuard operations, TaskRetentionProperties properties) {
        this(runtime, catalog, scores, items, operations, properties, DEFAULT_LIMITS, System::nanoTime);
    }
    RpcTaskItemRetention(TaskRuntime runtime, TaskResourceCatalog catalog, TaskScoreBandCore scores,
            TaskItemScoreBandCore items, OperationGuard operations, TaskRetentionProperties properties,
            Limits limits, LongSupplier ticker) {
        this.runtime = runtime; this.catalog = catalog; this.scores = scores; this.items = items;
        this.operations = operations; this.properties = properties; this.limits = limits; this.ticker = ticker;
    }

    Progress runPass(long now) {
        var pass = new Pass();
        if (!properties.enabled() || !properties.rpcItemRetentionEnabled()) return pass.result();
        // Previously read pages remain bounded and consume this pass's candidate budget too.
        pass.taskCandidates = taskPage.size() - nextTask + (taskId == null ? 0 : 1);
        try {
            while (true) {
                pass.beforeRequest();
                if (taskId == null && !selectTask(pass)) break;
                String id = taskId;
                if (!itemPageLoaded || nextItem == itemPage.size()) {
                    if (itemPageLoaded && advanceItemPage()) continue;
                    if (pass.scannedItems >= limits.itemRows()) { pass.scanLimitHit = true; break; }
                    if (!qualified(id, pass)) { pass.skippedTasks++; finishTask(); continue; }
                    pass.beforeRequest();
                    long age = (stage == RpcItemRetentionStage.PURGE_TERMINAL
                            ? properties.rpcTerminalRetention() : properties.rpcInputRetention()).toMillis();
                    var page = items.scanTerminalItems(id, tag, Math.max(0, now - age), itemCursor,
                            (int) Math.min(ITEM_PAGE, limits.itemRows() - pass.scannedItems));
                    pass.scannedItems += page.scannedCount();
                    itemPage = List.copyOf(page.observedScores().entrySet());
                    nextItemCursor = page.nextCursor(); nextItem = 0; itemPageLoaded = true;
                    if (itemPage.isEmpty()) continue;
                }
                var batch = new LinkedHashMap<String, Long>();
                int end = Math.min(nextItem + DELETE_BATCH, itemPage.size());
                for (int i = nextItem; i < end; i++) batch.put(itemPage.get(i).getKey(), itemPage.get(i).getValue());
                // The local control lock covers only this bounded mutation, never a scan/traversal.
                var result = operations.taskMutation(id, () -> {
                    if (!qualified(id, pass)) return null;
                    pass.beforeRequest();
                    return runtime.reclaimRpcItems(id, batch, stage);
                });
                if (result == null) { pass.skippedTasks++; finishTask(); continue; }
                pass.examinedItems += result.examinedItems(); pass.matchedItems += result.matchedItems();
                pass.deletedInputs += result.deletedInputs(); pass.deletedResults += result.deletedResults();
                pass.deletedScores += result.deletedScores();
                // Only a fully processed page can advance the committed keyset cursor.
                nextItem = end;
            }
        } catch (DeadlineReached stopped) {
            pass.deadlineHit = true;
        } catch (PassInterrupted stopped) {
            pass.interrupted = true;
        } catch (RuntimeException failed) {
            pass.failureKind = failed.getClass().getSimpleName();
            // Re-observe from the committed page cursor: a cached Score may have
            // become corrupt, and must not pin every later pass to the same batch.
            itemPage = List.of(); nextItem = 0; itemPageLoaded = false; nextItemCursor = "0";
        }
        var result = pass.result();
        LOG.log(result.failureKind() != null || result.deadlineHit() || result.scanLimitHit() || result.taskLimitHit()
                        ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                "RPC Item retention: scanned={0}, taskCandidates={1}, examined={2}, matched={3}, inputs={4}, results={5}, scores={6}, skippedTasks={7}, deadlineHit={8}, scanLimitHit={9}, taskLimitHit={10}, interrupted={11}, cycleComplete={12}, failureKind={13}, skippedReasons={14}, elapsedMillis={15}",
                result.scannedItems(), result.taskCandidates(), result.examinedItems(), result.matchedItems(),
                result.deletedInputs(), result.deletedResults(), result.deletedScores(), result.skippedTasks(),
                result.deadlineHit(), result.scanLimitHit(), result.taskLimitHit(), result.interrupted(),
                result.cycleComplete(), result.failureKind(), result.skippedReasons(), result.elapsedMillis());
        return result;
    }

    private boolean selectTask(Pass pass) {
        while (nextTask == taskPage.size()) {
            if (taskPageLoaded) {
                taskCursor = nextTaskCursor; taskPageLoaded = false; taskPage = List.of(); nextTask = 0;
                if (taskCursor.equals("0")) { pass.cycleComplete = true; return false; }
            }
            if (pass.taskCandidates >= limits.taskCandidates()) { pass.taskLimitHit = true; return false; }
            pass.beforeRequest();
            var page = scores.scanParkedTasks(taskCursor,
                    (int) Math.min(TASK_PAGE, limits.taskCandidates() - pass.taskCandidates));
            pass.taskCandidates += page.scannedCount();
            taskPage = page.tasks(); nextTaskCursor = page.nextCursor(); taskPageLoaded = true;
        }
        taskId = taskPage.get(nextTask++).taskId();
        return true;
    }

    private boolean qualified(String id, Pass pass) {
        pass.beforeRequest();
        var task = catalog.loadTaskAllocationDescriptors(List.of(id)).get(id);
        if (task == null) return pass.skipped("descriptor-missing");
        if (task.idleDisposition() != TaskIdleDisposition.PARK_WHEN_IDLE
                || !TaskCaller.RAW_INPUT.equals(task.metadata().get(TaskCaller.INPUT_OWNER))) return pass.skipped("not-raw-rpc");
        try { if (TaskAttribution.read(task.metadata()) == null) return pass.skipped("attribution-missing"); }
        catch (IllegalArgumentException corrupt) { return pass.skipped("attribution-invalid"); }
        pass.beforeRequest();
        var score = scores.getScoreStates(List.of(id)).get(id);
        if (score == null || !score.isIdleParked()) return pass.skipped("not-parked");
        pass.beforeRequest();
        var data = runtime.loadTaskDataStates(List.of(id)).get(id);
        if (data == null || data.phase() != TaskDataPhase.OPEN) return pass.skipped("not-open");
        return true;
    }

    /** Advance only after every chunk from the previous page completed successfully. */
    private boolean advanceItemPage() {
        itemCursor = nextItemCursor; itemPageLoaded = false; itemPage = List.of(); nextItem = 0;
        if (!itemCursor.equals("0")) return false;
        if (++tag > 9) {
            tag = 2;
            if (stage == RpcItemRetentionStage.PURGE_TERMINAL) stage = RpcItemRetentionStage.RELEASE_INPUTS;
            else finishTask();
        }
        return true;
    }

    private void finishTask() {
        taskId = null; stage = RpcItemRetentionStage.PURGE_TERMINAL; tag = 2;
        itemCursor = "0"; nextItemCursor = "0"; itemPage = List.of(); nextItem = 0; itemPageLoaded = false;
    }

    private final class Pass {
        private final long started = ticker.getAsLong();
        private long scannedItems, taskCandidates, examinedItems, matchedItems, deletedInputs, deletedResults, deletedScores, skippedTasks;
        private boolean deadlineHit, scanLimitHit, taskLimitHit, interrupted, cycleComplete;
        private @Nullable String failureKind;
        private final Map<String, Long> skippedReasons = new LinkedHashMap<>();
        private boolean skipped(String reason) { skippedReasons.merge(reason, 1L, Long::sum); return false; }
        private void beforeRequest() {
            if (Thread.currentThread().isInterrupted()) throw new PassInterrupted();
            if (ticker.getAsLong() - started >= limits.duration().toNanos()) throw new DeadlineReached();
        }
        private Progress result() {
            return new Progress(scannedItems, taskCandidates, examinedItems, matchedItems, deletedInputs, deletedResults,
                    deletedScores, skippedTasks, deadlineHit, scanLimitHit, taskLimitHit, interrupted, cycleComplete,
                    failureKind, Map.copyOf(skippedReasons), Math.max(0, ticker.getAsLong() - started) / 1_000_000);
        }
    }
    private static final class DeadlineReached extends RuntimeException { }
    private static final class PassInterrupted extends RuntimeException { }
}
