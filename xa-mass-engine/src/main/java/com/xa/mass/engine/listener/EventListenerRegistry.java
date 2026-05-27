package com.xa.mass.engine.listener;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.engine.worker.WorkerStatusEventListener;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy runtime event-bus listener registration helpers.
 */
public class EventListenerRegistry {
    private static final Logger log = LoggerFactory.getLogger(EventListenerRegistry.class);

    private EventListenerRegistry() {
    }

    public static WorkerStatusEventListener registerWorkerStatusListeners(
            EventBusFacade eventBus,
            WorkerResourceRuntime workerResourceRuntime
    ) {
        return registerWorkerStatusListeners(eventBus, workerResourceRuntime, null);
    }

    public static WorkerStatusEventListener registerWorkerStatusListeners(
            EventBusFacade eventBus,
            WorkerResourceRuntime workerResourceRuntime,
            Runnable dispatchWakeupCallback
    ) {
        log.info("registerWorkerStatusListeners: register worker status event listeners ...");
        WorkerStatusEventListener listener =
                new WorkerStatusEventListener(workerResourceRuntime, dispatchWakeupCallback);
        eventBus.register(listener);
        return listener;
    }
}
