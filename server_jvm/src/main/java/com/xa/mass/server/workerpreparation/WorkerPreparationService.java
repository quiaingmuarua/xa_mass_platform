package com.xa.mass.server.workerpreparation;

import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointBinding;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.server.workeridentity.WorkerIdentityService;
import java.net.URI;
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

    public PreparedWorker prepare(
            String workerGroupId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties
    ) {
        String workerId = identities.register(
                workerGroupId,
                workerProperties
        );
        WorkerEndpointBinding binding = bindings.bind(
                workerGroupId,
                workerId,
                transportType,
                workerProperties
        );
        return new PreparedWorker(
                workerId,
                binding.transportType(),
                binding.endpointUri()
        );
    }

    public record PreparedWorker(
            String workerId,
            WorkerTransportType transportType,
            URI endpointUri
    ) {
    }
}
