package com.xa.mass.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MassClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(MassClientApplication.class, args);

        MyWebSocketClient client = new MyWebSocketClient();
        client.connect();
    }
}