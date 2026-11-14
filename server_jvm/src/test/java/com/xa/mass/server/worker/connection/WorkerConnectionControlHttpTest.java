package com.xa.mass.server.worker.connection;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.server.api.*;
import com.xa.mass.server.api.v1.controller.WorkerConnectionControlController;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.user.UserDirectory;
import com.xa.mass.server.worker.endpoint.*;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class WorkerConnectionControlHttpTest {
    private final WorkerConnectionControlStore store = mock(WorkerConnectionControlStore.class);
    private final WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
    private final WorkerDeliveryAdapterManager adapters = mock(WorkerDeliveryAdapterManager.class);
    private UserDirectory users;
    private MockMvc http;
    private static final String PATH = "/api/v1/worker-groups/g/workers/w/connection-control";
    @BeforeEach void setup() {
        users = com.xa.mass.server.testing.TaskCallers.directory(
                new UserDirectory.User("admin", "Admin", "admin", List.of()),
                new UserDirectory.User("operator", "Operator", "operator", List.of()));
        var endpoints = new WorkerEndpointDirectory(Map.of("adapter", new WorkerEndpointDirectory.Endpoint(
                WorkerTransportType.WEBSOCKET, URI.create("ws://localhost/worker"))), Map.of(WorkerTransportType.WEBSOCKET, "adapter"));
        var service = new WorkerConnectionControlService(catalog, endpoints, adapters, store, new OperationGuard());
        http = MockMvcBuilders.standaloneSetup(new WorkerConnectionControlController(service))
                .setControllerAdvice(new ApiExceptionHandler()).setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver())
                .addFilters(new AccessKeyFilter(users, JsonMapper.builder().build())).build();
        when(catalog.getWorkerDescriptors(List.of("w"))).thenReturn(Map.of("w", new WorkerDescriptor("w", "g", "adapter")));
    }
    @AfterEach void close() throws Exception { users.close(); }
    @Test void nonAdminsCannotReadOrMutateControl() throws Exception {
        http.perform(get(PATH)).andExpect(status().isUnauthorized());
        http.perform(get(PATH).header("Authorization", "Bearer xak_dev_operator")).andExpect(status().isForbidden());
        http.perform(put(PATH).header("Authorization", "Bearer xak_dev_operator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"q\",\"cooldownUntilMillis\":null,\"reason\":\"\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store, catalog, adapters);
    }
    @Test void malformedDeadlinesAreRejectedBeforeStorage() throws Exception {
        http.perform(put(PATH).header("Authorization", "Bearer xak_dev_admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"q\",\"reason\":\"missing deadline is not release\"}"))
                .andExpect(status().isBadRequest());
        for (String value : List.of("1.5", "\"1000\"", "{}", "[]", "true", "9007199254740992")) {
            http.perform(put(PATH).header("Authorization", "Bearer xak_dev_admin").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"q\",\"cooldownUntilMillis\":" + value + ",\"reason\":\"maintenance\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store, catalog, adapters);
    }
    @Test void policyReadDoesNotClaimConnectionOrSchedulingState() throws Exception {
        http.perform(get(PATH).header("Authorization", "Bearer xak_dev_admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("allowed"))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.connectionState").doesNotExist()).andExpect(jsonPath("$.schedulingState").doesNotExist());
        verify(store).read("g", "w");
    }
}
