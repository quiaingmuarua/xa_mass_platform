package com.xa.mass.command.runtime;

public interface CommandLogger {

    void info(String message);

    void error(String message);

    void error(String message, Throwable throwable);
}
