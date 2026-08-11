package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class RegisteredWorkerPreparationTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI ENDPOINT = URI.create(
            "ws://127.0.0.1:18083/worker"
    );

    @Test
    void registersPersistsAndBindsOneDefensivePropertiesCopy()
            throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore();
        FakeControlClient control = new FakeControlClient();
        List<String> tags = new ArrayList<>(List.of("one"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("tags", tags);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("clientWorkerKey", "installation-1");
        source.put("nested", nested);
        RegisteredWorkerPreparation preparation = preparation(
                identity,
                () -> source,
                control
        );

        PreparedWorker prepared = preparation.prepare();

        assertEquals(WORKER_ID, prepared.workerId());
        assertEquals(ENDPOINT, prepared.endpointUri());
        assertEquals(Optional.of(WORKER_ID), identity.loadWorkerId());
        assertEquals(1, control.registerCalls);
        assertEquals(1, control.bindCalls);
        assertSame(
                control.registeredProperties,
                control.boundProperties.get(0)
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> control.lastProperties.put("new", "value")
        );
        source.put("afterPrepare", true);
        tags.add("two");
        assertTrue(!control.lastProperties.containsKey("afterPrepare"));
        assertEquals(
                List.of("one"),
                ((Map<?, ?>) control.lastProperties.get("nested"))
                        .get("tags")
        );
    }

    @Test
    void cachedIdentitySkipsRegisterAndEveryPrepareReloadsAndBinds()
            throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore(WORKER_ID);
        FakeControlClient control = new FakeControlClient();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        snapshots.add(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 1
        ));
        snapshots.add(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 2
        ));
        int[] index = {0};
        RegisteredWorkerPreparation preparation = preparation(
                identity,
                () -> snapshots.get(index[0]++),
                control
        );

        preparation.prepare();
        preparation.prepare();

        assertEquals(0, control.registerCalls);
        assertEquals(2, control.bindCalls);
        assertEquals(2, control.boundProperties.get(1).get("version"));
    }

    @Test
    void closeOwnsControlClientAndRejectsFurtherPreparation() {
        FakeControlClient control = new FakeControlClient();
        RegisteredWorkerPreparation preparation = preparation(
                new MutableIdentityStore(WORKER_ID),
                RegisteredWorkerPreparationTest::properties,
                control
        );

        preparation.close();
        preparation.close();

        assertTrue(control.closed);
        assertThrows(IllegalStateException.class, preparation::prepare);
    }

    @Test
    void invalidPropertiesFailBeforeAnyControlCall() {
        FakeControlClient control = new FakeControlClient();
        RegisteredWorkerPreparation preparation = preparation(
                new MutableIdentityStore(),
                Map::of,
                control
        );

        assertThrows(IllegalArgumentException.class, preparation::prepare);
        assertEquals(0, control.registerCalls);
        assertEquals(0, control.bindCalls);
    }

    @Test
    void nonBlankCachedIdentityIsNotFormatParsed() throws Exception {
        FakeControlClient control = new FakeControlClient();
        RegisteredWorkerPreparation preparation = preparation(
                new MutableIdentityStore("server-issued-worker-id"),
                RegisteredWorkerPreparationTest::properties,
                control
        );

        PreparedWorker prepared = preparation.prepare();

        assertEquals("server-issued-worker-id", prepared.workerId());
        assertEquals(0, control.registerCalls);
        assertEquals(1, control.bindCalls);
    }

    private static RegisteredWorkerPreparation preparation(
            WorkerIdentityStore identity,
            WorkerPropertiesProvider propertiesProvider,
            FakeControlClient control
    ) {
        return new RegisteredWorkerPreparation(
                "group-1",
                WorkerTransportType.WEBSOCKET,
                identity,
                propertiesProvider,
                control,
                Duration.ofSeconds(1)
        );
    }

    private static Map<String, Object> properties() {
        return Map.of("clientWorkerKey", "installation-1");
    }

    private static final class MutableIdentityStore
            implements WorkerIdentityStore {

        private String workerId;

        private MutableIdentityStore() {
        }

        private MutableIdentityStore(String workerId) {
            this.workerId = workerId;
        }

        @Override
        public Optional<String> loadWorkerId() {
            return Optional.ofNullable(workerId);
        }

        @Override
        public void saveWorkerId(String workerId) {
            this.workerId = workerId;
        }
    }

    private static final class FakeControlClient
            implements WorkerControlClient {

        private int registerCalls;
        private int bindCalls;
        private boolean closed;
        private Map<String, Object> registeredProperties;
        private Map<String, Object> lastProperties;
        private final List<Map<String, Object>> boundProperties =
                new ArrayList<>();

        @Override
        public String register(
                String workerGroupId,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            registerCalls++;
            registeredProperties = workerProperties;
            lastProperties = workerProperties;
            return WORKER_ID;
        }

        @Override
        public URI bind(
                String workerGroupId,
                String workerId,
                WorkerTransportType transportType,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            bindCalls++;
            lastProperties = workerProperties;
            boundProperties.add(workerProperties);
            return ENDPOINT;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
