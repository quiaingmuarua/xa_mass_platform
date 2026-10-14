package com.xa.mass.server.user;

import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.TaskCallerHttpConfiguration;
import com.xa.mass.server.api.v1.controller.UserController;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserDirectoryTest {
    private UserDirectory directory() throws Exception {
        return new UserDirectory(JsonMapper.builder().build(), new ClassPathResource("users/development-directory.json"),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                        new ProjectDefinition("messages", List.of("g")), new ProjectDefinition("app-checks", List.of("g")))), List.of()));
    }
    private MockMvc client(UserDirectory directory) {
        return MockMvcBuilders.standaloneSetup(new UserController(directory)).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver()).build();
    }
    @Test void independentClientsShareWritesWhileANewServerDirectoryReloadsTheSeed() throws Exception {
        var state = directory();
        var first = client(state);
        var second = client(state);
        first.perform(post("/api/v1/users").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"userId":"userc","displayName":"User C","role":"operator","projectIds":["messages"]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("userc"));
        second.perform(get("/api/v1/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$[3].projectIds[0]").value("messages"));
        second.perform(put("/api/v1/users/userc").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"displayName":"User C updated","role":"operator","projectIds":["app-checks"]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds[0]").value("app-checks"));
        first.perform(get("/api/v1/users")).andExpect(jsonPath("$[3].displayName").value("User C updated"));
        client(directory()).perform(get("/api/v1/users")).andExpect(jsonPath("$.length()").value(3));
        first.perform(get("/api/v1/roles")).andExpect(jsonPath("$[1].permissions").value(org.hamcrest.Matchers.hasItem("messages.use")));
    }
    @Test void rejectsUnauthorizedInvalidAndConflictingChangesWithoutChangingTheDirectory() throws Exception {
        var state = directory();
        var client = client(state);
        String valid = """
                {"userId":"userc","displayName":"User C","role":"operator","projectIds":["messages"]}
                """;
        for (String declaredRole : List.of("operator", "admin"))
            client.perform(post("/api/v1/users").header("X-XA-Dev-User", "MTAwMQ").header("X-XA-Dev-Role", declaredRole)
                    .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        for (String invalid : List.of(valid.replace("userc", "1001"), valid.replace("operator", "service"),
                valid.replace("messages", "unknown"), valid.replace("userc", "INVALID")))
            client.perform(post("/api/v1/users").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        assertThat(state.users()).hasSize(3);
        assertThatThrownBy(() -> state.update(new TaskCaller("1000", "admin"), "1000", new UserDirectory.UpdateUser("self", "operator", List.of())))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
        var granted = new java.util.ArrayList<>(List.of("messages"));
        var user = state.create(new TaskCaller("1000", "admin"), new UserDirectory.User("userc", "C", "operator", granted));
        granted.add("app-checks");
        assertThat(user.projectIds()).containsExactly("messages");
        assertThatThrownBy(() -> user.projectIds().add("sms")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void duplicateConcurrentCreatesHaveOneWinner() throws Exception {
        var state = directory();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(index -> (java.util.concurrent.Callable<Boolean>) () -> {
                try { state.create(new TaskCaller("1000", "admin"), new UserDirectory.User("userc", "C", "operator", List.of("messages"))); return true; }
                catch (com.xa.mass.server.error.ServerException conflict) { return false; }
            }).toList());
            int winners = 0;
            for (var result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(state.users()).hasSize(4);
        }
    }
}
