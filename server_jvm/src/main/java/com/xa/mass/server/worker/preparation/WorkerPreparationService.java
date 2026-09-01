package com.xa.mass.server.worker.preparation;

import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointBinding;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
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
    private final WorkerBindingService bindings;

    public WorkerPreparationService(
            WorkerIdentityService identities,
            WorkerBindingService bindings
    ) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public List<PreparedWorker> prepareAll(
            String workerGroupId,
            WorkerRegistrationKind workerKind,
            WorkerTransportType transportType,
            List<Map<String, Object>> workerProperties
    ) {
        String operation = "workerPreparation.prepareAll";
        if (workerKind == null
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
        for (Map<String, Object> properties : workerProperties) {
            String registrationKey = identities.registrationKey(
                    workerKind,
                    properties
            );
            if (!uniqueRegistrationKeys.add(registrationKey)) {
                throw invalidRequest(
                        operation,
                        "Worker registration coordinates must be unique"
                );
            }
        }

        List<PreparedWorker> prepared = new ArrayList<>();
        for (Map<String, Object> properties : workerProperties) {
            String workerId = identities.register(
                    workerGroupId,
                    workerKind,
                    properties
            );
            WorkerEndpointBinding binding = bindings.bind(
                    workerGroupId,
                    workerId,
                    workerKind,
                    transportType,
                    properties
            );
            prepared.add(new PreparedWorker(
                    workerId,
                    binding.transportType(),
                    binding.endpointUri()
            ));
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

    public record PreparedWorker(
            String workerId,
            WorkerTransportType transportType,
            URI endpointUri
    ) {
    }

}
