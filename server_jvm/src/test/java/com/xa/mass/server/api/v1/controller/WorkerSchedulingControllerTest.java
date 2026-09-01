package com.xa.mass.server.api.v1.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkerSchedulingControllerTest {

    private static final String GROUP_ID = "group-1";
    private static final String WORKER_ID = "worker-1";
    private static final String PAUSE_PATH =
            "/api/v1/worker-groups/group-1/workers/"
                    + "worker-1:pause-scheduling";
    private static final String RESUME_PATH =
            "/api/v1/worker-groups/group-1/workers/"
                    + "worker-1:resume-scheduling";

    private WorkerScoreCore workerScores;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerScores = mock(WorkerScoreCore.class);
        WorkerSchedulingService service =
                new WorkerSchedulingService(workerScores);
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkerSchedulingController(service)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void pauseTransitionReturnsApplied() throws Exception {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.TRANSITIONED, 123L)
        ));

        mockMvc.perform(post(PAUSE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void duplicatePauseReturnsUnchanged() throws Exception {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.STALE, 123L)
        ));

        mockMvc.perform(post(PAUSE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"));
    }

    @Test
    void missingAndInvalidTransitionsUseBusinessErrors()
            throws Exception {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.STALE, null)
        ));
        mockMvc.perform(post(PAUSE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15008))
                .andExpect(jsonPath("$.message").value(
                        "Worker resource was not found"
                ));

        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.INVALID, null)
        ));
        mockMvc.perform(post(PAUSE_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15009))
                .andExpect(jsonPath("$.message").value(
                        "Worker resource operation conflicts with current state"
                ));
    }

    @Test
    void resumeNoopRequiresNoRequestBody() throws Exception {
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(Map.of(
                WORKER_ID,
                new WorkerScoreCore.WorkerScoreState(
                        WORKER_ID,
                        123L,
                        WorkerScoreCore.WorkerScorePolarity.HOT_ACQUIRE,
                        1_000L,
                        2,
                        0
                )
        ));

        mockMvc.perform(post(RESUME_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void providerFailureReturnsServiceUnavailable() throws Exception {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenThrow(new IllegalStateException("Redis unavailable"));

        mockMvc.perform(post(PAUSE_PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(15004));
    }

    private static WorkerScoreTransitionResult result(
            WorkerScoreTransitionStatus status,
            Long score
    ) {
        return new WorkerScoreTransitionResult(status, score);
    }
}
