package com.xa.mass.android.capabilities;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class AndroidDemoCapabilities {

    public static final String STATE_READ = "android.demo.state.read";
    public static final String BATTERY_READ = "android.demo.battery.read";

    static final String PREFERENCES =
            "android-worker-demo-state-capability";
    private static final String COUNTER = "counter";

    @FunctionalInterface
    public interface Listener {

        void onChanged();
    }

    @FunctionalInterface
    interface BatteryStateReader {

        BatteryReading read();
    }

    private final Context applicationContext;
    private final SharedPreferences preferences;
    private final BatteryStateReader batteryStateReader;
    private final Collection<WorkerEventDefinition<?>> definitions;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private int processedCommands;
    private String lastEvent;

    public AndroidDemoCapabilities(Context context) {
        this(context, null);
    }

    AndroidDemoCapabilities(
            Context context,
            BatteryStateReader batteryStateReader
    ) {
        applicationContext = requireApplicationContext(context);
        preferences = applicationContext.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        this.batteryStateReader = batteryStateReader == null
                ? new SystemBatteryStateReader(applicationContext)
                : batteryStateReader;
        definitions = Collections.unmodifiableList(Arrays.asList(
                WorkerEventDefinition.of(
                        "TASK",
                        STATE_READ,
                        WorkerEventParameterResolvers.jsonMap(),
                        ignored -> executeStateRead()
                ),
                WorkerEventDefinition.of(
                        "TASK",
                        BATTERY_READ,
                        WorkerEventParameterResolvers.jsonMap(),
                        ignored -> executeBatteryRead()
                )
        ));
    }

    public Collection<? extends WorkerEventDefinition<?>> definitions() {
        return definitions;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                preferences.getInt(COUNTER, 0),
                processedCommands,
                lastEvent
        );
    }

    public int incrementCounter() {
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

    public int resetCounter() {
        synchronized (this) {
            persistCounter(0);
        }
        publish();
        return 0;
    }

    public void addListener(Listener listener) {
        Listener resolved = Objects.requireNonNull(
                listener,
                "listener"
        );
        listeners.add(resolved);
        resolved.onChanged();
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private String executeStateRead() {
        int counter;
        synchronized (this) {
            counter = preferences.getInt(COUNTER, 0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageName", applicationContext.getPackageName());
        result.put("versionName", versionName());
        result.put("sdkInt", Build.VERSION.SDK_INT);
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("model", Build.MODEL);
        result.put("counter", counter);
        return complete(STATE_READ, result);
    }

    private String executeBatteryRead() {
        BatteryReading reading = Objects.requireNonNull(
                batteryStateReader.read(),
                "batteryStateReader.read()"
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", reading.available());
        result.put("capacityPercent", reading.capacityPercent());
        result.put("charging", reading.charging());
        return complete(BATTERY_READ, result);
    }

    private String complete(String eventCode, Map<String, Object> result) {
        String encoded = Jsons.toJson(result);
        synchronized (this) {
            processedCommands = Math.addExact(processedCommands, 1);
            lastEvent = eventCode;
        }
        publish();
        return encoded;
    }

    private String versionName() {
        try {
            PackageInfo info = applicationContext.getPackageManager()
                    .getPackageInfo(applicationContext.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
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
                // A UI observer cannot interrupt capability execution.
            }
        }
    }

    private static Context requireApplicationContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        Context resolved = context.getApplicationContext();
        return resolved == null ? context : resolved;
    }

    static final class BatteryReading {

        private final boolean available;
        private final Integer capacityPercent;
        private final Boolean charging;

        private BatteryReading(
                boolean available,
                Integer capacityPercent,
                Boolean charging
        ) {
            this.available = available;
            this.capacityPercent = capacityPercent;
            this.charging = charging;
        }

        static BatteryReading available(
                int capacityPercent,
                boolean charging
        ) {
            if (capacityPercent < 0 || capacityPercent > 100) {
                throw new IllegalArgumentException(
                        "capacityPercent must be in 0..100"
                );
            }
            return new BatteryReading(
                    true,
                    capacityPercent,
                    charging
            );
        }

        static BatteryReading unavailable() {
            return new BatteryReading(false, null, null);
        }

        boolean available() {
            return available;
        }

        Integer capacityPercent() {
            return capacityPercent;
        }

        Boolean charging() {
            return charging;
        }
    }

    public static final class Snapshot {

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

        public int counter() {
            return counter;
        }

        public int processedCommands() {
            return processedCommands;
        }

        public String lastEvent() {
            return lastEvent;
        }
    }

    private static final class SystemBatteryStateReader
            implements BatteryStateReader {

        private final Context applicationContext;

        private SystemBatteryStateReader(Context applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        public BatteryReading read() {
            try {
                BatteryManager manager = (BatteryManager)
                        applicationContext.getSystemService(
                                Context.BATTERY_SERVICE
                        );
                if (manager == null) {
                    return BatteryReading.unavailable();
                }
                int capacity = manager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                );
                if (capacity < 0 || capacity > 100) {
                    return BatteryReading.unavailable();
                }
                return BatteryReading.available(
                        capacity,
                        manager.isCharging()
                );
            } catch (RuntimeException error) {
                return BatteryReading.unavailable();
            }
        }
    }
}
