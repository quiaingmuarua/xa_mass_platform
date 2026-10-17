package com.xa.mass.scenario.appchecks;

import java.nio.file.Path;

/** Separate JVM witness for the configuration file's process lock. */
public final class AppCheckConfigurationLockProbe {
    public static void main(String[] args) {
        try (var store = new AppCheckConfigurationStore(Path.of(args[0]), AppCheckTestSettings::seed, AppCheckTestSettings.contracts())) {
            store.view();
            System.out.println("ACQUIRED");
        } catch (AppCheckConfigurationException unavailable) {
            if (!(unavailable.getCause() instanceof java.io.IOException)
                    || !unavailable.getCause().getMessage().contains("already owned")) throw unavailable;
            System.out.println("BUSY");
            System.exit(2);
        }
    }
}
