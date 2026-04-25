package com.xa.mass.starter.transport;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Context passed when assembling worker transport bindings for the embedded
 * runtime.
 */
public final class WorkerTransportRuntimeFactoryContext {

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final TaskDispatchChannel taskDispatchChannel;
    private final WebSocketTransportFrameCodec deprecatedFrameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final boolean gatewayEnabled;

    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                MessageTransporter<String, OutboundDelivery> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry,
                                                TaskDispatchChannel taskDispatchChannel,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                boolean gatewayEnabled) {
        this(taskManager,
                workerManager,
                messageTransporter,
                endpointRegistry,
                taskDispatchChannel,
                null,
                taskResultIngestChannel,
                systemEventChannel,
                gatewayEnabled);
    }

    /**
     * @deprecated Prefer {@link #WorkerTransportRuntimeFactoryContext(TaskManager, WorkerManager, MessageTransporter,
     * WorkerEndpointRegistry, TaskDispatchChannel, TaskResultIngestChannel, WorkerSystemEventChannel, boolean)} so
     * worker transport assembly depends on the canonical dispatch seam rather than the adapter-local WebSocket codec.
     */
    @Deprecated(forRemoval = false)
    public WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                WorkerManager workerManager,
                                                MessageTransporter<String, OutboundDelivery> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry,
                                                WebSocketTransportFrameCodec frameCodec,
                                                TaskResultIngestChannel taskResultIngestChannel,
                                                WorkerSystemEventChannel systemEventChannel,
                                                boolean gatewayEnabled) {
        this(taskManager,
                workerManager,
                messageTransporter,
                endpointRegistry,
                createTaskDispatchChannel(messageTransporter, frameCodec),
                frameCodec,
                taskResultIngestChannel,
                systemEventChannel,
                gatewayEnabled);
    }

    private WorkerTransportRuntimeFactoryContext(TaskManager taskManager,
                                                 WorkerManager workerManager,
                                                 MessageTransporter<String, OutboundDelivery> messageTransporter,
                                                 WorkerEndpointRegistry endpointRegistry,
                                                 TaskDispatchChannel taskDispatchChannel,
                                                 WebSocketTransportFrameCodec deprecatedFrameCodec,
                                                 TaskResultIngestChannel taskResultIngestChannel,
                                                 WorkerSystemEventChannel systemEventChannel,
                                                 boolean gatewayEnabled) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.taskDispatchChannel = taskDispatchChannel;
        this.deprecatedFrameCodec = deprecatedFrameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
        this.gatewayEnabled = gatewayEnabled;
    }

    private static TaskDispatchChannel createTaskDispatchChannel(
            MessageTransporter<String, OutboundDelivery> messageTransporter,
            WebSocketTransportFrameCodec frameCodec) {
        if (messageTransporter == null || frameCodec == null) {
            return null;
        }
        return items -> {
            if (items == null || items.isEmpty()) {
                return;
            }
            for (var dispatchItem : items) {
                String rawJson = frameCodec.encodeCanonicalTaskDispatch(dispatchItem);
                messageTransporter.sendOutput(new OutboundDelivery(
                        dispatchItem.getWorkerId(),
                        rawJson,
                        dispatchItem.getMessageId()
                ));
            }
        };
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public MessageTransporter<String, OutboundDelivery> getMessageTransporter() {
        return messageTransporter;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TaskDispatchChannel getTaskDispatchChannel() {
        return taskDispatchChannel;
    }

    /**
     * @deprecated Prefer {@link #getTaskDispatchChannel()} so runtime transport bindings depend on the canonical
     * dispatch seam instead of the adapter-local WebSocket codec.
     */
    @Deprecated(forRemoval = false)
    public WebSocketTransportFrameCodec getFrameCodec() {
        return deprecatedFrameCodec;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public boolean isGatewayEnabled() {
        return gatewayEnabled;
    }
}
