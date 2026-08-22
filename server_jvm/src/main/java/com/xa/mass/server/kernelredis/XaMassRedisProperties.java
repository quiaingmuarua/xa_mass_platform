package com.xa.mass.server.kernelredis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xa.mass.redis")
public record XaMassRedisProperties(
        URI url,
        String scope
) {
    public XaMassRedisProperties {
        Objects.requireNonNull(url, "url");
        if (!"redis".equalsIgnoreCase(url.getScheme())
                && !"rediss".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException(
                    "url must use redis or rediss"
            );
        }
        new RedisKeyspace(scope);
    }

    public RedisKeyspace keyspace() {
        return new RedisKeyspace(scope);
    }
}
