package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase;
import java.util.Objects;

final class NettyWorkerDeliveryAdapter implements WorkerDeliveryAdapter {

    private final String adapterId;
    private final WorkerConnectionMechanism connectionMechanism;
    private final AdapterProcessManager processManager;
    private final NettyWorkerServer networkServer;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;

    NettyWorkerDeliveryAdapter(
            String adapterId,
            NettyWorkerServer networkServer,
            WorkerConnectionMechanism connectionMechanism,
            AdapterProcessManager processManager
    ) {
        this.adapterId = requireAdapterId(adapterId);
        this.networkServer = Objects.requireNonNull(
                networkServer,
                "networkServer"
        );
        this.connectionMechanism = Objects.requireNonNull(
                connectionMechanism,
                "connectionMechanism"
        );
        this.processManager = Objects.requireNonNull(
                processManager,
                "processManager"
        );
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public WorkerDeliveryAdapterState state() {
        return state;
    }

    @Override
    public synchronized void start() {
        if (state == WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        if (state != WorkerDeliveryAdapterState.REGISTERED) {
            throw new IllegalStateException(
                    "Cannot start Adapter from state " + state
            );
        }
        try {
            networkServer.start(connectionMechanism);
            state = WorkerDeliveryAdapterState.RUNNING;
            processManager.start();
        } catch (RuntimeException error) {
            RuntimeException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "netty.start",
                    "Netty Adapter could not start"
            );
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            state = WorkerDeliveryAdapterState.STOPPING;
        }

        boolean interruptedOnEntry = Thread.interrupted();
        RuntimeException failure = interruptedOnEntry
                ? new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                        "netty.close",
                        "Adapter shutdown was already interrupted",
                        null
                )
                : null;
        failure = quiesceProcesses(BEFORE_NETWORK_CLOSE, failure);
        try {
            networkServer.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        } finally {
            connectionMechanism.clear();
        }
        failure = quiesceProcesses(AFTER_NETWORK_CLOSE, failure);
        try {
            processManager.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }

        synchronized (this) {
            state = WorkerDeliveryAdapterState.CLOSED;
        }
        if (interruptedOnEntry) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw classify(
                    failure,
                    WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                    "netty.close",
                    "Netty Adapter could not close cleanly"
            );
        }
    }

    private RuntimeException quiesceProcesses(
            QuiescePhase phase,
            RuntimeException failure
    ) {
        try {
            processManager.quiesce(phase);
        } catch (RuntimeException error) {
            return accumulate(failure, error);
        }
        return failure;
    }

    private static WorkerDeliveryAdapterException classify(
            RuntimeException error,
            WorkerDeliveryAdapterErrorCode errorCode,
            String operation,
            String message
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                errorCode,
                operation,
                message,
                error
        );
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        return adapterId;
    }
}
