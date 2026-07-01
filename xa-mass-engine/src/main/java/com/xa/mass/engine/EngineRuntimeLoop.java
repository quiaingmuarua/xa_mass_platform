package com.xa.mass.engine;

public interface EngineRuntimeLoop {

    String name();

    long intervalMillis();

    void runOnce();
}
