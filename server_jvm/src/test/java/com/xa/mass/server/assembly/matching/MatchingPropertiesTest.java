package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.task.call.TaskRpcProperties;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingPropertiesTest {
    @Test void windowConfigurationBindsOncePerGroupAndRejectsMalformedNumbers() {
        runner.withPropertyValues("xa.mass.worker-matching.groups.g.assignment-window.window-millis=60000",
                "xa.mass.worker-matching.groups.g.assignment-window.max-assignments=10")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var window = context.getBean(MatchingProperties.class).groups().get("g").assignmentWindow();
                    assertThat(window.windowMillis()).isEqualTo(60000);
                    assertThat(window.maxAssignments()).isEqualTo(10);
                });
        for (String bad : List.of("0", "-1", "1.5", "many"))
            runner.withPropertyValues("xa.mass.worker-matching.groups.g.assignment-window.window-millis=60000",
                    "xa.mass.worker-matching.groups.g.assignment-window.max-assignments=" + bad)
                    .run(context -> assertThat(context).hasFailed());
    }
    @EnableConfigurationProperties({MatchingProperties.class,TaskRpcProperties.class})
    @org.springframework.context.annotation.Import(com.xa.mass.server.task.call.RefillTargetConfigurationConverter.class)
    static class Binding {}
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class)
        .withPropertyValues("xa.mass.task-rpc.default-wait-timeout-millis=1000","xa.mass.task-rpc.max-wait-timeout-millis=1000",
            "xa.mass.task-rpc.max-waiters=10","xa.mass.task-rpc.max-pending-observations=10","xa.mass.task-rpc.max-probe-items-per-round=10",
            "xa.mass.task-rpc.initial-probe-interval-millis=50","xa.mass.task-rpc.normal-probe-interval-millis=100","xa.mass.task-rpc.long-probe-interval-millis=250");
    @Test void poolsAndFunctionsHaveIndependentConfiguration() {
        runner.withPropertyValues("xa.mass.worker-matching.groups.g.pools[0]=messaging",
            "xa.mass.worker-matching.groups.g.functions[0]=worker.messaging.available","xa.mass.worker-matching.groups.g.functions[1]=worker.phone")
            .run(context->{ assertThat(context).hasNotFailed(); var group=context.getBean(MatchingProperties.class).groups().get("g");
                assertThat(group.pools()).containsExactly("messaging"); assertThat(group.functions()).containsExactlyInAnyOrder("worker.messaging.available","worker.phone"); });
    }
    @Test void removedConfigurationAndUnknownFieldsAreRejected() {
        for(String invalid:List.of("xa.mass.worker-matching.rules.worker-groups.g[0]=worker.country","xa.mass.worker-matching.groups.g.unknown=true"))
            runner.withPropertyValues(invalid).run(context->assertThat(context).hasFailed());
    }
    @Test void explicitEmptyManagedSupplyAndMalformedDeclarationsStayDistinct() {
        runner.withPropertyValues("xa.mass.task-rpc.refill-by-worker-group.g=")
                .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(TaskRpcProperties.class).refillByWorkerGroup()).containsEntry("g",List.of());});
        for(String bad:List.of("{\"poolName\":\"any\",\"count\":1}","{\"poolName\":\"any\",\"target\":{},\"count\":\"1\"}","{\"poolName\":\"any\",\"target\":{},\"count\":1,\"extra\":true}"))
            runner.withPropertyValues("xa.mass.task-rpc.refill-by-worker-group.g[0]="+bad).run(context->assertThat(context).hasFailed());
    }

    @Test void managedRefillUsesFlatPoolTargetShape() {
        String prefix="xa.mass.task-rpc.refill-by-worker-group.g[0]=";
        runner.withPropertyValues(prefix+"{\"poolName\":\"country\",\"target\":{\"worker.country\":[\"CN\"]},\"count\":1000}")
            .run(context->{ assertThat(context).hasNotFailed(); var target=context.getBean(TaskRpcProperties.class).refillByWorkerGroup().get("g").getFirst();
                assertThat(target.poolName()).isEqualTo("country"); assertThat(target.count()).isEqualTo(1000);
                assertThat(target.target().query()).isEqualTo(Map.of("worker.country",List.of("CN"))); });
    }
}
