package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

public final class WorkerDeliveryAdapterLifecycleHost {

    private final WorkerDeliveryAdapterManager manager;

    public WorkerDeliveryAdapterLifecycleHost(
            WorkerDeliveryAdapterManager manager
    ) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        manager.start();
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        manager.close();
    }
}
