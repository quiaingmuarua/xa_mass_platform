package com.xa.mass.engine.listener;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.engine.worker.WorkerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 浜嬩欢鐩戝惉娉ㄥ唽涓績锛屽彧娉ㄥ唽Worker涓婁笅绾夸簨浠?
 */
public class EventListenerRegistry {
    private static final Logger log = LoggerFactory.getLogger(EventListenerRegistry.class);

    private EventListenerRegistry() {
    }

    public static WorkerManager.WorkerStatusEventListener registerWorkerStatusListeners(
            EventBusFacade eventBus,
            WorkerManager workerManager
    ) {
        log.info("registerWorkerStatusListeners: register worker status event listeners ...");
        WorkerManager.WorkerStatusEventListener listener = new WorkerManager.WorkerStatusEventListener(workerManager);
        eventBus.register(listener);
        return listener;
    }
}
