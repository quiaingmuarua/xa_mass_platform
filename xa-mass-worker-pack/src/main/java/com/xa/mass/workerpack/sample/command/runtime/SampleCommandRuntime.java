package com.xa.mass.workerpack.sample.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.command.core.CommandDispatcher;
import com.xa.mass.command.core.CoreCommandRoutes;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.command.runtime.CommandLogger;
import com.xa.mass.workerpack.sample.command.fixture.SampleCommandRoutes;
import com.xa.mass.workerpack.sample.command.tool.ToolCommandRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight bootstrap for the dev-app sample client command runtime.
 */
public final class SampleCommandRuntime {

    private static final Logger log = LoggerFactory.getLogger(SampleCommandRuntime.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private SampleCommandRuntime() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ClassLoader classLoader = SampleCommandRuntime.class.getClassLoader();
        CommandContext.init(
                () -> classLoader,
                classLoader,
                new CommandLogger() {
                    @Override
                    public void info(String message) {
                        log.info("[sample-command] {}", message);
                    }

                    @Override
                    public void error(String message) {
                        log.error("[sample-command] {}", message);
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                        log.error("[sample-command] {}", message, throwable);
                    }
                },
                (response, targetApp, request) -> {
                }
        );
        CoreCommandRoutes.registerCommonRoutes();
        SampleCommandRoutes.registerSampleRoutes();
        ToolCommandRoutes.registerToolRoutes();
    }

    public static CommandResponse<?> dispatch(JsonObject request) {
        initialize();
        return CommandDispatcher.dispatch(request);
    }

    public static <T> void registerService(Class<T> type, T service) {
        initialize();
        CommandContext.getInstance().register(type, service);
    }

    public static <T> T getService(Class<T> type) {
        initialize();
        return CommandContext.getInstance().get(type);
    }
}

