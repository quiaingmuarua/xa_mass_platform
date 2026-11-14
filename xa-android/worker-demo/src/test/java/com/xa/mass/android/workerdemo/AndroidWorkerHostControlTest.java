package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AndroidWorkerHostControlTest {

    @Test public void checksEarlyReleaseWithoutWaitingForTheOldDayDeadline() {
        Fixture fixture = new Fixture(1234);
        fixture.control.start();
        assertEquals(1, fixture.worker.starts);
        long deadline = fixture.scheduler.wall + 24 * 60 * 60 * 1000L;
        fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, deadline);
        fixture.worker.publish();
        fixture.worker.publish();
        assertEquals(1, fixture.scheduler.pending.size());
        assertEquals(Long.valueOf(fixture.scheduler.wall + 3_601_234),
                fixture.control.status().nextCheckAtMillis());
        assertEquals(Long.valueOf(deadline), fixture.control.snapshot().cooldownUntilMillis());

        fixture.scheduler.advance(3_601_233, true);
        assertEquals(1, fixture.worker.starts);
        fixture.scheduler.advance(1, true);
        assertEquals(2, fixture.worker.starts);
        assertNull(fixture.control.status().nextCheckAtMillis());
        fixture.worker.connected();
        assertTrue(fixture.control.status().desiredRunning());
        fixture.scheduler.advance(24 * 60 * 60 * 1000L, true);
        assertEquals(2, fixture.worker.starts);
    }

    @Test public void onlyCooldownRecoveryContinuesAfterTransientFailure() {
        Fixture fixture = new Fixture(0);
        fixture.control.start();
        fixture.worker.stopped(WorkerLifecycle.StopReason.UNAVAILABLE, null);
        assertTrue(fixture.scheduler.pending.isEmpty());
        assertFalse(fixture.control.status().desiredRunning());

        fixture.control.start();
        fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, 99_000_000L);
        fixture.scheduler.advance(3_600_000, true);
        fixture.worker.stopped(WorkerLifecycle.StopReason.UNAVAILABLE, null);
        assertEquals(1, fixture.scheduler.pending.size());
        fixture.scheduler.advance(3_600_000, true);
        fixture.worker.stopped(WorkerLifecycle.StopReason.REJECTED, null);
        assertTrue(fixture.scheduler.pending.isEmpty());
        assertFalse(fixture.control.status().desiredRunning());
    }

    @Test public void manualStopFencesOldTimerAndUnorderedLifecycleNotification() {
        Fixture fixture = coolingFixture();
        Runnable oldTimer = fixture.scheduler.pending.get(0).action;
        WorkerLifecycle.Snapshot oldCooldown = fixture.worker.snapshot();
        fixture.control.stop();
        assertEquals(1, fixture.worker.stops);
        assertFalse(fixture.control.status().desiredRunning());
        assertNull(fixture.control.status().nextCheckAtMillis());
        fixture.worker.emit(oldCooldown);
        oldTimer.run();
        fixture.control.onForeground();
        fixture.scheduler.advance(86_400_000, true);
        assertEquals(1, fixture.worker.starts);
        assertTrue(fixture.scheduler.pending.isEmpty());
    }

    @Test public void manualAttemptCancelsOldTimerAndCannotBeOverwrittenByOldCooldown() {
        Fixture fixture = coolingFixture();
        Runnable oldTimer = fixture.scheduler.pending.get(0).action;
        WorkerLifecycle.Snapshot oldCooldown = fixture.worker.snapshot();
        fixture.control.start();
        assertEquals(2, fixture.worker.starts);
        oldTimer.run();
        fixture.worker.emit(oldCooldown);
        assertTrue(fixture.scheduler.pending.isEmpty());
        fixture.worker.connected();
        fixture.worker.emit(oldCooldown);
        assertTrue(fixture.scheduler.pending.isEmpty());
        assertEquals(2, fixture.worker.starts);
    }

    @Test public void foregroundOnlyMakesOneDueCheckAfterTheProcessGetsTimeAgain() {
        Fixture fixture = coolingFixture();
        Runnable delayed = fixture.scheduler.pending.get(0).action;
        for (int i = 0; i < 10; i++) fixture.control.onForeground();
        assertEquals(1, fixture.worker.starts);
        fixture.scheduler.advance(3_600_000, false);
        fixture.control.onForeground();
        assertEquals(2, fixture.worker.starts);
        delayed.run();
        fixture.control.onForeground();
        assertEquals(2, fixture.worker.starts);
        assertTrue(fixture.scheduler.pending.isEmpty());
    }

    @Test public void closeDiscardsTheProcessTimerAndNextProcessStartsAfresh() {
        Fixture firstProcess = coolingFixture();
        Runnable oldTimer = firstProcess.scheduler.pending.get(0).action;
        firstProcess.control.close();
        oldTimer.run();
        assertEquals(1, firstProcess.worker.closes);
        assertEquals(1, firstProcess.worker.starts);
        assertTrue(firstProcess.worker.listeners.isEmpty());
        assertTrue(firstProcess.scheduler.pending.isEmpty());

        Fixture newProcess = new Fixture(0);
        assertEquals(0, newProcess.worker.starts);
        newProcess.control.start();
        assertEquals(1, newProcess.worker.starts);
    }

    @Test public void jitterIsBoundedAndDoesNotBecomeASecondTimer() {
        Fixture fixture = new Fixture(AndroidWorkerHostControl.MAX_JITTER_MILLIS);
        fixture.control.start();
        fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, 99_000_000L);
        assertEquals(3_900_000, fixture.scheduler.pending.get(0).due);
        fixture.worker.publish();
        fixture.control.onForeground();
        assertEquals(1, fixture.scheduler.pending.size());
    }

    private static Fixture coolingFixture() {
        Fixture fixture = new Fixture(0);
        fixture.control.start();
        fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, 99_000_000L);
        return fixture;
    }

    static final class Fixture {
        final FakeWorker worker = new FakeWorker();
        final FakeScheduler scheduler = new FakeScheduler();
        final AndroidWorkerHostControl control;

        Fixture(long jitter) {
            control = new AndroidWorkerHostControl(worker, scheduler,
                    () -> scheduler.elapsed, () -> scheduler.wall, () -> jitter);
        }
    }

    static final class FakeScheduler implements AndroidWorkerHostControl.Scheduler {
        long elapsed;
        long wall = 1_800_000_000_000L;
        final List<Scheduled> pending = new ArrayList<>();

        @Override public void execute(Runnable action) { action.run(); }
        @Override public void schedule(Runnable action, long delayMillis) {
            pending.add(new Scheduled(action, elapsed + delayMillis));
        }
        @Override public void cancel(Runnable action) {
            pending.removeIf(item -> item.action == action);
        }

        void advance(long millis, boolean dispatch) {
            elapsed += millis;
            wall += millis;
            if (!dispatch) return;
            for (Scheduled scheduled : new ArrayList<>(pending)) {
                if (scheduled.due <= elapsed && pending.remove(scheduled)) scheduled.action.run();
            }
        }
    }

    static final class Scheduled {
        final Runnable action;
        final long due;
        Scheduled(Runnable action, long due) { this.action = action; this.due = due; }
    }

    static final class FakeWorker implements WorkerLifecycle {
        private Snapshot snapshot = new Snapshot(State.STOPPED, null, null, null);
        final List<Listener> listeners = new ArrayList<>();
        int starts;
        int stops;
        int closes;

        @Override public void start() {
            starts++;
            snapshot = new Snapshot(State.RUNNING, null, null, null);
            publish();
        }
        @Override public void stop() {
            stops++;
            stopped(StopReason.MANUAL, null);
        }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public void addListener(Listener listener) { listeners.add(listener); listener.onSnapshot(snapshot); }
        @Override public void removeListener(Listener listener) { listeners.remove(listener); }
        @Override public void close() { closes++; stopped(StopReason.MANUAL, null); }

        void stopped(StopReason reason, Long untilMillis) {
            snapshot = new Snapshot(State.STOPPED, "worker-original", null, null, reason, untilMillis);
            publish();
        }
        void connected() {
            snapshot = new Snapshot(State.RUNNING, "worker-original", URI.create("ws://localhost/worker"), null);
            publish();
        }
        void publish() { emit(snapshot); }
        void emit(Snapshot value) { for (Listener listener : new ArrayList<>(listeners)) listener.onSnapshot(value); }
    }
}
