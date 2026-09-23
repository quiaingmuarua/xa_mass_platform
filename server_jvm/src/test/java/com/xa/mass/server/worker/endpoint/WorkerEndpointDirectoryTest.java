package com.xa.mass.server.worker.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory.Endpoint;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEndpointDirectoryTest {

    @Test
    void explicitDefaultIsIndependentOfWorkerIdentityAndConfigurationOrder() {
        var defaults = Map.of(WorkerTransportType.WEBSOCKET, "websocket-b");
        var first = new WorkerEndpointDirectory(endpoints(false), defaults);
        var second = new WorkerEndpointDirectory(endpoints(true), defaults);
        assertThat(first.defaultEndpointId(WorkerTransportType.WEBSOCKET))
                .isEqualTo(second.defaultEndpointId(WorkerTransportType.WEBSOCKET))
                .isEqualTo("websocket-b");
        assertThat(first.find("websocket-a")).isNotNull();
        assertThat(first.defaultEndpointId(WorkerTransportType.SOCKET)).isNull();
    }

    @Test
    void eachConfiguredTypeRequiresAValidExplicitDefault() {
        assertThatThrownBy(() -> new WorkerEndpointDirectory(endpoints(false), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerEndpointDirectory(endpoints(false),
                Map.of(WorkerTransportType.WEBSOCKET, "missing")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerEndpointDirectory(endpoints(false),
                Map.of(WorkerTransportType.SOCKET, "websocket-a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointPropertiesRejectMismatchedSchemesAndRelativeUris() {
        assertThatThrownBy(() -> new Endpoint(
                WorkerTransportType.POLLING,
                URI.create("ws://127.0.0.1:18083/worker")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Endpoint(
                WorkerTransportType.WEBSOCKET,
                URI.create("/worker")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Endpoint(
                WorkerTransportType.SOCKET,
                URI.create("http://127.0.0.1:18084")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pollingTransportUsesOnlyTheBuiltInEndpointIdentity() {
        Endpoint polling = new Endpoint(
                WorkerTransportType.POLLING,
                URI.create("http://127.0.0.1:18082")
        );
        assertThatThrownBy(() -> new WorkerEndpointDirectory(Map.of(
                "another-polling-endpoint",
                polling
        ), Map.of(WorkerTransportType.POLLING, "system-polling"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerEndpointDirectory(Map.of(
                "system-polling",
                websocket(18083)
        ), Map.of(WorkerTransportType.POLLING, "system-polling"))).isInstanceOf(IllegalArgumentException.class);

        WorkerEndpointDirectory directory = new WorkerEndpointDirectory(Map.of("system-polling", polling), Map.of(WorkerTransportType.POLLING, "system-polling"));
        assertThat(directory.defaultEndpointId(WorkerTransportType.POLLING)).isEqualTo("system-polling");
    }

    @Test
    void lookupAndTypeMembershipUseTheConfiguredIdentity() {
        WorkerEndpointDirectory directory = new WorkerEndpointDirectory(endpoints(false), Map.of(WorkerTransportType.WEBSOCKET, "websocket-a"));

        assertThat(directory.find("websocket-a")).isEqualTo(
                new Endpoint(
                        WorkerTransportType.WEBSOCKET,
                        URI.create("ws://127.0.0.1:18083/worker")
                )
        );
        assertThat(directory.find("missing")).isNull();
        assertThat(directory.contains(
                "websocket-a",
                WorkerTransportType.WEBSOCKET
        )).isTrue();
        assertThat(directory.contains(
                "websocket-a",
                WorkerTransportType.SOCKET
        )).isFalse();
    }

    private static Map<String, Endpoint> endpoints(
            boolean reverse
    ) {
        LinkedHashMap<String, Endpoint> endpoints =
                new LinkedHashMap<>();
        if (reverse) {
            endpoints.put("websocket-b", websocket(18084));
            endpoints.put("websocket-a", websocket(18083));
        } else {
            endpoints.put("websocket-a", websocket(18083));
            endpoints.put("websocket-b", websocket(18084));
        }
        return endpoints;
    }

    private static Endpoint websocket(int port) {
        return new Endpoint(
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:" + port + "/worker")
        );
    }
}
