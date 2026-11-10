package com.xa.mass.workersimulator;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkerSimulatorGatewayCredentialTest {
    private static final String SECRET = "credential-content-must-never-appear-in-diagnostics";
    @TempDir Path temp;

    @Test void relativeCredentialIsReadOnceAndNotRenderedInCapturedConfiguration() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("配置 with spaces"));
        Path privateDirectory = Files.createDirectories(directory.resolve("private"));
        Path credential = privateDirectory.resolve("access.json");
        Files.writeString(credential, Jsons.toJson(Map.of("keyId", "host-a", "secret", SECRET)));
        Path input = writeConfig(directory, "https://workers.example.test", "private/access.json");

        var config = WorkerSimulatorConfig.load(input);
        String authorization = "Basic " + Base64.getEncoder().encodeToString(("host-a:" + SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(config.gatewayCredential().keyId()).isEqualTo("host-a");
        assertThat(config.gatewayCredential().authorizationHeader()).isEqualTo(authorization);
        assertThat(config.toString()).doesNotContain(SECRET, authorization);
        assertThat(config.gatewayCredential().toString()).doesNotContain(SECRET, authorization);
        Files.delete(credential);
        assertThat(config.gatewayCredential().authorizationHeader()).isEqualTo(authorization);
        try (var host = WorkerSimulator.create(config)) {
            host.start(); // No Groups: creating/starting a captured Host does not read the credential again.
        }
    }

    @Test void absoluteCredentialPathWorksAndOmissionKeepsTheExistingLocalMode() throws Exception {
        Path credential = temp.resolve("absolute.json");
        Files.writeString(credential, Jsons.toJson(Map.of("keyId", "host-b", "secret", SECRET)));
        Path directory = Files.createDirectories(temp.resolve("configuration"));
        var config = WorkerSimulatorConfig.load(writeConfig(directory, "https://workers.example.test:443", credential.toString()));
        assertThat(config.gatewayCredential().keyId()).isEqualTo("host-b");
        assertThat(WorkerSimulatorJsonParser.parse("{\"workerGroups\":{}}", directory).gatewayCredential()).isNull();
    }

    @Test void credentialFileLimitIsMeasuredInBytesAndInclusive() throws Exception {
        Path credential = temp.resolve("access.json");
        String encoded = Jsons.toJson(Map.of("keyId", "host-a", "secret", SECRET));
        Files.writeString(credential, encoded + " ".repeat(65_536 - encoded.getBytes(StandardCharsets.UTF_8).length));
        Path input = writeConfig(temp, "https://workers.example.test", "access.json");
        assertThat(Files.size(credential)).isEqualTo(65_536);
        assertThat(WorkerSimulatorConfig.load(input).gatewayCredential().keyId()).isEqualTo("host-a");
        Files.writeString(credential, " ", java.nio.file.StandardOpenOption.APPEND);
        assertSafeFailure(input);
    }

    @Test void missingUnreadableNullAndMalformedCredentialsNeverFallBackOrExposeTheirInput() throws Exception {
        Path input = writeConfig(temp, "https://workers.example.test", "access.json");
        assertSafeFailure(input);
        Path credential = temp.resolve("access.json");
        for (String invalid : List.of(
                "", "[]", "null", "{\"keyId\":\"host-a\"}",
                "{\"keyId\":\"host-a\",\"secret\":null}",
                "{\"keyId\":null,\"secret\":\"" + SECRET + "\"}",
                "{\"keyId\":\"host-a\",\"secret\":123}",
                "{\"keyId\":\"host-a\",\"secret\":\"\"}",
                "{\"keyId\":\"host:a\",\"secret\":\"" + SECRET + "\"}",
                "{\"keyId\":\"host-a\",\"secret\":\"" + SECRET + "\",\"extra\":true}",
                "{\"keyId\":\"host-a\",\"secret\":\"" + SECRET + "\",\"secret\":\"other\"}",
                "{\"keyId\":\"host-a\",\"secret\":\"" + SECRET + "\"} trailing",
                "{\"keyId\":\"host-a\",\"secret\":\"" + SECRET)) {
            Files.writeString(credential, invalid);
            assertSafeFailure(input);
        }
        Files.write(credential, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertSafeFailure(input);
        Files.writeString(credential, " ".repeat(65_537));
        assertSafeFailure(input);
        Files.delete(credential);
        Files.createDirectory(credential);
        assertSafeFailure(input);
        for (String invalid : List.of("null", "false", "123", "\"\"")) {
            assertThatThrownBy(() -> WorkerSimulatorJsonParser.parse(
                    "{\"workerGroups\":{},\"gatewayCredentialFile\":" + invalid + "}", temp))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gatewayCredentialFile");
        }
        assertThat(temp.resolve("data/scenario-workers")).doesNotExist();
    }

    @Test void invalidAuthenticatedRuntimeFailsBeforeInventoryOrControlListenerStartup() throws Exception {
        Path credential = temp.resolve("access.json");
        Files.writeString(credential, Jsons.toJson(Map.of("keyId", "host-a", "secret", SECRET)));
        for (String runtime : List.of("http://workers.example.test", "https://user:password@workers.example.test",
                "https://workers.example.test?other=true", "https://workers.example.test#fragment")) {
            Path input = writeConfig(temp, runtime, credential.toString());
            assertThatThrownBy(() -> WorkerSimulatorMain.main(new String[]{"--config", input.toString()}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(temp.resolve("data/scenario-workers")).doesNotExist();
        }
    }

    private Path writeConfig(Path directory, String runtime, String credential) throws Exception {
        Path input = directory.resolve("simulator.json");
        Files.writeString(input, Jsons.toJson(Map.of("runtimeApiBaseUrl", runtime,
                "gatewayCredentialFile", credential, "controlPort", 0, "workerGroups", Map.of())));
        return input;
    }

    private void assertSafeFailure(Path input) {
        assertThatThrownBy(() -> WorkerSimulatorConfig.load(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gatewayCredentialFile")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain(SECRET));
    }
}
