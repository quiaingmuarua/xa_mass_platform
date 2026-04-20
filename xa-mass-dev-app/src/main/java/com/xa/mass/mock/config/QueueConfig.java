package com.xa.mass.mock.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.gateway.queue.Envelope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class QueueConfig {

    @Bean
    @Qualifier("inputQueue")
    public MessageQueue<Envelope> inputQueue() {
        return new InMemoryMessageQueue<>("Envelope", Envelope.class);
    }

    @Bean
    @Qualifier("outputQueue")
    public MessageQueue<Envelope> outputQueue() {
        return new InMemoryMessageQueue<>("Envelope", Envelope.class);
    }
}
