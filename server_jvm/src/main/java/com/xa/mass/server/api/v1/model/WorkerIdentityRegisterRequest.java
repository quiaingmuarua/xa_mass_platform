package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;

public record WorkerIdentityRegisterRequest(
        @NotBlank String clientWorkerKey
) {
}
