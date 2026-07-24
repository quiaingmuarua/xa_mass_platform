package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.v1.ResourceCommandController;
import com.xa.mass.server.api.v1.TaskCommandController;
import com.xa.mass.server.kernelclient.KernelCommandClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms"
        }
)
class ServerApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Test
    void assemblesTheRuntimeApiWithoutStartingThePythonKernel() throws Exception {
        assertThat(applicationContext.getBean(KernelCommandClient.class))
                .isNotNull();
        assertThat(applicationContext.getBean(ResourceCommandController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskCommandController.class))
                .isNotNull();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> liveness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/liveness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> readiness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
