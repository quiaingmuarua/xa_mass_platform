package com.xa.mass.android.workerdemo;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidMessageDemoTest {
    private final AtomicLong clock = new AtomicLong();
    private final AtomicReference<WorkerLifecycle.Snapshot> worker = new AtomicReference<>(
            new WorkerLifecycle.Snapshot(WorkerLifecycle.State.RUNNING, "actual-worker", URI.create("ws://localhost/worker"), null));
    private AndroidMessageDemo channel(int accepted, int tracked, int recent) {
        return new AndroidMessageDemo(worker::get, "+8617000000000", clock::get, () -> 1000 + clock.get(),
                accepted, tracked, recent, 600_000);
    }
    static Map<String, Object> input(String id) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("campaignId", "campaign");
        value.put("messageId", id);
        value.put("country", "CN");
        value.put("recipientId", "+8613800000000");
        value.put("body", "  文本 {{name}}\n{}  ");
        return value;
    }
    private static String token(AndroidMessageDemo channel) { return channel.recentMessages().get(0).token; }

    @Test public void acceptancePreservesTextAndDoesNotInventReceipts() {
        AndroidMessageDemo channel = channel(10, 2, 10);
        AtomicInteger reports = new AtomicInteger();
        Map<String, Object> sent = channel.send(input("one"), (tag, time, content) -> { reports.incrementAndGet(); return true; });
        assertEquals("SENT", sent.get("status"));
        assertEquals(input("one").get("body"), sent.get("body"));
        assertEquals("actual-worker", sent.get("workerId"));
        assertEquals(0, reports.get());
        assertEquals(1, channel.recentMessages().size());
        assertThrows(UnsupportedOperationException.class, () -> sent.put("body", "different"));
    }

    @Test public void duplicateUsesFirstSnapshotAndReporterWithoutRenewal() {
        AndroidMessageDemo channel = channel(10, 2, 10);
        List<Map<String, Object>> reports = new ArrayList<>();
        WorkerOutcomeReporter first = (tag, time, content) -> { reports.add(Jsons.parseObject(content)); return true; };
        Map<String, Object> sent = channel.send(input("one"), first);
        String token = token(channel);
        clock.set(500_000);
        assertSame(sent, channel.send(input("one"), (tag, time, content) -> { fail("Retry reporter must not be adopted"); return false; }));
        assertTrue(channel.opened(token).startsWith("READ"));
        assertTrue(channel.reply(token, "原样回复", "reply-1").startsWith("REPLIED"));
        assertEquals(2, reports.size());
        assertEquals("原样回复", reports.get(1).get("reply"));
        assertEquals("reply-1", reports.get(1).get("replyRequestId"));
        assertEquals("one", reports.get(1).get("messageId"));
        assertEquals(sent.get("workerId"), reports.get(1).get("workerId"));
        clock.set(600_000);
        assertFalse(channel.message(token).receiptAvailable);
        assertEquals(input("one").get("body"), channel.message(token).body);
        assertTrue(channel.reply(token, "late", "late").contains("过期"));
        assertEquals(2, reports.size());
    }

    @Test public void trackingCapacityNeverBlocksConfirmedSending() {
        AndroidMessageDemo channel = channel(3, 1, 3);
        channel.send(input("one"), (tag, time, content) -> true);
        Map<String, Object> second = channel.send(input("two"), (tag, time, content) -> { fail(); return true; });
        assertEquals("SENT", second.get("status"));
        assertFalse(channel.recentMessages().get(0).receiptAvailable);
        assertTrue(channel.opened(token(channel)).contains("未保留"));
        assertEquals(2, channel.recentMessages().size());
    }

    @Test public void fullAcceptanceRejectsBeforeMutationAndExpiryReleasesCapacity() {
        AndroidMessageDemo channel = channel(1, 1, 1);
        Map<String, Object> first = channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE);
        assertThrows(IllegalStateException.class, () -> channel.send(input("two"), WorkerOutcomeReporter.UNAVAILABLE));
        assertSame(first, channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE));
        assertEquals("one", channel.recentMessages().get(0).messageId);
        clock.set(600_000);
        assertEquals("two", channel.send(input("two"), WorkerOutcomeReporter.UNAVAILABLE).get("messageId"));
    }

    @Test public void invalidAndConflictingPayloadsDoNotChangeAcceptance() {
        AndroidMessageDemo channel = channel(10, 1, 10);
        for (String field : input("one").keySet()) {
            Map<String, Object> bad = input("one"); bad.remove(field);
            assertThrows(IllegalArgumentException.class, () -> channel.send(bad, WorkerOutcomeReporter.UNAVAILABLE));
        }
        Map<String, Object> bad = input("one"); bad.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> channel.send(bad, WorkerOutcomeReporter.UNAVAILABLE));
        assertTrue(channel.recentMessages().isEmpty());
        Map<String, Object> sent = channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE);
        Map<String, Object> changed = input("one"); changed.put("body", "another");
        assertThrows(IllegalArgumentException.class, () -> channel.send(changed, WorkerOutcomeReporter.UNAVAILABLE));
        assertSame(sent, channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE));
    }

    @Test public void localObserverFailureDoesNotTurnAcceptanceIntoFailure() {
        AndroidMessageDemo channel = channel(2, 1, 2);
        channel.addListener(() -> { throw new IllegalStateException("UI unavailable"); });
        assertEquals("SENT", channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE).get("status"));
        assertEquals(1, channel.recentMessages().size());
    }

    @Test public void stopPreservesDedupButNeverRebindsOriginalMessage() {
        AndroidMessageDemo channel = channel(2, 2, 2);
        Map<String, Object> sent = channel.send(input("one"), (tag, time, content) -> { fail("Revoked reporter"); return true; });
        String old = token(channel);
        channel.revokeReporters();
        assertSame(sent, channel.send(input("one"), (tag, time, content) -> { fail("New reporter"); return true; }));
        assertFalse(channel.message(old).receiptAvailable);
        assertTrue(channel.opened(old).contains("停止"));
        AndroidMessageDemo restartedProcess = channel(2, 2, 2);
        assertNull(restartedProcess.message(old));
    }

    @Test public void staleWorkerAndRejectedReporterDoNotClaimSuccess() {
        AndroidMessageDemo channel = channel(2, 2, 2);
        worker.set(new WorkerLifecycle.Snapshot(WorkerLifecycle.State.STOPPED, "actual-worker", null, null));
        assertThrows(IllegalStateException.class, () -> channel.send(input("one"), WorkerOutcomeReporter.UNAVAILABLE));
        worker.set(new WorkerLifecycle.Snapshot(WorkerLifecycle.State.RUNNING, "actual-worker", URI.create("ws://localhost/worker"), null));
        channel.send(input("one"), (tag, time, content) -> false);
        assertTrue(channel.opened(token(channel)).contains("未被接受"));
    }

    @Test public void concurrentRetriesCreateOneAcceptance() throws Exception {
        AndroidMessageDemo channel = channel(2, 2, 2);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> channel.send(input("one"), (tag, time, content) -> true)));
            Map<String, Object> first = futures.get(0).get(2, TimeUnit.SECONDS);
            for (Future<Map<String, Object>> future : futures) assertSame(first, future.get(2, TimeUnit.SECONDS));
            assertEquals(1, channel.recentMessages().size());
        } finally { executor.shutdownNow(); }
    }

    @Test public void blockedReporterDoesNotHoldStateGateOrReattachAfterStop() throws Exception {
        AndroidMessageDemo channel = channel(2, 2, 2);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        channel.send(input("one"), (tag, time, content) -> {
            entered.countDown();
            try { return finish.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        });
        String token = token(channel);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> callback = executor.submit(() -> channel.opened(token));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.submit(channel::revokeReporters).get(1, TimeUnit.SECONDS);
            assertFalse(channel.message(token).receiptAvailable);
            finish.countDown();
            callback.get(2, TimeUnit.SECONDS);
            assertTrue(channel.message(token).feedback.contains("停止"));
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test public void expiredRecentMessagesRemainReadOnlyWithoutHidingNewAcceptance() {
        AndroidMessageDemo channel = channel(2, 2, 2);
        channel.send(input("one"), (tag, time, content) -> { fail("Expired reporter"); return true; });
        String old = token(channel);
        clock.set(600_000);
        assertEquals(1, channel.recentMessages().size());
        assertFalse(channel.message(old).receiptAvailable);
        assertTrue(channel.message(old).feedback.contains("过期"));
        assertEquals(Collections.singletonList("one"),
                channel.inspect(Collections.singletonMap("messageIds", Collections.singletonList("one"))).get("missing"));
        // The dedup window has ended; the old UI token cannot remove or report through this new record.
        channel.send(input("one"), (tag, time, content) -> true);
        String fresh = token(channel);
        assertNotEquals(old, fresh);
        assertFalse(channel.message(old).receiptAvailable);
        assertTrue(channel.message(fresh).receiptAvailable);
        assertTrue(channel.reply(old, "late", "late").contains("过期"));
        channel.send(input("two"), WorkerOutcomeReporter.UNAVAILABLE);
        assertEquals(2, channel.recentMessages().size());
        assertNull(channel.message(old));
    }

    @Test public void sendAdmittedBeforeStopCannotRetainReporterAfterLocalRevocation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AndroidMessageDemo channel = new AndroidMessageDemo(() -> {
            WorkerLifecycle.Snapshot snapshot = worker.get();
            entered.countDown();
            try { assertTrue(finish.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return snapshot;
        }, "+8617000000000", clock::get, () -> 1000L, 2, 2, 2, 600_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> sending = executor.submit(() ->
                    channel.send(input("one"), (tag, time, content) -> { fail("Revoked run"); return true; }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            channel.revokeReporters();
            finish.countDown();
            assertEquals("SENT", sending.get(2, TimeUnit.SECONDS).get("status"));
            assertFalse(channel.message(token(channel)).receiptAvailable);
            assertTrue(channel.opened(token(channel)).contains("停止"));
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @SuppressWarnings("unchecked")
    @Test public void recentViewIsBoundedAndPointEvidenceExcludesMessageContents() {
        AndroidMessageDemo channel = channel(200, 200, 100);
        for (int i = 0; i < 123; i++) channel.send(input("id-" + i), WorkerOutcomeReporter.UNAVAILABLE);
        assertEquals(100, channel.recentMessages().size());
        assertEquals("id-122", channel.recentMessages().get(0).messageId);
        Map<String, Object> evidence = channel.inspect(Collections.singletonMap("messageIds", Arrays.asList("id-0", "id-122", "missing")));
        List<Map<String, Object>> found = (List<Map<String, Object>>) evidence.get("accepted");
        assertEquals(2, found.size());
        assertEquals(Collections.singletonList("missing"), evidence.get("missing"));
        assertFalse(found.get(0).containsKey("body"));
        assertFalse(found.get(0).containsKey("recipientId"));
        assertTrue(((String) found.get(0).get("requestFingerprint")).matches("[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class, () -> channel.inspect(Collections.singletonMap("messageIds", Collections.nCopies(101, "one"))));
    }
}
