package com.xa.mass.mock.command.runtime;

public interface CommandLogger {

    void info(String message);

    void error(String message);

    void error(String message, Throwable throwable);
}
