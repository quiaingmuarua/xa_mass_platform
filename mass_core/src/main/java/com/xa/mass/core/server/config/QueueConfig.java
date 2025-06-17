package com.xa.mass.core.server.config;

import com.xa.mass.core.server.queue.InMemoryMessageQueue;
import com.xa.mass.core.server.queue.MessageQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import com.xa.mass.core.server.queue.InMemoryMessageQueue;
import com.xa.mass.core.server.queue.MessageQueue;
import com.xa.mass.core.server.queue.StoredMessage; // 导入 StoredMessage
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile; // 导入 Profile

@Configuration
@Profile("local") // 只在 "local" profile 激活时生效
public class QueueConfig { // 可以移除 ("customQueueConfigName") 如果不需要特定bean

    @Bean
    @Qualifier("inputQueue")
    public MessageQueue<StoredMessage> inputQueue() { // 返回类型改为 MessageQueue<StoredMessage>
        return new InMemoryMessageQueue(); // InMemoryMessageQueue 应该实现 MessageQueue<StoredMessage>
    }

    @Bean
    @Qualifier("outputQueue")
    public MessageQueue<StoredMessage> outputQueue() { // 返回类型改为 MessageQueue<StoredMessage>
        return new InMemoryMessageQueue();
    }
}
