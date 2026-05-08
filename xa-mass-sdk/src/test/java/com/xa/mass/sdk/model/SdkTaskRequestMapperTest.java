package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskSourceType;
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
                .taskName("interactive-task")
                .eventCode("interactive.run")
                .sharedConfig(Map.of("source", "sdk"))
                .sourceType(TaskSourceType.BATCH)
                .workloadClass(TaskWorkloadClass.INTERACTIVE)
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.INTERACTIVE, dto.getWorkloadClass());
        assertEquals(TaskSourceType.BATCH, dto.getSourceType());
        assertEquals("interactive.run",
                ((Map<?, ?>) dto.getSharedConfig().get("_sdk")).get("eventCode"));
    }

    @Test
    void massTaskShellCreateRequestPreservesSharedConfig() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .taskName("bulk-task")
                .eventCode("crawler.fetch-page")
                .sourceType(TaskSourceType.STREAM)
                .workloadClass(TaskWorkloadClass.BULK)
                .sharedConfig(Map.of("_sdk", Map.of("eventCode", "crawler.fetch-page")))
                .build();

        TaskShellCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.BULK, dto.getWorkloadClass());
        assertEquals(TaskSourceType.STREAM, dto.getSourceType());
        assertEquals("crawler.fetch-page",
                ((Map<?, ?>) dto.getSharedConfig().get("_sdk")).get("eventCode"));
    }
}

