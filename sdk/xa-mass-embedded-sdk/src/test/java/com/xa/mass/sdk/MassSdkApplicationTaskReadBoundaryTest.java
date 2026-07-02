package com.xa.mass.sdk;

import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskAccessSnapshot;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskSummarySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassSdkApplicationTaskReadBoundaryTest {

    @Test
    void taskReadsUseReadProjectionAfterCreateAndLifecycleCommands() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(true))
                .build();
        try {
            app.start();

            TaskExecutionOptions execution = new TaskExecutionOptions();
            execution.setBatchSize(3);
            TaskShellSnapshot created = app.createTaskShell(MassTaskShellCreateRequest.builder()
                    .tenantId("tenant-a")
                    .project("demoApp")
                    .userId("owner-a")
                    .contract("BATCH")
                    .sourceRef("input/read-boundary.csv")
                    .sharedConfig(Map.of("workerGroupId", "read-boundary-workers"))
                    .executionSpec(execution)
                    .build());

            assertNotNull(created.getTaskId());
            assertEquals("tenant-a", created.getTenantId());
            assertEquals("demoApp", created.getProject());
            assertEquals("owner-a", created.getUserId());
            assertEquals("BATCH", created.getContract());
            assertEquals("input/read-boundary.csv", created.getSourceRef());

            TaskDetailSnapshot detail = app.getTaskDetail(created.getTaskId());
            assertNotNull(detail);
            assertEquals("NEW", detail.getStatus());
            assertEquals("OPEN", detail.getIntakeStatus());
            assertEquals(3, detail.getExecutionSpec().getBatchSize());
            assertEquals("read-boundary-workers", detail.getSharedConfig().get("workerGroupId"));

            TaskAccessSnapshot access = app.getTaskAccess(created.getTaskId());
            assertNotNull(access);
            assertEquals("demoApp", access.getProject());
            assertEquals("OPEN", access.getIntakeStatus());
            assertEquals("read-boundary-workers", access.getSharedConfig().get("workerGroupId"));

            assertTrue(app.approveTask(created.getTaskId()));
            TaskStateSnapshot approvedState = app.getTaskState(created.getTaskId());
            assertNotNull(approvedState);
            assertEquals("READY", approvedState.getStatus());

            List<TaskSummarySnapshot> readyTasks = app.getTaskSummariesByStatus("READY");
            assertEquals(1, readyTasks.size());
            assertEquals(created.getTaskId(), readyTasks.get(0).getTaskId());
            assertEquals("READY", readyTasks.get(0).getStatus());

            assertTrue(app.sealTask(created.getTaskId()));
            TaskDetailSnapshot sealed = app.getTaskDetail(created.getTaskId());
            assertNotNull(sealed);
            assertEquals("SEALED", sealed.getIntakeStatus());
            assertFalse(app.getTaskSummariesByStatus("PAUSED").stream()
                    .anyMatch(task -> created.getTaskId().equals(task.getTaskId())));
        } finally {
            app.stop();
        }
    }
}
