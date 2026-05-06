package com.xa.mass.trace.config;

import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the execution event sink.
 *
 * <p>Enable with {@code mass.trace.sink.enabled=true}. Defaults to the no-op sink.
 */
@AutoConfiguration
@EnableConfigurationProperties(MassTraceSinkProperties.class)
public class MassTraceSinkConfig {

    @Bean
    @ConditionalOnMissingBean(ExecutionEventSink.class)
    @ConditionalOnProperty(name = "mass.trace.sink.enabled", havingValue = "true")
    public JsonlExecutionEventSink jsonlExecutionEventSink(MassTraceSinkProperties props) {
        return new JsonlExecutionEventSink(
                props.getOutputDir(),
                props.getQueueCapacity(),
                props.getRotateAfterLines(),
                props.getOverflowPolicy(),
                props.getShutdownDrainTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionEventSink.class)
    public ExecutionEventSink noopExecutionEventSink() {
        return new NoopExecutionEventSink();
    }
}
