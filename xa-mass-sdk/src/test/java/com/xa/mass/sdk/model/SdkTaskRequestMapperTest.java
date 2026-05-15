package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkTaskRequestMapperTest {

    @Test
    void massTaskShellCreateRequestMapsExplicitWorkloadClass() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .contract(TaskContract.SESSION.name())
                .sharedConfig(Map.of("source", "sdk"))
                .executionSpec(spec(TaskWorkloadClass.INTERACTIVE, 1, 0))
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.INTERACTIVE, dto.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskContract.SESSION, dto.getContract());
        assertEquals("sdk", dto.getSharedConfig().get("source"));
        assertEquals(true, dto.getExecutionSpec().isForeground());
    }

    @Test
    void massTaskShellCreateRequestPreservesSharedConfig() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .contract(TaskContract.BATCH.name())
                .executionSpec(spec(TaskWorkloadClass.BULK, 1, 0))
                .sharedConfig(Map.of("eventCode", "crawler.fetch-page"))
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.BULK, dto.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskContract.BATCH, dto.getContract());
        assertEquals("crawler.fetch-page", dto.getSharedConfig().get("eventCode"));
    }

    @Test
    void massTaskShellCreateRequestMapsForegroundSchedulingMode() {
        TaskExecutionOptions executionSpec = spec(TaskWorkloadClass.BULK, 4, 60);
        executionSpec.setForeground(false);
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .contract(TaskContract.BATCH.name())
                .executionSpec(executionSpec)
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(false, dto.getExecutionSpec().isForeground());
        assertEquals(TaskWorkloadClass.BULK, dto.getExecutionSpec().getWorkloadClass());
        assertEquals(4, dto.getExecutionSpec().getBatchSize());
    }

    private TaskExecutionOptions spec(TaskWorkloadClass workloadClass,
                                      int batchSize,
                                      int maxRuntimeSeconds) {
        TaskExecutionOptions spec = new TaskExecutionOptions();
        spec.setWorkloadClass(workloadClass.name());
        spec.setBatchSize(batchSize);
        spec.setMaxRuntimeSeconds(maxRuntimeSeconds);
        return spec;
    }
}

