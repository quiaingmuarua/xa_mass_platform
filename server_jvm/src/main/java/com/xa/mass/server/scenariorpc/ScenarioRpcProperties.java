package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcPollingPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.scenario-rpc")
public record ScenarioRpcProperties(
        @NotBlank @DefaultValue("data/rpc-task") String root,
        @Min(1) @DefaultValue("1048576") int maxInputBytes,
        @Min(1) @DefaultValue("1000") int maxInputLines,
        @Min(1) @DefaultValue("100") int maxScenarios,
        @Min(1) @DefaultValue("100") long defaultLoadIntervalMillis,
        @Min(1) @DefaultValue("300") int defaultMaximumLoadRounds
) {
    public ScenarioRpcProperties {
        new ScenarioRpcPollingPolicy(
                defaultLoadIntervalMillis,
                defaultMaximumLoadRounds
        );
    }
}
