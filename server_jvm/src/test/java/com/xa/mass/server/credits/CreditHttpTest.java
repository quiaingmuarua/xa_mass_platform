package com.xa.mass.server.credits;

import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.TaskCallerHttpConfiguration;
import com.xa.mass.server.api.v1.controller.CreditController;
import com.xa.mass.server.api.v1.controller.UserController;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.*;
import com.xa.mass.server.project.*;
import com.xa.mass.server.user.UserDirectory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CreditHttpTest {
    final CreditAccountStore store = mock(CreditAccountStore.class);
    UserDirectory users;
    MockMvc http;
    final String path = "/api/v1/users/1001/credits";
    static UserDirectory directory() throws Exception {
        return new UserDirectory(JsonMapper.builder().build(), new ClassPathResource("users/development-directory.json"),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                        new ProjectDefinition("messages", List.of("g"), null), new ProjectDefinition("app-checks", List.of("g"), null))), List.of()));
    }
    @BeforeEach void start() throws Exception {
        users = directory();
        http = MockMvcBuilders.standaloneSetup(new CreditController(new CreditService(users, store)), new UserController(users))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver(users)).build();
    }
    @Test void authorizationAndInputRejectionsNeverTouchAccounts() throws Exception {
        http.perform(get(path)).andExpect(status().isBadRequest());
        http.perform(get(path).header("X-XA-Dev-User", "MTAwMg").header("X-XA-Dev-Role", "operator")).andExpect(status().isForbidden());
        http.perform(get(path).header("X-XA-Dev-User", "MTAwMQ").header("X-XA-Dev-Role", "admin")).andExpect(status().isForbidden());
        http.perform(get(path).header("X-XA-Dev-User", "MTAwMQ").header("X-XA-Dev-Role", "service")).andExpect(status().isBadRequest());
        http.perform(post(path + "/adjustments").header("X-XA-Dev-User", "MTAwMQ").header("X-XA-Dev-Role", "operator")
                .contentType(MediaType.APPLICATION_JSON).content(body("1"))).andExpect(status().isForbidden());
        for (String amount : List.of("0", "1.5", "1.0", "\"1\"", "9007199254740992", "-9007199254740992", "null"))
            http.perform(post(path + "/adjustments").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                    .contentType(MediaType.APPLICATION_JSON).content(body(amount))).andExpect(status().isBadRequest());
        http.perform(post(path + "/adjustments").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(body("1").replace("reason", "  "))).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void ownAccountNeedsNoProjectAndPublicDirectoryDoesNotExposeCredits() throws Exception {
        users.update(users.resolveCaller("1000", "admin"), "1001", new UserDirectory.UpdateUser("Renamed", "operator", List.of()));
        when(store.account("1001")).thenReturn(new CreditAccountStore.Account(false, 0, 0, 0));
        http.perform(get(path).header("X-XA-Dev-User", "MTAwMQ").header("X-XA-Dev-Role", "operator"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0)).andExpect(jsonPath("$.updatedAtMillis").isEmpty());
        http.perform(get("/api/v1/users")).andExpect(status().isOk()).andExpect(jsonPath("$[0].balance").doesNotExist());
        http.perform(get("/api/v1/roles")).andExpect(status().isOk()).andExpect(jsonPath("$[1].permissions").value(org.hamcrest.Matchers.hasItem("credits.view")));
    }
    @Test void adminSelfAdjustmentAndRetainedAccountReadAreIndependentOfProfileEditing() throws Exception {
        var input = new Adjustment(UUID.randomUUID().toString(), 9, "self");
        var transaction = new CreditTransaction(1, input.requestId(), "1000", "1000", 9, 0, 9, "self", 1000, null);
        when(store.adjust("1000", "1000", input)).thenReturn(new CreditAdjustmentResponse(input.requestId(), "applied", transaction, null, 9L));
        var service = new CreditService(users, store);
        assertThat(service.adjust(users.resolveCaller("1000", "admin"), "1000", input).transaction()).isEqualTo(transaction);
        when(store.account("olduser")).thenReturn(new CreditAccountStore.Account(true, 8, 1, 1000));
        assertThat(service.account(users.resolveCaller("1000", "admin"), "olduser").userPresent()).isFalse();
        assertThatThrownBy(() -> service.adjust(users.resolveCaller("1000", "admin"), "olduser", input))
                .isInstanceOf(ServerException.class);
        verify(store, never()).adjust(eq("olduser"), anyString(), any());
        when(store.account("unknown")).thenReturn(new CreditAccountStore.Account(false, 0, 0, 0));
        http.perform(get("/api/v1/users/unknown/credits").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")).andExpect(status().isNotFound());
    }
    @Test void confirmedRejectionAndUnavailableHaveDistinctHttpOutcomes() throws Exception {
        when(store.adjust(eq("1001"), eq("1000"), any())).thenAnswer(call -> {
            Adjustment input = call.getArgument(2);
            return new CreditAdjustmentResponse(input.requestId(), "rejected", null, "insufficient_balance", 0L);
        });
        http.perform(post(path + "/adjustments").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(body("-1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(18004));
        when(store.adjust(eq("1001"), eq("1000"), any())).thenThrow(new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "credits.adjust", null, null));
        http.perform(post(path + "/adjustments").header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(body("1"))).andExpect(status().isServiceUnavailable());
    }
    private String body(String amount) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"delta\":" + amount + ",\"reason\":\"reason\"}";
    }
}
