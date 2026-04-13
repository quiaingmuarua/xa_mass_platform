package com.xa.mass.engine.v2.schedule;

public abstract class AbstractDaemonService implements DaemonService, Runnable {
    private Thread thread;
    private volatile boolean running = false;

    @Override
    public synchronized void start() {
        if (!running) {
            running = true;
            thread = new Thread(this, this.getClass().getSimpleName() + "-Daemon");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 子类实现
    @Override
    public abstract void run();
}
