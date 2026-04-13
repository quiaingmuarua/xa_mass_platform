package com.xa.mass.engine.v2.schedule;

public interface DaemonService {
    void start();
    void stop();
    boolean isRunning();
}
