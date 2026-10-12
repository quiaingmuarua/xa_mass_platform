package com.xa.mass.server.frontend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontendControllerTest {

    @Test
    void forwardsFrontendRoutesToTheVueEntryPoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FrontendController()
        ).build();

        for (String path : new String[]{
                "/",
                "/runtime/workers",
                "/runtime/workers/",
                "/runtime/tasks",
                "/runtime/tasks/",
                "/task-workbench",
                "/task-workbench/",
                "/api-reference",
                "/api-reference/",
                "/reference/error-codes",
                "/reference/error-codes/"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void forwardsWorkbenchQueriesWithoutCatchingUnknownApiOrAssetPaths() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FrontendController()).build();
        mockMvc.perform(get("/task-workbench?tab=finite&view=create"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        for (String path : new String[]{"/api/v1/unknown", "/static/unknown.js", "/task-workbench/unknown"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
