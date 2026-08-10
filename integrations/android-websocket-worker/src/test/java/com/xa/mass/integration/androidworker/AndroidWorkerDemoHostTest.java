package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.xa.mass.worker.runtime.WorkerLifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerDemoHostTest {

    private AndroidWorkerDemoHost host;
    private FakeWorker worker;
    private ManualExecutorService controlExecutor;

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidDemoStateCapability.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();

        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(application);
        AndroidDemoStateCapability capability =
                new AndroidDemoStateCapability(
                        application,
                        deviceProperties
                );
        controlExecutor = new ManualExecutorService();
        ExecutorService handlerExecutor =
                Executors.newSingleThreadExecutor();
        AndroidWorkerDemoResources resources =
                new AndroidWorkerDemoResources(
                        controlExecutor,
                        handlerExecutor
                );
        worker = new FakeWorker();
        host = new AndroidWorkerDemoHost(
                worker,
                capability,
                resources,
                new Handler(Looper.getMainLooper())
        );
    }

    @After
    public void tearDown() {
        host.close();
    }

    @Test
    public void mergesWorkerAndCapabilityWithoutOwningTheirMechanisms() {
        AtomicReference<AndroidWorkerDemoHost.Snapshot> observed =
                new AtomicReference<>();
        host.addListener(observed::set);

        assertEquals(
                WorkerLifecycle.State.STOPPED,
                host.snapshot().state()
        );
        assertEquals(1, host.incrementCounter());
        ShadowLooper.idleMainLooper();

        assertEquals(1, host.snapshot().counter());
        assertNotNull(observed.get());
        assertEquals(1, observed.get().counter());
        assertEquals(0, host.resetCounter());
        assertEquals(0, host.snapshot().counter());
    }

    @Test
    public void connectReturnsBeforeOneCoalescedStartRuns() {
        host.start();
        host.start();

        assertEquals(0, worker.startCalls);
        assertEquals(1, controlExecutor.pendingTasks());

        controlExecutor.runAll();
        assertEquals(1, worker.startCalls);
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                host.snapshot().state()
        );
    }

    @Test
    public void disconnectPreventsAQueuedStart() {
        host.start();
        host.stop();

        controlExecutor.runAll();

        assertEquals(0, worker.startCalls);
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                host.snapshot().state()
        );
    }

    @Test
    public void terminationDoesNotScheduleAnotherStart() {
        host.start();
        controlExecutor.runAll();

        worker.terminate();
        controlExecutor.runAll();

        assertEquals(1, worker.startCalls);
        assertEquals(0, controlExecutor.pendingTasks());
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                host.snapshot().state()
        );
    }

    private static final class FakeWorker implements WorkerLifecycle {

        private final Set<Listener> listeners =
                new CopyOnWriteArraySet<>();
        private State state = State.STOPPED;
        private int startCalls;
        private boolean closed;

        @Override
        public void start() {
            if (closed) {
                throw new IllegalStateException("Worker is closed");
            }
            startCalls++;
            state = State.RUNNING;
            publish();
        }

        @Override
        public void stop() {
            state = State.STOPPED;
            publish();
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(state, "worker", null, null);
        }

        @Override
        public void addListener(Listener listener) {
            listeners.add(listener);
            listener.onSnapshot(snapshot());
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        @Override
        public void close() {
            closed = true;
            state = State.STOPPED;
            publish();
        }

        private void terminate() {
            state = State.STOPPED;
            publish();
        }

        private void publish() {
            Snapshot snapshot = snapshot();
            for (Listener listener : listeners) {
                listener.onSnapshot(snapshot);
            }
        }
    }

    private static final class ManualExecutorService
            extends AbstractExecutorService {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            return queued;
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public synchronized void execute(Runnable command) {
            if (shutdown) {
                throw new IllegalStateException("Executor is closed");
            }
            tasks.add(command);
        }

        private void runAll() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) {
                    return;
                }
                task.run();
            }
        }

        private synchronized int pendingTasks() {
            return tasks.size();
        }
    }
}
