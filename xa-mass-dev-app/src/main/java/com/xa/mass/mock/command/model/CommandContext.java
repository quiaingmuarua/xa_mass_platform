package com.xa.mass.mock.command.model;

import com.xa.mass.mock.command.runtime.CommandHost;
import com.xa.mass.mock.command.runtime.CommandLogger;
import com.xa.mass.mock.command.runtime.CommandRuntimeContext;
import com.xa.mass.mock.command.runtime.CommandServices;
import com.xa.mass.mock.command.runtime.ResponseEnvProvider;

public class CommandContext {

    private static final CommandLogger NO_OP_LOGGER = new CommandLogger() {
        @Override
        public void info(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    };

    private static final ResponseEnvProvider NO_OP_ENV_PROVIDER = (response, targetApp, request) -> {
    };

    private static final CommandContext INSTANCE = new CommandContext();

    private final CommandServices services = new CommandServices();
    private volatile Object compatibilityContext;
    private volatile CommandRuntimeContext runtimeContext;

    public static CommandContext getInstance() {
        return INSTANCE;
    }

    public static void init(Object compatibilityContext) {
        if (compatibilityContext == null) {
            throw new IllegalArgumentException("context is null");
        }
        init(new ReflectiveCommandHost(compatibilityContext), compatibilityContext, NO_OP_LOGGER, NO_OP_ENV_PROVIDER);
    }

    public static void init(
            CommandHost host,
            Object compatibilityContext,
            CommandLogger logger,
            ResponseEnvProvider responseEnvProvider
    ) {
        if (host == null) {
            throw new IllegalArgumentException("host is null");
        }
        INSTANCE.compatibilityContext = compatibilityContext;
        INSTANCE.runtimeContext = new CommandRuntimeContext(
                host,
                INSTANCE.services,
                logger == null ? NO_OP_LOGGER : logger,
                responseEnvProvider == null ? NO_OP_ENV_PROVIDER : responseEnvProvider
        );
    }

    public <T> T getContext() {
        Object context = compatibilityContext;
        if (context == null) {
            throw new IllegalStateException("CommandContext not initialized");
        }
        @SuppressWarnings("unchecked")
        T casted = (T) context;
        return casted;
    }

    public CommandRuntimeContext runtime() {
        CommandRuntimeContext currentRuntimeContext = runtimeContext;
        if (currentRuntimeContext == null) {
            throw new IllegalStateException("CommandContext not initialized");
        }
        return currentRuntimeContext;
    }

    public ClassLoader getClassLoader() {
        return runtime().getClassLoader();
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return runtime().loadClass(name);
    }

    public CommandServices services() {
        return services;
    }

    public CommandLogger logger() {
        CommandRuntimeContext currentRuntimeContext = runtimeContext;
        return currentRuntimeContext == null ? NO_OP_LOGGER : currentRuntimeContext.logger();
    }

    public ResponseEnvProvider responseEnvProvider() {
        CommandRuntimeContext currentRuntimeContext = runtimeContext;
        return currentRuntimeContext == null ? NO_OP_ENV_PROVIDER : currentRuntimeContext.responseEnvProvider();
    }

    public <T> void register(Class<T> type, T service) {
        services.register(type, service);
    }

    public <T> T require(Class<T> type) {
        return services.require(type);
    }

    public <T> T get(Class<T> type) {
        return services.get(type);
    }

    private static final class ReflectiveCommandHost implements CommandHost {
        private final Object compatibilityContext;

        private ReflectiveCommandHost(Object compatibilityContext) {
            this.compatibilityContext = compatibilityContext;
        }

        @Override
        public ClassLoader getClassLoader() {
            if (compatibilityContext instanceof ClassLoader) {
                return (ClassLoader) compatibilityContext;
            }
            try {
                Object result = compatibilityContext.getClass().getMethod("getClassLoader").invoke(compatibilityContext);
                if (result instanceof ClassLoader) {
                    return (ClassLoader) result;
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("host does not expose getClassLoader()", e);
            }
            throw new IllegalStateException("host getClassLoader() did not return a ClassLoader");
        }
    }
}
