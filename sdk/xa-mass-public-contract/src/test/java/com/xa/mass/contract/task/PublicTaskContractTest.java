package com.xa.mass.contract.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicTaskContractTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void createRequestBuilderWritesRoutingKeysIntoSharedConfig() throws Exception {
        TaskCreateRequest request = TaskCreateRequest.builder()
                .project("crawlerApp")
                .contract(TaskContract.BATCH)
                .workerGroupId("crawler-workers")
                .targetWorkerAttribute("region", "us")
                .routingCode("us")
                .routeAttribute("region", "us")
                .build();

        assertEquals("crawler-workers", request.sharedConfig().get(TaskSharedConfigKeys.WORKER_GROUP_ID));
        assertEquals(Map.of("region", "us"),
                request.sharedConfig().get(TaskSharedConfigKeys.TARGET_WORKER_ATTRIBUTES));
        assertEquals("us", request.sharedConfig().get(TaskSharedConfigKeys.ROUTING_CODE));
        assertEquals(Map.of("region", "us"), request.sharedConfig().get(TaskSharedConfigKeys.ROUTE_ATTRIBUTES));

        JsonNode json = OBJECT_MAPPER.valueToTree(request);
        assertEquals("BATCH", json.get("contract").asText());
        assertEquals("crawler-workers", json.get("sharedConfig").get("workerGroupId").asText());
    }

    @Test
    void createRequestBuilderKeepsWorkerGroupSelectorsMutuallyExclusive() {
        TaskCreateRequest request = TaskCreateRequest.builder()
                .workerGroupId("old-group")
                .workerGroupIds(Arrays.asList("pool-east", null, " ", "pool-west"))
                .build();

        assertEquals(List.of("pool-east", "pool-west"),
                request.sharedConfig().get(TaskSharedConfigKeys.WORKER_GROUP_IDS));
        assertFalse(request.sharedConfig().containsKey(TaskSharedConfigKeys.WORKER_GROUP_ID));
    }

    @Test
    void unknownFieldsRemainVisibleToControllers() throws Exception {
        TaskItemBatch request = OBJECT_MAPPER.readValue("""
                {"eventCode":"crawler.fetch-page","items":[],"extra":true}
                """, TaskItemBatch.class);

        assertTrue(request.hasUnknownFields());
        assertEquals(List.of("extra"), request.getUnknownFieldNames());
    }

    @Test
    void executionSpecRejectsLegacyContractField() {
        JsonMappingException exception = assertThrows(JsonMappingException.class, () -> OBJECT_MAPPER.readValue("""
                {"contract":"BATCH"}
                """, TaskExecutionSpec.class));
        assertTrue(exception.getMessage().contains("executionSpec.contract has been removed"));
    }
}
