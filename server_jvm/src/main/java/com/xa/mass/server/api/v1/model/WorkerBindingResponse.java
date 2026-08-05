package com.xa.mass.server.api.v1.model;

import com.xa.mass.server.workerbinding.WorkerEndpointBinding;

public record WorkerBindingResponse(
        String transportType,
        String endpointUri
) {

    public static WorkerBindingResponse from(WorkerEndpointBinding binding) {
        return new WorkerBindingResponse(
                binding.transportType().name(),
                binding.endpointUri().toString()
        );
    }
}
