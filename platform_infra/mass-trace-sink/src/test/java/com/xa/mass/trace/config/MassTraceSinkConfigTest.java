package com.xa.mass.trace.config;

import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import com.xa.mass.trace.sink.OverflowPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MassTraceSinkConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MassTraceSinkConfig.class));

    @Test
    void defaultsToNoopSinkWhenDisabled() {
        contextRunner.run(context ->
                assertInstanceOf(NoopExecutionEventSink.class, context.getBean(ExecutionEventSink.class)));
    }

    @Test
    void createsJsonlSinkWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "mass.trace.sink.enabled=true",
                        "mass.trace.sink.output-dir=build/test-trace",
                        "mass.trace.sink.queue-capacity=128",
                        "mass.trace.sink.rotate-after-lines=64",
                        "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                        "mass.trace.sink.shutdown-drain-timeout-ms=1500"
                )
                .run(context -> {
                    assertInstanceOf(JsonlExecutionEventSink.class, context.getBean(ExecutionEventSink.class));

                    MassTraceSinkProperties properties = context.getBean(MassTraceSinkProperties.class);
                    assertEquals(true, properties.isEnabled());
                    assertEquals("build/test-trace", properties.getOutputDir());
                    assertEquals(128, properties.getQueueCapacity());
                    assertEquals(64, properties.getRotateAfterLines());
                    assertEquals(OverflowPolicy.FALLBACK_SYNC, properties.getOverflowPolicy());
                    assertEquals(1500L, properties.getShutdownDrainTimeoutMs());
                });
    }
}
