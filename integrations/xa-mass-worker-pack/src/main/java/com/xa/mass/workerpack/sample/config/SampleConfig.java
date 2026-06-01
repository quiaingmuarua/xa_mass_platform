package com.xa.mass.workerpack.sample.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sample")
public class SampleConfig {
    private Client client = new Client();
    private Task task = new Task();

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public static class Client {
        private int count = 5;
        private String websocketUri = "ws://localhost:18088/ws";

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public String getWebsocketUri() {
            return websocketUri;
        }

        public void setWebsocketUri(String websocketUri) {
            this.websocketUri = websocketUri;
        }

    }

    public static class Task {
        private long interval = 30000;

        public long getInterval() {
            return interval;
        }

        public void setInterval(long interval) {
            this.interval = interval;
        }
    }
} 
