package com.xa.mass.mock.config;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.queue.RedisEnvelopeQueue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class RedisQueueConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:#{null}}")
    private String redisPassword;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ClientResources clientResources) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withDatabase(redisDatabase);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }

        return RedisClient.create(clientResources, uriBuilder.build());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public ClientResources clientResources() {
        return DefaultClientResources.create();
    }

    @Bean(destroyMethod = "close")
    @Qualifier("readConnection")
    public StatefulRedisConnection<String, String> readRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean(destroyMethod = "close")
    @Qualifier("writeConnection")
    public StatefulRedisConnection<String, String> writeRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    @Qualifier("inputQueue")
    public MessageQueue<Envelope> redisInputQueue(
            @Qualifier("readConnection") StatefulRedisConnection<String, String> readConn,
            @Qualifier("writeConnection") StatefulRedisConnection<String, String> writeConn,
            @Value("${mass.queue.input.stream-key}") String streamKey,
            @Value("${mass.queue.input.group-name}") String groupName,
            @Value("${mass.queue.input.consumer-name}") String consumerName) {
        return new RedisEnvelopeQueue(streamKey, groupName, consumerName, readConn, writeConn);
    }

    @Bean
    @Qualifier("outputQueue")
    public MessageQueue<Envelope> redisOutputQueue(
            @Qualifier("readConnection") StatefulRedisConnection<String, String> readConn,
            @Qualifier("writeConnection") StatefulRedisConnection<String, String> writeConn,
            @Value("${mass.queue.output.stream-key}") String streamKey,
            @Value("${mass.queue.output.group-name}") String groupName,
            @Value("${mass.queue.output.consumer-name}") String consumerName) {
        return new RedisEnvelopeQueue(streamKey, groupName, consumerName, readConn, writeConn);
    }
}
