package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterManagerTest {

    @Test
    void managesAdaptersInRegistrationOrder() {
        List<String> events = new ArrayList<>();
        FakeAdapter first = new FakeAdapter("adapter-1", events, false);
        FakeAdapter second = new FakeAdapter("adapter-2", events, false);
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();

        manager.register(first);
        manager.register(second);

        manager.start();
        manager.start();
        manager.close();
        manager.close();

        assertThat(events).containsExactly(
                "start:adapter-1",
                "start:adapter-2",
                "close:adapter-2",
                "close:adapter-1"
        );
    }

    @Test
    void rejectsInvalidRegistration() {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        FakeAdapter adapter = new FakeAdapter(
                "adapter-1",
                new ArrayList<>(),
                false
        );
        manager.register(adapter);

        assertThatThrownBy(() -> manager.register(adapter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        manager.start();
        assertThatThrownBy(() -> manager.register(new FakeAdapter(
                "adapter-2",
                new ArrayList<>(),
                false
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before start");

        manager.close();

        WorkerDeliveryAdapterManager polling =
                new WorkerDeliveryAdapterManager();
        assertThatThrownBy(() -> polling.register(new FakeAdapter(
                "system-polling",
                new ArrayList<>(),
                false
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system-polling");
    }

    @Test
    void startFailureClosesCurrentAndEarlierAdaptersInReverse() {
        List<String> events = new ArrayList<>();
        FakeAdapter first = new FakeAdapter("adapter-1", events, false);
        FakeAdapter second = new FakeAdapter("adapter-2", events, true);
        FakeAdapter third = new FakeAdapter("adapter-3", events, false);
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(first);
        manager.register(second);
        manager.register(third);

        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");

        assertThat(events).containsExactly(
                "start:adapter-1",
                "start:adapter-2",
                "close:adapter-2",
                "close:adapter-1"
        );
        assertThat(third.state())
                .isEqualTo(WorkerDeliveryAdapterState.REGISTERED);
        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closeAggregatesFailuresWithoutSkippingAdapters() {
        List<String> events = new ArrayList<>();
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(new FakeAdapter(
                "adapter-1",
                events,
                false,
                true
        ));
        manager.register(new FakeAdapter(
                "adapter-2",
                events,
                false,
                true
        ));

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                manager::close
        );

        assertThat(events).containsExactly(
                "close:adapter-2",
                "close:adapter-1"
        );
        assertThat(failure.getSuppressed()).hasSize(1);
    }

    private static final class FakeAdapter
            implements WorkerDeliveryAdapter {

        private final String adapterId;
        private final List<String> events;
        private final boolean failStart;
        private final boolean failClose;
        private WorkerDeliveryAdapterState state =
                WorkerDeliveryAdapterState.REGISTERED;

        private FakeAdapter(
                String adapterId,
                List<String> events,
                boolean failStart
        ) {
            this(adapterId, events, failStart, false);
        }

        private FakeAdapter(
                String adapterId,
                List<String> events,
                boolean failStart,
                boolean failClose
        ) {
            this.adapterId = adapterId;
            this.events = events;
            this.failStart = failStart;
            this.failClose = failClose;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public WorkerDeliveryAdapterState state() {
            return state;
        }

        @Override
        public void start() {
            events.add("start:" + adapterId);
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
            state = WorkerDeliveryAdapterState.RUNNING;
        }

        @Override
        public void close() {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            events.add("close:" + adapterId);
            state = WorkerDeliveryAdapterState.CLOSED;
            if (failClose) {
                throw new IllegalStateException(
                        "close failed: " + adapterId
                );
            }
        }
    }
}
