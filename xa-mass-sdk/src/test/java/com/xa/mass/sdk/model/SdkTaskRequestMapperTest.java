package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.TaskExecutionSpec;
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
                .sharedConfig(Map.of("source", "sdk"))
                .executionSpec(spec(TaskContract.SESSION, TaskWorkloadClass.INTERACTIVE, 1, 0))
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.INTERACTIVE, dto.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskContract.SESSION, dto.getExecutionSpec().getContract());
        assertEquals("sdk", dto.getSharedConfig().get("source"));
    }

    @Test
    void massTaskShellCreateRequestPreservesSharedConfig() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .executionSpec(spec(TaskContract.BATCH, TaskWorkloadClass.BULK, 1, 0))
                .sharedConfig(Map.of("eventCode", "crawler.fetch-page"))
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.BULK, dto.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskContract.BATCH, dto.getExecutionSpec().getContract());
        assertEquals("crawler.fetch-page", dto.getSharedConfig().get("eventCode"));
    }

    private TaskExecutionSpec spec(TaskContract contract,
                                   TaskWorkloadClass workloadClass,
                                   int batchSize,
                                   int maxRuntimeSeconds) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setContract(contract);
        spec.setWorkloadClass(workloadClass);
        spec.setBatchSize(batchSize);
        spec.setMaxRuntimeSeconds(maxRuntimeSeconds);
        return spec;
    }
}

