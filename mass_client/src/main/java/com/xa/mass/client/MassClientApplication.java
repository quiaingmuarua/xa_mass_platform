package com.xa.mass.client;

import com.google.gson.Gson;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MassClientApplication {
    private static final Gson gson = new Gson();
    public static void main(String[] args) {
        SpringApplication.run(MassClientApplication.class, args);
        TaskWebSocketClient client = new TaskWebSocketClient();
        client.connect();

    }
}