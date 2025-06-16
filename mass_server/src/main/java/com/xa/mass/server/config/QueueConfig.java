package com.xa.mass.server.config;

import com.xa.mass.server.queue.InMemoryMessageQueue;
import com.xa.mass.server.queue.MessageQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration("customQueueConfigName") // 指定一个唯一的bean名称
public class QueueConfig {

    @Bean
    @Qualifier("inputQueue") // 使用 Qualifier 来区分不同的队列实例
    public MessageQueue inputQueue() {
        return new InMemoryMessageQueue();
    }

    @Bean
    @Qualifier("outputQueue")
    public MessageQueue outputQueue() {
        return new InMemoryMessageQueue();
    }
}