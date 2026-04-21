package com.xa.mass.mock.command.runtime;

public final class CommandRuntimeContext {

    private final CommandHost host;
    private final CommandServices services;
    private final CommandLogger logger;
    private final ResponseEnvProvider responseEnvProvider;

    public CommandRuntimeContext(
            CommandHost host,
            CommandServices services,
            CommandLogger logger,
            ResponseEnvProvider responseEnvProvider
    ) {
        this.host = host;
        this.services = services;
        this.logger = logger;
        this.responseEnvProvider = responseEnvProvider;
    }

    public CommandHost host() {
        return host;
    }

    public CommandServices services() {
        return services;
    }

    public CommandLogger logger() {
        return logger;
    }

    public ResponseEnvProvider responseEnvProvider() {
        return responseEnvProvider;
    }

    public ClassLoader getClassLoader() {
        return host.getClassLoader();
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return host.loadClass(name);
    }
}
