package com.xa.mass.server.taskbatch;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("xa.mass.task-batch")
public record TaskBatchProperties(
        @NotBlank @DefaultValue("data/rpc-task") String root,
        @Min(1) @DefaultValue("1048576") int maxInputBytes,
        @Min(1) @DefaultValue("1000") int maxInputLines,
        @Min(1) @DefaultValue("100") long resultLoadIntervalMillis,
        @Min(1) @DefaultValue("30000") long defaultMaximumWaitMillis,
        @Min(1) @DefaultValue("300000") long maximumWaitMillis
) {
    public TaskBatchProperties {
        if (defaultMaximumWaitMillis > maximumWaitMillis) {
            throw new IllegalArgumentException(
                    "defaultMaximumWaitMillis must not exceed maximumWaitMillis"
            );
        }
    }
}
