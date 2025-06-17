package com.xa.mass.core.config;


import com.google.gson.Gson;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.queue.RedisStreamMessageQueue;
import com.xa.mass.core.queue.StoredMessage;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile; // 导入 Profile
import org.springframework.util.StringUtils;


@Configuration
@Profile("!local") // "local" profile 不激活时生效 (例如 "prod", "dev" 
// 或@Profile({"prod", "dev"}) 等具体指
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
    public RedisClient redisClient() {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withDatabase(redisDatabase);

        if (StringUtils.hasText(redisPassword)) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(uriBuilder.build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> statefulRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public Gson gsonForQueue() {
        return new Gson();
    }

    @Bean
    @Qualifier("inputQueue")
    public MessageQueue<StoredMessage> redisInputQueue(
            StatefulRedisConnection<String, String> connection,
            Gson gsonForQueue,
            @Value("${mass.queue.input.stream-key:mass_input_stream}") String streamKey,
            @Value("${mass.queue.input.group-name:mass_input_group}") String groupName,
            @Value("${mass.queue.input.consumer-name:input_consumer_1}") String consumerName) {
        return new RedisStreamMessageQueue(streamKey, groupName, consumerName, connection, gsonForQueue);
    }

    @Bean
    @Qualifier("outputQueue")
    public MessageQueue<StoredMessage> redisOutputQueue(
            StatefulRedisConnection<String, String> connection,
            Gson gsonForQueue,
            @Value("${mass.queue.output.stream-key:mass_output_stream}") String streamKey,
            @Value("${mass.queue.output.group-name:mass_output_group}") String groupName,
            @Value("${mass.queue.output.consumer-name:output_consumer_1}") String consumerName) {
        return new RedisStreamMessageQueue(streamKey, groupName, consumerName, connection, gsonForQueue);
    }
}
