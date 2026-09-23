package com.xa.mass.server.worker.preparation;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerRegistrationResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory.Endpoint;
import com.xa.mass.server.worker.endpoint.WorkerTransportType;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class WorkerPreparationService {

    private final WorkerIdentityService identities;
    private final WorkerEndpointDirectory endpoints;
    private final WorkerResourceCatalog catalog;

    public WorkerPreparationService(
            WorkerIdentityService identities,
            WorkerEndpointDirectory endpoints,
            WorkerResourceCatalog catalog
    ) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public List<PreparedWorker> prepareAll(
            String workerGroupId,
            WorkerRegistrationKind workerKind,
            WorkerTransportType transportType,
            List<Map<String, Object>> workerProperties
    ) {
        String operation = "workerPreparation.prepareAll";
        if (workerGroupId == null || workerGroupId.isBlank() || workerKind == null
                || transportType == null
                || workerProperties == null
                || workerProperties.isEmpty()
                || workerProperties.size() > 100) {
            throw invalidRequest(
                    operation,
                    "Preparation must contain 1..100 Workers"
            );
        }

        HashSet<String> uniqueRegistrationKeys = new HashSet<>();
        List<String> registrationKeys = new ArrayList<>();
        for (Map<String, Object> properties : workerProperties) {
            String registrationKey = identities.registrationKey(
                    workerKind,
                    properties
            );
            registrationKeys.add(registrationKey);
            if (!uniqueRegistrationKeys.add(registrationKey)) {
                throw invalidRequest(
                        operation,
                        "Worker registration coordinates must be unique"
                );
            }
        }

        String defaultEndpointId;
        try {
            if (catalog.getWorkerGroupDescriptors(List.of(workerGroupId)).get(workerGroupId) == null) {
                throw failure(ServerErrorCode.WORKER_GROUP_NOT_FOUND, operation, "WorkerGroup was not found", null);
            }
            defaultEndpointId = endpoints.defaultEndpointId(transportType);
            if (defaultEndpointId == null) {
                throw failure(ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE, operation,
                        "No default Endpoint is configured for " + transportType, null);
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(ServerErrorCode.WORKER_BINDING_UNAVAILABLE, operation, null, error);
        }
        List<String> workerIds = identities.registerAll(workerGroupId, registrationKeys);
        Map<String, WorkerRegistrationResult> registrations;
        try {
            registrations = catalog.registerWorkers(workerGroupId, workerIds, defaultEndpointId);
        } catch (RuntimeException error) {
            throw failure(ServerErrorCode.WORKER_BINDING_UNAVAILABLE, operation, null, error);
        }
        // The whole batch has run. A later error does not imply prefix-only side effects.
        List<PreparedWorker> prepared = new ArrayList<>();
        for (String workerId : workerIds) {
            WorkerRegistrationResult result = registrations.get(workerId);
            switch (result.status()) {
                case NOT_FOUND -> throw failure(ServerErrorCode.WORKER_GROUP_NOT_FOUND, operation, result.reason(), null);
                case CONFLICT -> throw failure(ServerErrorCode.WORKER_BINDING_CONFLICT, operation, result.reason(), null);
                case INVALID -> throw failure(ServerErrorCode.INVALID_WORKER_BINDING_REQUEST, operation, result.reason(), null);
                case OK, NOOP -> { }
            }
            Endpoint endpoint = endpoints.find(result.endpointManagerId());
            if (endpoint == null) {
                throw failure(ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE, operation,
                        "Bound Endpoint is not present in the Endpoint directory", null);
            }
            if (endpoint.transportType() != transportType) {
                throw failure(ServerErrorCode.WORKER_BINDING_CONFLICT, operation,
                        "Worker is already bound to a different transport; registration does not migrate bindings", null);
            }
            prepared.add(new PreparedWorker(workerId, endpoint.transportType(), endpoint.publicUri()));
        }
        return List.copyOf(prepared);
    }

    private static ServerException invalidRequest(
            String operation,
            String message
    ) {
        return new ServerException(
                ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                operation,
                message,
                null
        );
    }

    private static ServerException failure(ServerErrorCode code, String operation, String message, Throwable cause) {
        return new ServerException(code, operation, message, cause);
    }

    public record PreparedWorker(
            String workerId,
            WorkerTransportType transportType,
            URI endpointUri
    ) {
    }

}
