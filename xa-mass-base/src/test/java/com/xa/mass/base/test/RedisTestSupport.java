package com.xa.mass.base.test;

import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class RedisTestSupport {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 6379;

    private RedisTestSupport() {
    }

    public static void initLocalRedisOrSkip() {
        Assumptions.assumeTrue(
                isReachable(DEFAULT_HOST, DEFAULT_PORT, 200),
                () -> "Skipping Redis integration test because Redis is unavailable at "
                        + DEFAULT_HOST + ":" + DEFAULT_PORT
        );
        RedisConnectionManager.shutdown();
        RedisConnectionManager.init(DEFAULT_HOST, DEFAULT_PORT, null, 0);
    }

    private static boolean isReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
