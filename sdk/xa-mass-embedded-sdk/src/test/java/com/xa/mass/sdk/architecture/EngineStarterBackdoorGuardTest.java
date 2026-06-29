package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineStarterBackdoorGuardTest {

    @Test
    void massApplicationDoesNotExposeRawMassEngine() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");

        assertFalse(Pattern.compile("\\bpublic\\s+MassEngine\\s+getEngine\\s*\\(").matcher(source).find(),
                "MassApplication.getEngine() is a deleted ECSP backdoor");
    }

    @Test
    void massEngineDoesNotExposeRawEngineConfig() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");

        assertFalse(Pattern.compile("\\bpublic\\s+EngineConfig\\s+getConfig\\s*\\(").matcher(source).find(),
                "MassEngine.getConfig() is a deleted ECSP backdoor");
    }

    @Test
    void sourceDoesNotKeepChainedBackdoorCalls() {
        String embeddedStarter = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String sdkFacade = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java");

        assertFalse(embeddedStarter.contains("getEngine().getConfig()"));
        assertFalse(sdkFacade.contains("getEngine().getConfig()"));
    }
}
