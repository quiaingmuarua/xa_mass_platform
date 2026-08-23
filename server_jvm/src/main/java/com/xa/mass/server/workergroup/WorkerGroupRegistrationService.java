package com.xa.mass.server.workergroup;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallRegistrationService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class WorkerGroupRegistrationService {

    private static final String OPERATION = "workerGroup.register";

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerGroupTaskCallRegistrationService taskCallRegistrations;

    public WorkerGroupRegistrationService(
            WorkerResourceCatalog workerCatalog,
            WorkerGroupTaskCallRegistrationService taskCallRegistrations
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.taskCallRegistrations = Objects.requireNonNull(
                taskCallRegistrations,
                "taskCallRegistrations"
        );
    }

    public Registration register(
            String workerGroupId,
            Map<String, Object> attributes,
            List<String> eventCodes
    ) {
        requireValid(workerGroupId, attributes, eventCodes);
        WorkerRuntimeResult result;
        try {
            result = workerCatalog.registerWorkerGroup(
                    new WorkerGroupDescriptor(
                            workerGroupId,
                            attributes,
                            new LinkedHashSet<>(eventCodes)
                    )
            );
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    null,
                    error
            );
        }
        if (result == null) {
            throw failure(
                    ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    null,
                    null
            );
        }
        boolean groupRegistered = switch (result.status()) {
            case OK -> true;
            case NOOP -> false;
            case CONFLICT -> throw failure(
                    ServerErrorCode.WORKER_GROUP_REGISTRATION_CONFLICT,
                    result.reason(),
                    null
            );
            case INVALID, NOT_FOUND, REJECTED, STALE -> throw failure(
                    ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    result.reason(),
                    null
            );
        };
        WorkerGroupTaskCallRegistrationService.Registration taskCall =
                taskCallRegistrations.register(workerGroupId);
        return new Registration(
                workerGroupId,
                groupRegistered || taskCall.newlyRegistered()
                        ? "registered"
                        : "already_registered"
        );
    }

    private static void requireValid(
            String workerGroupId,
            Map<String, Object> attributes,
            List<String> eventCodes
    ) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw invalid("workerGroupId must be non-blank");
        }
        if (attributes == null) {
            throw invalid("attributes must be present");
        }
        if (eventCodes == null) {
            throw invalid("eventCodes must be present");
        }
        var unique = new LinkedHashSet<String>();
        for (String eventCode : eventCodes) {
            if (eventCode == null || eventCode.isBlank()) {
                throw invalid("eventCodes must contain non-blank strings");
            }
            if (!unique.add(eventCode)) {
                throw invalid("eventCodes must be unique");
            }
        }
    }

    private static ServerException invalid(String message) {
        return failure(
                ServerErrorCode.INVALID_WORKER_GROUP_REQUEST,
                message,
                null
        );
    }

    private static ServerException failure(
            ServerErrorCode code,
            String message,
            Throwable cause
    ) {
        return new ServerException(code, OPERATION, message, cause);
    }

    public record Registration(String workerGroupId, String status) {
    }
}
