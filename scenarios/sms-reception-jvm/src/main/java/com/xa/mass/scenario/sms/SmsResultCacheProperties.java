package com.xa.mass.scenario.sms;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "xa.mass.sms.result-cache", ignoreUnknownFields = false)
public record SmsResultCacheProperties(@DefaultValue("10000") long maximumSize,
        @DefaultValue("10m") Duration expireAfterWrite) {
    public SmsResultCacheProperties {
        if (maximumSize <= 0 || expireAfterWrite == null || expireAfterWrite.isNegative() || expireAfterWrite.toMillis() < 1)
            throw new IllegalArgumentException("SMS Result cache requires a positive size and expiration");
    }
}
