package com.xa.mass.server.task.retention;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "xa.mass.task-retention", ignoreUnknownFields = false)
public record TaskRetentionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1h") Duration grace,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("30d") Duration historyRetention,
        @DefaultValue("1h") Duration interval,
        @DefaultValue("./data/task-history.sqlite") Path databasePath
) {
    public TaskRetentionProperties {
        if (grace == null || retention == null || historyRetention == null || interval == null || databasePath == null
                || grace.isNegative() || retention.compareTo(grace) <= 0 || historyRetention.compareTo(retention) < 0
                || interval.isZero() || interval.isNegative())
            throw new IllegalArgumentException("Task retention requires 0 <= grace < retention <= history-retention and a positive interval");
        if (interval.toMillis() < 1 || retention.toMillis() <= grace.toMillis()
                || historyRetention.toMillis() < retention.toMillis())
            throw new IllegalArgumentException("Task retention durations must have millisecond precision");
        databasePath = databasePath.toAbsolutePath().normalize();
    }
}
