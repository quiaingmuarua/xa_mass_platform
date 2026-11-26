package com.xa.mass.server.api.v1.contract.worker;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(name = "WorkerGroupDirectoryPageResponse")
public record WorkerGroupDirectoryPageResponse(
        String nextCursor,
        int unreadableCount,
        List<WorkerGroupManagedView> groups,
        Instant readAt
) {
    public WorkerGroupDirectoryPageResponse {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    @Schema(name = "WorkerGroupManagedView")
    public record WorkerGroupManagedView(
            String workerGroupId,
            Map<String, Object> attributes,
            List<String> eventCodes,
            String source,
            boolean editable,
            boolean deletableByConfig,
            List<String> protectionReasons,
            List<String> declaredProjects,
            WorkerGroupWorkerCounts workerCounts
    ) {
        public WorkerGroupManagedView {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            eventCodes = eventCodes == null ? List.of() : List.copyOf(eventCodes);
            protectionReasons = protectionReasons == null ? List.of() : List.copyOf(protectionReasons);
            declaredProjects = declaredProjects == null ? List.of() : List.copyOf(declaredProjects);
        }
    }

    @Schema(name = "WorkerGroupWorkerCounts")
    public record WorkerGroupWorkerCounts(
            long total,
            long activeHot,
            long pausedActive,
            long recovery,
            long cold,
            long pausedOffline,
            long activeTotal
    ) {
    }
}
