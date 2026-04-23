package com.xa.mass.api.internal;

import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SdkSubmitterControllerTest {

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SdkSubmitterController(authProvider)).build();
    }

    @Test
    void currentSubmitterReturnsAuthenticatedContext() throws Exception {
        when(authProvider.authenticate("dev-api-key")).thenReturn(new TaskSubmitterContext(
                "telegram-bot",
                "bot-user",
                "telegramApp",
                Map.of("channel", "telegram")
        ));

        mockMvc.perform(get("/sdk/submitters/me")
                        .header("X-Mass-Api-Key", "dev-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.principalId").value("telegram-bot"))
                .andExpect(jsonPath("$.data.userId").value("bot-user"))
                .andExpect(jsonPath("$.data.projectScope").value("telegramApp"))
                .andExpect(jsonPath("$.data.attributes.channel").value("telegram"));
    }

    @Test
    void currentSubmitterAcceptsBearerCredential() throws Exception {
        when(authProvider.authenticate("bearer-key")).thenReturn(new TaskSubmitterContext(
                "crawler-agent",
                null,
                "crawlerApp",
                Map.of()
        ));

        mockMvc.perform(get("/sdk/submitters/me")
                        .header("Authorization", "Bearer bearer-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("crawler-agent"))
                .andExpect(jsonPath("$.data.projectScope").value("crawlerApp"));
    }

    @Test
    void currentSubmitterRejectsMissingCredential() throws Exception {
        mockMvc.perform(get("/sdk/submitters/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("Invalid or missing SDK credential"));
    }
}
