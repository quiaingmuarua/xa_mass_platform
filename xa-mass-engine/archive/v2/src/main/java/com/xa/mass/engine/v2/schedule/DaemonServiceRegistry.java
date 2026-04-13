package com.xa.mass.engine.v2.schedule;

import java.util.ArrayList;
import java.util.List;

public class DaemonServiceRegistry {
    private final List<DaemonService> services = new ArrayList<>();

    public void register(DaemonService service) {
        services.add(service);
    }
    public void startAll() {
        for (DaemonService s : services) s.start();
    }
    public void stopAll() {
        for (DaemonService s : services) s.stop();
    }
}
