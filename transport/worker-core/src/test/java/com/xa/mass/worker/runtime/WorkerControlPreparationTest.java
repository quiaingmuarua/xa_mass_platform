package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerControlPreparationTest {

    private static final PreparedWorker PREPARED = new PreparedWorker(
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
            URI.create("ws://127.0.0.1:18083/worker")
    );

    @Test
    void eachPreparationLoadsOneDefensiveCopyAndCallsControlOnce()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        List<String> tags = new ArrayList<>(List.of("one"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("tags", tags);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("clientWorkerKey", "installation-1");
        source.put("nested", nested);
        WorkerControlPreparation preparation = preparation(() -> source, control);

        assertEquals(PREPARED, preparation.prepare());

        assertEquals(1, control.prepareCalls);
        assertThrows(
                UnsupportedOperationException.class,
                () -> control.properties.get(0).put("new", "value")
        );
        source.put("afterPrepare", true);
        tags.add("two");
        assertTrue(!control.properties.get(0).containsKey("afterPrepare"));
        assertEquals(
                List.of("one"),
                ((Map<?, ?>) control.properties.get(0).get("nested"))
                        .get("tags")
        );
    }

    @Test
    void repeatedPreparationReloadsPropertiesAndCallsControlOnceEach()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        int[] version = {0};
        WorkerControlPreparation preparation = preparation(
                () -> Map.of(
                        "clientWorkerKey", "installation-1",
                        "version", ++version[0]
                ),
                control
        );

        preparation.prepare();
        preparation.prepare();

        assertEquals(2, control.prepareCalls);
        assertEquals(1, control.properties.get(0).get("version"));
        assertEquals(2, control.properties.get(1).get("version"));
    }

    @Test
    void invalidPropertiesFailBeforeControlAndCloseIsOwned() {
        FakeControlClient control = new FakeControlClient();
        WorkerControlPreparation preparation = preparation(Map::of, control);

        assertThrows(IllegalArgumentException.class, preparation::prepare);
        assertEquals(0, control.prepareCalls);
        preparation.close();
        preparation.close();
        assertTrue(control.closed);
        assertThrows(IllegalStateException.class, preparation::prepare);
    }

    private static WorkerControlPreparation preparation(
            WorkerPropertiesProvider propertiesProvider,
            FakeControlClient control
    ) {
        return new WorkerControlPreparation(
                "group-1",
                WorkerTransportType.WEBSOCKET,
                propertiesProvider,
                control,
                Duration.ofSeconds(1)
        );
    }

    private static final class FakeControlClient
            implements WorkerControlClient {

        private int prepareCalls;
        private boolean closed;
        private final List<Map<String, Object>> properties =
                new ArrayList<>();

        @Override
        public PreparedWorker prepare(
                String workerGroupId,
                WorkerTransportType transportType,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            prepareCalls++;
            properties.add(workerProperties);
            return PREPARED;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
