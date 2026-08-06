package com.xa.mass.integration.androidworker;

import android.content.Context;
import android.content.SharedPreferences;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class AndroidDemoStateCapability {

    static final String EVENT_CODE = "android.demo.state.read";
    static final String PREFERENCES =
            "android-worker-demo-state-capability";
    private static final String COUNTER = "counter";

    interface Listener {

        void onChanged();
    }

    private final SharedPreferences preferences;
    private final AndroidDeviceProperties deviceProperties;
    private final Collection<WorkerEventDefinition<?>> definitions;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private int processedCommands;
    private String lastEvent;

    AndroidDemoStateCapability(
            Context context,
            AndroidDeviceProperties deviceProperties
    ) {
        if (context == null || deviceProperties == null) {
            throw new IllegalArgumentException(
                    "Capability dependencies must be present"
            );
        }
        preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.deviceProperties = deviceProperties;
        definitions = Collections.singletonList(
                WorkerEventDefinition.of(
                        "TASK",
                        EVENT_CODE,
                        WorkerEventParameterResolvers.jsonMap(),
                        ignored -> execute()
                )
        );
    }

    Collection<WorkerEventDefinition<?>> definitions() {
        return definitions;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                preferences.getInt(COUNTER, 0),
                processedCommands,
                lastEvent
        );
    }

    int incrementCounter() {
        int value;
        synchronized (this) {
            value = Math.addExact(
                    preferences.getInt(COUNTER, 0),
                    1
            );
            persistCounter(value);
        }
        publish();
        return value;
    }

    int resetCounter() {
        synchronized (this) {
            persistCounter(0);
        }
        publish();
        return 0;
    }

    void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must be present");
        }
        listeners.add(listener);
        listener.onChanged();
    }

    void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private String execute() {
        int counter;
        synchronized (this) {
            counter = preferences.getInt(COUNTER, 0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageName", deviceProperties.packageName());
        result.put("versionName", deviceProperties.versionName());
        result.put("sdkInt", deviceProperties.sdkInt());
        result.put("manufacturer", deviceProperties.manufacturer());
        result.put("model", deviceProperties.model());
        result.put("counter", counter);
        String encoded = Jsons.toJson(result);
        synchronized (this) {
            processedCommands = Math.addExact(processedCommands, 1);
            lastEvent = EVENT_CODE + " counter=" + counter;
        }
        publish();
        return encoded;
    }

    private void persistCounter(int value) {
        if (!preferences.edit().putInt(COUNTER, value).commit()) {
            throw new IllegalStateException(
                    "Unable to persist Android demo capability counter"
            );
        }
    }

    private void publish() {
        for (Listener listener : listeners) {
            try {
                listener.onChanged();
            } catch (RuntimeException ignored) {
                // A demo observer cannot interrupt capability execution.
            }
        }
    }

    static final class Snapshot {

        private final int counter;
        private final int processedCommands;
        private final String lastEvent;

        private Snapshot(
                int counter,
                int processedCommands,
                String lastEvent
        ) {
            this.counter = counter;
            this.processedCommands = processedCommands;
            this.lastEvent = lastEvent;
        }

        int counter() {
            return counter;
        }

        int processedCommands() {
            return processedCommands;
        }

        String lastEvent() {
            return lastEvent;
        }
    }
}
