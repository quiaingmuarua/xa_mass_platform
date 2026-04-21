package com.xa.mass.mock.command.runtime;

public interface CommandHost {

    ClassLoader getClassLoader();

    default Class<?> loadClass(String name) throws ClassNotFoundException {
        return getClassLoader().loadClass(name);
    }
}
