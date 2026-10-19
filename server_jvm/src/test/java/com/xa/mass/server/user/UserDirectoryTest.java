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
    @Test void httpProofSeedAndFocusedFixturesProduceTheSameRoleSnapshot() throws Exception {
        var proof = new UserDirectory(JsonMapper.builder().build(), new ClassPathResource("users/proof-directory.json"),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of()));
        assertThat(proof.roles()).isEqualTo(directory().roles());
        // Fault-injection matchers compare the admitted value, including its immutable grants.
        assertThat(proof.resolveCaller("proof", "admin"))
                .isEqualTo(com.xa.mass.server.testing.TaskCallers.caller("proof", "admin"));
    }
    private UserDirectory directory() throws Exception {
        return new UserDirectory(JsonMapper.builder().build(), new ClassPathResource("users/development-directory.json"),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                        new ProjectDefinition("messages", List.of("g"), null), new ProjectDefinition("app-checks", List.of("g"), null))), List.of()));
    }
    private MockMvc client(UserDirectory directory) {
        return MockMvcBuilders.standaloneSetup(new UserController(directory)).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver(directory)).build();
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
        assertThatThrownBy(() -> state.update(state.resolveCaller("1000", "admin"), "1000", new UserDirectory.UpdateUser("self", "operator", List.of())))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
        var granted = new java.util.ArrayList<>(List.of("messages"));
        var user = state.create(state.resolveCaller("1000", "admin"), new UserDirectory.User("userc", "C", "operator", granted));
        granted.add("app-checks");
        assertThat(user.projectIds()).containsExactly("messages");
        assertThatThrownBy(() -> user.projectIds().add("sms")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void duplicateConcurrentCreatesHaveOneWinner() throws Exception {
        var state = directory();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(index -> (java.util.concurrent.Callable<Boolean>) () -> {
                try { state.create(state.resolveCaller("1000", "admin"), new UserDirectory.User("userc", "C", "operator", List.of("messages"))); return true; }
                catch (com.xa.mass.server.error.ServerException conflict) { return false; }
            }).toList());
            int winners = 0;
            for (var result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(state.users()).hasSize(4);
        }
    }

    @Test void admissionRejectsUnknownUsersAndRoleSpoofingAndUsesDirectoryGrants() throws Exception {
        var state = directory();
        for (var declaration : List.of(new String[]{"missing", "operator"}, new String[]{"1001", "admin"}))
            assertThatThrownBy(() -> state.resolveCaller(declaration[0], declaration[1]))
                    .isInstanceOfSatisfying(com.xa.mass.server.error.ServerException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(com.xa.mass.server.error.ServerErrorCode.TASK_ACCESS_DENIED));
        var actor = state.resolveCaller("1001", "operator");
        actor.requireProject("messages");
        assertThatThrownBy(() -> actor.requireProject("other")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> actor.selectUser("1002")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        var admin = state.resolveCaller("1000", "admin");
        state.update(admin, "1001", new UserDirectory.UpdateUser("A", "operator", List.of("app-checks")));
        actor.requireProject("messages");
        assertThatThrownBy(() -> state.resolveCaller("1001", "operator").requireProject("messages"))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
        state.update(admin, "1001", new UserDirectory.UpdateUser("A", "admin", List.of()));
        assertThatThrownBy(() -> state.resolveCaller("1001", "operator")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        var promoted = state.resolveCaller("1001", "admin");
        assertThat(promoted.identityKey()).isEqualTo(actor.identityKey());
        promoted.requirePermission("users.manage");
        state.update(admin, "1001", new UserDirectory.UpdateUser("A", "operator", List.of("messages")));
        promoted.requirePermission("users.manage");
        assertThatThrownBy(() -> state.resolveCaller("1001", "admin")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> state.resolveCaller("1001", "operator").requirePermission("users.manage"))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
    }

    @Test void admittedBusinessWorkDoesNotHoldTheDirectoryGateOrChangeIdentityMidOperation() throws Exception {
        var state = directory();
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = executor.submit(() -> {
                var caller = state.resolveCaller("1001", "operator");
                admitted.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Business gate timed out");
                caller.requireProject("messages");
                return caller.identityKey();
            });
            try {
                assertThat(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> state.update(state.resolveCaller("1000", "admin"), "1001",
                        new UserDirectory.UpdateUser("A changed", "operator", List.of())))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThatThrownBy(() -> state.resolveCaller("1001", "operator").requireProject("messages"))
                        .isInstanceOf(com.xa.mass.server.error.ServerException.class);
            } finally { release.countDown(); }
            assertThat(work.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("user:1001");
        }
    }

    @Test void accessUsesTheSeedRolePolicyRatherThanRoleNameBranches() throws Exception {
        var defaults = directory();
        var roles = defaults.roles().stream().map(role -> role.id().equals("operator")
                ? new UserDirectory.Role(role.id(), role.label(), List.of("runtime.view"), "all", "assigned", role.home())
                : new UserDirectory.Role(role.id(), role.label(), List.of("users.manage"), role.taskScope(), role.projectScope(), role.home())).toList();
        var mapper = JsonMapper.builder().build();
        var state = new UserDirectory(mapper, new org.springframework.core.io.ByteArrayResource(
                mapper.writeValueAsBytes(new UserDirectory.Seed(roles, defaults.users()))),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of()));
        var operator = state.resolveCaller("1001", "operator");
        operator.requirePermission("runtime.view");
        assertThat(operator.canRead("1002")).isTrue();
        assertThat(operator.selectUser("1002")).isEqualTo("1002");
        assertThatThrownBy(() -> operator.requireProject("unassigned")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> state.resolveCaller("1000", "admin").requirePermission("runtime.view"))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
    }
}
