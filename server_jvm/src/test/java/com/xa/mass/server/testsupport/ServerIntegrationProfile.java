package com.xa.mass.server.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ServerIntegrationProfile {

    private static final String RESOURCE =
            "/application-integration-test.properties";
    private static final Properties PROPERTIES = load();

    public static final String REDIS_URL = value(
            "xa.mass.redis.url"
    );

    private ServerIntegrationProfile() {
    }

    private static Properties load() {
        try (InputStream input = ServerIntegrationProfile.class
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing Server integration profile " + RESOURCE
                );
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String value(String key) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing Server integration profile property " + key
            );
        }
        return value;
    }
}
