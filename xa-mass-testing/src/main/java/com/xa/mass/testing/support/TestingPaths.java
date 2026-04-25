package com.xa.mass.testing.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves stable module-local report paths whether the runner is launched from
 * the repo root or the {@code xa-mass-testing} module directory.
 */
public final class TestingPaths {

    private static final String MODULE_NAME = "xa-mass-testing";

    private TestingPaths() {
    }

    public static Path reportDir(String reportFolderName) {
        Path moduleDir = resolveModuleDir();
        return moduleDir.resolve("target").resolve(reportFolderName);
    }

    private static Path resolveModuleDir() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (isTestingModuleDir(cwd)) {
            return cwd;
        }

        Path child = cwd.resolve(MODULE_NAME);
        if (isTestingModuleDir(child)) {
            return child;
        }

        return cwd.resolve(MODULE_NAME).normalize();
    }

    private static boolean isTestingModuleDir(Path dir) {
        return dir != null
                && MODULE_NAME.equals(String.valueOf(dir.getFileName()))
                && Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src"));
    }
}
