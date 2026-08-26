package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KernelPacerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(KernelPacerConfiguration.class)
                    .withBean(
                            CandidateWorkerCache.class,
                            () -> mock(CandidateWorkerCache.class)
                    )
                    .withBean(
                            CandidateWarmupSchedule.class,
                            () -> mock(CandidateWarmupSchedule.class)
                    )
                    .withBean(
                            WorkerCommandRuntime.class,
                            () -> mock(WorkerCommandRuntime.class)
                    )
                    .withBean(
                            WorkerResultRuntime.class,
                            () -> mock(WorkerResultRuntime.class)
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
                            "xa.mass.kernel-pacer.config-path=kernel.json",
                            "xa.mass.kernel-pacer.shutdown-timeout=1s"
                    );

    @Test
    void bindsTheFiniteLifecycleConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KernelPacerProperties.class).enabled())
                    .isFalse();
        });
    }

    @Test
    void rejectsUnknownLifecycleFields() {
        contextRunner.withPropertyValues(
                "xa.mass.kernel-pacer.extra-arguments=--unsafe"
        ).run(context -> assertThat(context).hasFailed());
    }
}
