package com.xa.mass.server.api;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.user.UserDirectory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccessKeyFilterTest {
    @RestController
    static final class Routes {
        @PostMapping("/api/v1/worker-groups/{group}/workers:prepare") String prepare(@PathVariable String group) { return "prepare:" + group; }
        @PostMapping("/api/v1/worker-groups/{group}:register") String register(@PathVariable String group) { return "register"; }
        @PostMapping("/api/v1/worker-delivery/endpoint-managers/{manager}/commands:consume") String consume(@PathVariable String manager) { return "consume"; }
        @PostMapping("/api/v1/worker-delivery/endpoint-managers/{manager}/direct-calls") String direct(@PathVariable String manager) { return "direct"; }
        @GetMapping("/api/v1/whoami") String whoami(TaskCaller caller) { return caller.userId(); }
        @GetMapping("/static/page") String page() { return "page"; }
    }

    private static MockMvc client() {
        UserDirectory directory = TaskCallers.directory(new UserDirectory.User("1000", "Admin", "admin", List.of()));
        return MockMvcBuilders.standaloneSetup(new Routes()).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver())
                .addFilters(new RequestIdFilter(), new AccessKeyFilter(directory, JsonMapper.builder().build())).build();
    }

    @Test void workerAndInternalMachineRoutesAreTheOnlyExemptApiRoutes() throws Exception {
        var client = client();
        client.perform(post("/api/v1/worker-groups/g-1/workers:prepare")).andExpect(status().isOk()).andExpect(content().string("prepare:g-1"));
        client.perform(post("/api/v1/worker-delivery/endpoint-managers/m/commands:consume")).andExpect(status().isOk());
        client.perform(get("/static/page")).andExpect(status().isOk());
        for (var secured : List.of(post("/api/v1/worker-groups/g:register"), post("/api/v1/worker-delivery/endpoint-managers/m/direct-calls"),
                get("/api/v1/whoami"), get("/api/v1/worker-groups/g/workers:prepare"),
                post("/api/v1/worker-groups/g%2Fx/workers:prepare"), post("/api/v1/worker-groups/g/workers:prepare;x=1")))
            client.perform(secured).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(18008))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        client.perform(get("/api/v1/whoami").header("Authorization", "bearer " + TaskCallers.devKey("1000")))
                .andExpect(status().isOk()).andExpect(content().string("1000"));
    }

    @Test void openApiTemplatesUseTheSameExemptionTable() {
        assertThat(AccessKeyFilter.exemptTemplate("POST", "/api/v1/worker-groups/{workerGroupId}/workers:prepare-batch")).isTrue();
        assertThat(AccessKeyFilter.exemptTemplate("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/direct-calls")).isFalse();
        assertThat(AccessKeyFilter.exemptTemplate("GET", "/api/v1/worker-groups/{workerGroupId}/workers:prepare")).isFalse();
    }
}
