package com.xa.mass.android.workerdemo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public final class AndroidWorkerHostEventsTest {

    @Test
    public void appendsFixedLocalEventsAfterBusinessDefinitions() {
        FakeWorker worker = new FakeWorker();
        AndroidDemoCapabilities capabilities = new AndroidDemoCapabilities(
                RuntimeEnvironment.getApplication()
        );
        WorkerEventDefinition<?> business = WorkerEventDefinition.extension(
                "android.test",
                WorkerEventParameterResolvers.jsonMap(),
                ignored -> "{}"
        );

        Collection<? extends WorkerEventDefinition<?>> assembled =
                AndroidWorkerHostEvents.assemble(
                        List.of(business),
                        AndroidWorkerHostControl.create(worker),
                        capabilities,
                        new AndroidWorkerLabEvents()
                );
        List<String> eventNames = new ArrayList<>();
        for (WorkerEventDefinition<?> definition : assembled) {
            eventNames.add(definition.eventName());
        }

        assertEquals(List.of(
                "extension.worker.android.test",
                AndroidWorkerHostEvents.SNAPSHOT_EVENT,
                AndroidWorkerHostEvents.START_EVENT,
                AndroidWorkerHostEvents.STOP_EVENT
        ), eventNames);
    }

    @Test
    public void exposesSnapshotAndRequestsLifecycleWithoutWaiting() {
        FakeWorker worker = new FakeWorker();
        worker.workerId = "worker-id";
        worker.endpointUri = URI.create("ws://127.0.0.1:18083/worker");
        AndroidDemoCapabilities capabilities = new AndroidDemoCapabilities(
                RuntimeEnvironment.getApplication()
        );
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                AndroidWorkerHostEvents.assemble(
                        List.of(),
                        AndroidWorkerHostControl.create(worker),
                        capabilities,
                        new AndroidWorkerLabEvents()
                )
        );

        Map<String, Object> initial = result(
                dispatcher,
                AndroidWorkerHostEvents.SNAPSHOT_EVENT
        );
        assertEquals("STOPPED", initial.get("state"));
        assertEquals("worker-id", initial.get("workerId"));
        assertEquals(
                "ws://127.0.0.1:18083/worker",
                initial.get("endpointUri")
        );
        assertNull(initial.get("diagnosticMessage"));
        assertNull(initial.get("stopReason"));
        assertNull(initial.get("cooldownUntilMillis"));
        assertNull(initial.get("nextConnectionCheckAtMillis"));
        assertEquals(0L, initial.get("processedCommands"));
        assertNull(initial.get("lastEvent"));
        assertEquals(0L, initial.get("activeDelayCount"));

        Map<String, Object> started = result(
                dispatcher,
                AndroidWorkerHostEvents.START_EVENT
        );
        assertEquals(Boolean.TRUE, started.get("accepted"));
        assertEquals("RUNNING", started.get("requestedState"));
        assertEquals(1, worker.startCalls);
        assertEquals(WorkerLifecycle.State.RUNNING, worker.state);

        Map<String, Object> stopped = result(
                dispatcher,
                AndroidWorkerHostEvents.STOP_EVENT
        );
        assertEquals(Boolean.TRUE, stopped.get("accepted"));
        assertEquals("STOPPED", stopped.get("requestedState"));
        assertEquals(1, worker.stopCalls);
        assertEquals(WorkerLifecycle.State.STOPPED, worker.state);
    }

    @Test
    public void assembledCollectionIsImmutableAndRejectsNulls() {
        AndroidDemoCapabilities capabilities = new AndroidDemoCapabilities(
                RuntimeEnvironment.getApplication()
        );
        Collection<? extends WorkerEventDefinition<?>> assembled =
                AndroidWorkerHostEvents.assemble(
                        List.of(),
                        AndroidWorkerHostControl.create(new FakeWorker()),
                        capabilities,
                        new AndroidWorkerLabEvents()
                );

        assertFalse(assembled.isEmpty());
        boolean rejected = false;
        try {
            @SuppressWarnings("unchecked")
            Collection<WorkerEventDefinition<?>> mutable =
                    (Collection<WorkerEventDefinition<?>>) assembled;
            mutable.clear();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void repeatedRequestsDelegateToWorkerAndFailuresUseProtocolCode() {
        FakeWorker worker = new FakeWorker();
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                AndroidWorkerHostEvents.assemble(
                        List.of(),
                        AndroidWorkerHostControl.create(worker),
                        new AndroidDemoCapabilities(
                                RuntimeEnvironment.getApplication()
                        ),
                        new AndroidWorkerLabEvents()
                )
        );

        result(dispatcher, AndroidWorkerHostEvents.START_EVENT);
        result(dispatcher, AndroidWorkerHostEvents.START_EVENT);
        result(dispatcher, AndroidWorkerHostEvents.STOP_EVENT);
        result(dispatcher, AndroidWorkerHostEvents.STOP_EVENT);

        assertEquals(2, worker.startCalls);
        assertEquals(2, worker.stopCalls);

        worker.startFailure = new IllegalStateException("local failure");
        WorkerCommandOutcome failure = execute(
                dispatcher,
                AndroidWorkerHostEvents.START_EVENT
        );
        assertEquals("3303", failure.diagnosticCode());
    }

    @Test
    public void localHttpStopCancelsTheSameRecoveryAsThePage() {
        AndroidWorkerHostControlTest.Fixture fixture = new AndroidWorkerHostControlTest.Fixture(0);
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                AndroidWorkerHostEvents.assemble(List.of(), fixture.control,
                        new AndroidDemoCapabilities(RuntimeEnvironment.getApplication()),
                        new AndroidWorkerLabEvents()));
        result(dispatcher, AndroidWorkerHostEvents.START_EVENT);
        fixture.worker.stopped(WorkerLifecycle.StopReason.COOLDOWN, 99_000_000L);
        Runnable staleTimer = fixture.scheduler.pending.get(0).action;
        Map<String, Object> cooled = result(dispatcher, AndroidWorkerHostEvents.SNAPSHOT_EVENT);
        assertEquals("COOLDOWN", cooled.get("stopReason"));
        assertEquals(99_000_000L, cooled.get("cooldownUntilMillis"));
        assertEquals(Boolean.TRUE, cooled.get("connectionRequested"));
        assertTrue(cooled.get("nextConnectionCheckAtMillis") instanceof Number);

        result(dispatcher, AndroidWorkerHostEvents.STOP_EVENT);
        staleTimer.run();
        assertEquals(1, fixture.worker.starts);
        assertEquals(1, fixture.worker.stops);
        Map<String, Object> stopped = result(dispatcher, AndroidWorkerHostEvents.SNAPSHOT_EVENT);
        assertEquals(Boolean.FALSE, stopped.get("connectionRequested"));
        assertNull(stopped.get("nextConnectionCheckAtMillis"));

        result(dispatcher, AndroidWorkerHostEvents.START_EVENT);
        assertEquals(2, fixture.worker.starts);
    }

    private static Map<String, Object> result(
            WorkerCommandDispatcher dispatcher,
            String eventName
    ) {
        WorkerCommandOutcome outcome = execute(dispatcher, eventName);
        assertEquals("", outcome.diagnosticCode());
        return Jsons.parseObject(outcome.payload());
    }

    private static WorkerCommandOutcome execute(
            WorkerCommandDispatcher dispatcher,
            String eventName
    ) {
        DeliveryCommand command = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                eventName,
                Long.MAX_VALUE,
                "{}",
                "android-host-test"
        );
        WorkerCommandOutcome outcome = dispatcher.execute(command)
                .orElseThrow();
        return outcome;
    }

    private static final class FakeWorker implements WorkerLifecycle {

        private State state = State.STOPPED;
        private String workerId;
        private URI endpointUri;
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        @Override
        public void start() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
            state = State.RUNNING;
        }

        @Override
        public void stop() {
            stopCalls++;
            state = State.STOPPED;
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(state, workerId, endpointUri, null);
        }

        @Override
        public void addListener(Listener listener) {
        }

        @Override
        public void removeListener(Listener listener) {
        }

        @Override
        public void close() {
        }
    }
}
