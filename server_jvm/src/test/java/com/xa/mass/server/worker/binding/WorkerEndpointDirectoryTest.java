package com.xa.mass.server.worker.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.worker.binding.WorkerBindingProperties.EndpointProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkerEndpointDirectoryTest {

    @Test
    void selectionIsStableAndIndependentOfConfigurationOrder() {
        Map<String, EndpointProperties> forward = endpoints(false);
        Map<String, EndpointProperties> reverse = endpoints(true);
        WorkerEndpointDirectory first = new WorkerEndpointDirectory(forward);
        WorkerEndpointDirectory second = new WorkerEndpointDirectory(reverse);

        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            assertThat(first.select(workerId, WorkerTransportType.WEBSOCKET))
                    .isEqualTo(second.select(
                            workerId,
                            WorkerTransportType.WEBSOCKET
                    ));
        }
    }

    @Test
    void multipleEndpointsReceiveDeterministicSelections() {
        WorkerEndpointDirectory directory = new WorkerEndpointDirectory(
                endpoints(false)
        );

        Set<String> selected = IntStream.range(0, 100)
                .mapToObj(index -> directory.select(
                        "worker-" + index,
                        WorkerTransportType.WEBSOCKET
                ).endpointManagerId())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(selected).containsExactlyInAnyOrder(
                "websocket-a",
                "websocket-b"
        );
        assertThat(directory.select("worker-1", WorkerTransportType.SOCKET))
                .isNull();
    }

    @Test
    void endpointPropertiesRejectMismatchedSchemesAndRelativeUris() {
        assertThatThrownBy(() -> new EndpointProperties(
                WorkerTransportType.POLLING,
                URI.create("ws://127.0.0.1:18083/worker")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointProperties(
                WorkerTransportType.WEBSOCKET,
                URI.create("/worker")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointProperties(
                WorkerTransportType.SOCKET,
                URI.create("http://127.0.0.1:18084")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pollingTransportUsesOnlyTheBuiltInEndpointIdentity() {
        EndpointProperties polling = new EndpointProperties(
                WorkerTransportType.POLLING,
                URI.create("http://127.0.0.1:18082")
        );
        assertThatThrownBy(() -> new WorkerEndpointDirectory(Map.of(
                "another-polling-endpoint",
                polling
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerEndpointDirectory(Map.of(
                "system-polling",
                websocket(18083)
        ))).isInstanceOf(IllegalArgumentException.class);

        WorkerEndpointDirectory directory = new WorkerEndpointDirectory(
                Map.of("system-polling", polling)
        );
        assertThat(directory.select(
                "worker-1",
                WorkerTransportType.POLLING
        ).endpointManagerId()).isEqualTo("system-polling");
    }

    @Test
    void lookupAndTypeMembershipUseTheConfiguredIdentity() {
        WorkerEndpointDirectory directory = new WorkerEndpointDirectory(
                endpoints(false)
        );

        assertThat(directory.find("websocket-a")).isEqualTo(
                new WorkerEndpointBinding(
                        "websocket-a",
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

    private static Map<String, EndpointProperties> endpoints(
            boolean reverse
    ) {
        LinkedHashMap<String, EndpointProperties> endpoints =
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

    private static EndpointProperties websocket(int port) {
        return new EndpointProperties(
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:" + port + "/worker")
        );
    }
}
