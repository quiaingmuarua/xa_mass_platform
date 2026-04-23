package com.xa.mass.command.runtime;

public interface CommandHost {

    ClassLoader getClassLoader();

    default Class<?> loadClass(String name) throws ClassNotFoundException {
        return getClassLoader().loadClass(name);
    }
}
