package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkTaskRequestMapperTest {

    @Test
    void massTaskCreateRequestMapsExplicitWorkloadClass() {
        MassTaskCreateRequest request = MassTaskCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .taskName("interactive-task")
                .inputs(List.of(Map.of("target", "alpha")))
                .sourceType(TaskSourceType.BATCH)
                .workloadClass(TaskWorkloadClass.INTERACTIVE)
                .build();

        TaskCreateRequestDto dto = SdkResourceMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.INTERACTIVE, dto.getWorkloadClass());
        assertEquals(TaskSourceType.BATCH, dto.getSourceType());
    }

    @Test
    void massTaskRequestMapsExplicitWorkloadClass() {
        MassTaskRequest request = MassTaskRequest.builder()
                .userId("agent")
                .project("demoApp")
                .taskName("bulk-task")
                .eventCode("crawler.fetch-page")
                .jsonInputs(List.of(Map.of("url", "https://example.test")))
                .sourceType(TaskSourceType.STREAM)
                .workloadClass(TaskWorkloadClass.BULK)
                .build();

        TaskCreateRequestDto dto = MassTaskRequestMapper.toEngineRequest(request);

        assertEquals(TaskWorkloadClass.BULK, dto.getWorkloadClass());
        assertEquals(TaskSourceType.STREAM, dto.getSourceType());
    }
}
