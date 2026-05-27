package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchWakeupBridgeTest {

    @Test
    void wakeFansOutToBoundedLaneRetryAndRuntimeReadyWakeup() {
        TaskAssignWorker assignWorker = mock(TaskAssignWorker.class);
        when(assignWorker.wakeWaitingRetries("worker available")).thenReturn(2);
        AtomicInteger runtimeWakeups = new AtomicInteger();
        TaskDispatchWakeupBridge bridge = new TaskDispatchWakeupBridge(assignWorker, runtimeWakeups::incrementAndGet);

        bridge.wake("worker available");

        verify(assignWorker).wakeWaitingRetries("worker available");
        assertEquals(1, runtimeWakeups.get());
    }
}
