package com.xa.mass.server.kernelbinding;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-property-index",
        ignoreUnknownFields = false
)
public record WorkerPropertyIndexProperties(
        @DefaultValue("{}") String registryJson
) {

    public static final String REDIS_HASH = "redis-hash";
    private static final System.Logger LOGGER = System.getLogger(
            WorkerPropertyIndexProperties.class.getName()
    );

    public WorkerPropertyIndexProperties {
        Map<String, String> registry = parseRegistry(registryJson);
        registryJson = canonicalJson(registry);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Worker Property Index registry fields={0} fingerprint={1}",
                registry.size(),
                fingerprint(registryJson)
        );
    }

    public Map<String, String> registry() {
        return parseRegistry(registryJson);
    }

    public String fingerprint() {
        return fingerprint(registryJson);
    }

    private static Map<String, String> parseRegistry(String encoded) {
        String resolved = encoded == null ? "{}" : encoded;
        Map<String, Object> parsed = Jsons.parseObject(resolved);
        var sorted = new TreeMap<String, String>();
        parsed.forEach((propertyField, rawImplementation) -> {
            if (!validPropertyField(propertyField)) {
                throw new IllegalArgumentException(
                        "Worker property index fields must use index.*"
                );
            }
            if (!(rawImplementation instanceof String implementation)
                    || implementation.isEmpty()) {
                throw new IllegalArgumentException(
                        "Worker property index implementation must be a string"
                );
            }
            if (!REDIS_HASH.equals(implementation)) {
                throw new IllegalArgumentException(
                        "Unknown Worker property index implementation: "
                                + implementation
                );
            }
            sorted.put(propertyField, implementation);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String canonicalJson(Map<String, String> registry) {
        return escapeNonAscii(Jsons.toJson(new TreeMap<>(registry)));
    }

    private static String fingerprint(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String escapeNonAscii(String json) {
        StringBuilder escaped = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            if (value <= 0x7f) {
                escaped.append(value);
                continue;
            }
            escaped.append("\\u");
            escaped.append(Character.forDigit((value >>> 12) & 0xf, 16));
            escaped.append(Character.forDigit((value >>> 8) & 0xf, 16));
            escaped.append(Character.forDigit((value >>> 4) & 0xf, 16));
            escaped.append(Character.forDigit(value & 0xf, 16));
        }
        return escaped.toString();
    }

    private static boolean validPropertyField(String field) {
        return field != null
                && field.startsWith("index.")
                && field.length() > "index.".length();
    }
}
