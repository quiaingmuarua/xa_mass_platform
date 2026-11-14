package com.xa.mass.server.worker.connection;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationAlreadyRunningException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.server.worker.endpoint.WorkerTransportType;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class WorkerConnectionControlService {
    private final WorkerResourceCatalog catalog;
    private final WorkerEndpointDirectory endpoints;
    private final WorkerDeliveryAdapterManager adapters;
    private final WorkerConnectionControlStore store;
    private final OperationGuard operations;
    private final Clock clock;

    @Autowired
    public WorkerConnectionControlService(WorkerResourceCatalog catalog, WorkerEndpointDirectory endpoints,
            WorkerDeliveryAdapterManager adapters, WorkerConnectionControlStore store, OperationGuard operations) {
        this(catalog, endpoints, adapters, store, operations, Clock.systemUTC());
    }
    WorkerConnectionControlService(WorkerResourceCatalog catalog, WorkerEndpointDirectory endpoints,
            WorkerDeliveryAdapterManager adapters, WorkerConnectionControlStore store, OperationGuard operations, Clock clock) {
        this.catalog = catalog;
        this.endpoints = endpoints;
        this.adapters = adapters;
        this.store = store;
        this.operations = operations;
        this.clock = clock;
    }

    public View get(TaskCaller caller, String groupId, String workerId) {
        caller.requireAdmin();
        try {
            WorkerDescriptor worker = requireWorker(groupId, workerId);
            return view(worker, store.read(groupId, workerId), clock.millis());
        } catch (ServerException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(error); }
    }

    public View put(TaskCaller caller, String groupId, String workerId, WorkerConnectionControlStore.Change change) {
        caller.requireAdmin();
        try {
            return operations.execute("worker-connection-control", workerId, () -> {
                WorkerDescriptor worker = requireWorker(groupId, workerId);
                var endpoint = endpoints.find(worker.endpointManagerId());
                if (endpoint == null || endpoint.transportType() != WorkerTransportType.WEBSOCKET)
                    throw invalid("Connection cooldown requires a WebSocket Worker");
                // Validation precedes persistence; revocation after persistence is an independent effect.
                adapters.requireConnectionControl(worker.endpointManagerId());
                var control = store.update(groupId, workerId, change, caller.userId(), clock.millis());
                if (control.cooling(clock.millis()))
                    adapters.revokeWorkerConnection(worker.endpointManagerId(), workerId, control.cooldownUntilMillis());
                return view(worker, control, clock.millis());
            });
        } catch (WorkerConnectionControlStore.Conflict | OperationAlreadyRunningException conflict) {
            throw new ServerException(ServerErrorCode.WORKER_CONNECTION_CONTROL_CONFLICT,
                    "workerConnectionControl.put", null, conflict);
        } catch (ServerException error) { throw error; }
        catch (IllegalArgumentException invalid) { throw invalid(invalid.getMessage()); }
        catch (RuntimeException error) { throw unavailable(error); }
    }

    private WorkerDescriptor requireWorker(String groupId, String workerId) {
        if (groupId == null || groupId.isBlank() || workerId == null || workerId.isBlank()) throw invalid("Group and Worker are required");
        var worker = catalog.getWorkerDescriptors(List.of(workerId)).get(workerId);
        if (worker == null || !groupId.equals(worker.workerGroupId()))
            throw new ServerException(ServerErrorCode.WORKER_RESOURCE_NOT_FOUND, "workerConnectionControl.requireWorker", null, null);
        return worker;
    }

    private View view(WorkerDescriptor worker, WorkerConnectionControlStore.Control control, long now) {
        var endpoint = endpoints.find(worker.endpointManagerId());
        String transport = endpoint == null ? null : endpoint.transportType().name();
        String unsupported = null;
        if (endpoint == null || endpoint.transportType() != WorkerTransportType.WEBSOCKET) unsupported = "websocket_required";
        else {
            try { adapters.requireConnectionControl(worker.endpointManagerId()); }
            catch (IllegalStateException unavailable) { unsupported = "adapter_not_running_here"; }
        }
        return new View(worker.workerGroupId(), worker.workerId(), transport, unsupported == null, unsupported,
                control != null && control.cooling(now) ? "cooldown" : "allowed",
                control == null ? null : control.revision(), control == null ? null : control.requestId(),
                control == null ? null : control.cooldownUntilMillis(), control == null ? null : control.reason(),
                control == null ? null : control.updatedAt(), now);
    }
    private static ServerException invalid(String message) {
        return new ServerException(ServerErrorCode.INVALID_WORKER_CONNECTION_CONTROL,
                "workerConnectionControl.put", message, null);
    }
    private static ServerException unavailable(Throwable cause) {
        return new ServerException(ServerErrorCode.WORKER_CONNECTION_CONTROL_UNAVAILABLE,
                "workerConnectionControl.access", null, cause);
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "WorkerConnectionControlView")
    public record View(String workerGroupId, String workerId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String transportType,
            boolean supported,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String unsupportedReason,
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String revision,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String requestId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Long cooldownUntilMillis,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String reason,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Long updatedAt,
            long readAt) { }
}
