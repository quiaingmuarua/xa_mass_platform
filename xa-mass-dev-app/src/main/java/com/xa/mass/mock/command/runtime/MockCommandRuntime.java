package com.xa.mass.mock.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.core.CommandDispatcher;
import com.xa.mass.mock.command.core.CoreCommandRoutes;
import com.xa.mass.mock.command.model.ApiResponse;
import com.xa.mass.mock.command.model.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight bootstrap for the dev-app mock client command runtime.
 */
public final class MockCommandRuntime {

    private static final Logger log = LoggerFactory.getLogger(MockCommandRuntime.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private MockCommandRuntime() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ClassLoader classLoader = MockCommandRuntime.class.getClassLoader();
        CommandContext.init(
                () -> classLoader,
                classLoader,
                new CommandLogger() {
                    @Override
                    public void info(String message) {
                        log.info("[mock-command] {}", message);
                    }

                    @Override
                    public void error(String message) {
                        log.error("[mock-command] {}", message);
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                        log.error("[mock-command] {}", message, throwable);
                    }
                },
                (response, targetApp, request) -> {
                }
        );
        CoreCommandRoutes.registerCommonRoutes();
    }

    public static ApiResponse<?> dispatch(JsonObject request) {
        initialize();
        return CommandDispatcher.dispatch(request);
    }
}
