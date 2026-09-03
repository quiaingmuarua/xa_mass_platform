package com.xa.mass.server.assembly.pacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.InMemoryWorkerMatchQueue;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KernelPacerPropertiesTest {

    private final ApplicationContextRunner baseContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(KernelPacerConfiguration.class)
                    .withBean(
                            CandidateWorkerCache.class,
                            () -> mock(CandidateWorkerCache.class)
                    )
                    .withBean(
                            WorkerMatchQueue.class,
                            () -> new InMemoryWorkerMatchQueue(1)
                    )
                    .withBean(
                            WorkerCommandRuntime.class,
                            () -> mock(WorkerCommandRuntime.class)
                    )
                    .withBean(
                            TaskResultRuntime.class,
                            () -> mock(TaskResultRuntime.class)
                    )
                    .withBean(
                            TaskRuntime.class,
                            () -> mock(TaskRuntime.class)
                    )
                    .withBean(
                            TaskItemScoreBandCore.class,
                            () -> mock(TaskItemScoreBandCore.class)
                    )
                    .withBean(
                            TaskScoreBandCore.class,
                            () -> mock(TaskScoreBandCore.class)
                    )
                    .withBean(
                            TaskResourceCatalog.class,
                            () -> mock(TaskResourceCatalog.class)
                    )
                    .withBean(
                            WorkerScoreCore.class,
                            () -> mock(WorkerScoreCore.class)
                    )
                    .withBean(
                            WorkerServiceabilityRuntime.class,
                            () -> mock(WorkerServiceabilityRuntime.class)
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> mock(WorkerResourceCatalog.class)
                    )
                    .withPropertyValues(
                            "xa.mass.kernel-pacer.enabled=false",
                            "xa.mass.kernel-pacer.preset=DEFAULT",
                            "xa.mass.kernel-pacer.shutdown-timeout=1s"
                    );

    @Test
    void bindsTheFiniteLifecycleConfiguration() {
        contextRunner("test_kernel_pacer").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KernelPacerProperties.class).enabled())
                    .isFalse();
            assertThat(context.getBean(KernelPacerProperties.class).preset())
                    .isEqualTo(KernelPacerRuntime.PolicyPreset.DEFAULT);
        });
    }

    @Test
    void rejectsUnknownPreset() {
        contextRunner("test_kernel_pacer").withPropertyValues(
                "xa.mass.kernel-pacer.preset=UNKNOWN"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRemovedConfigPath() {
        contextRunner("test_kernel_pacer").withPropertyValues(
                "xa.mass.kernel-pacer.config-path=kernel.json"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsServiceabilityDefaultPreset() {
        contextRunner("profile_default").withPropertyValues(
                "xa.mass.kernel-pacer.preset=SERVICEABILITY_DEFAULT"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KernelPacerProperties.class).preset())
                    .isEqualTo(
                            KernelPacerRuntime.PolicyPreset
                                    .SERVICEABILITY_DEFAULT
                    );
        });
    }

    @Test
    void acceptsRuntimeBoundaryProofPresetForTestScope() {
        contextRunner("test_kernel_pacer").withPropertyValues(
                "xa.mass.kernel-pacer.preset=RUNTIME_BOUNDARY_PROOF"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsRuntimeBoundaryProofPresetForPersistentScope() {
        contextRunner("profile_default")
                .withPropertyValues(
                        "xa.mass.kernel-pacer.preset=RUNTIME_BOUNDARY_PROOF"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "operation=kernelPacer.validatePresetScope "
                                            + "RUNTIME_BOUNDARY_PROOF requires "
                                            + "a test_* Redis scope"
                            );
                });
    }

    private ApplicationContextRunner contextRunner(String redisScope) {
        return baseContextRunner.withBean(
                XaMassRedisProperties.class,
                () -> redisProperties(redisScope)
        );
    }

    private static XaMassRedisProperties redisProperties(String scope) {
        return new XaMassRedisProperties(
                URI.create("redis://127.0.0.1:6379/15"),
                scope
        );
    }
}
