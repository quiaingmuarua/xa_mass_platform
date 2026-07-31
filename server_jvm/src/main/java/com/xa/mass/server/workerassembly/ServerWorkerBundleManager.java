package com.xa.mass.server.workerassembly;

import com.xa.mass.server.workerassembly.phonenumber
        .PhoneNumberWorkerBundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ServerWorkerBundleManager implements AutoCloseable {

    private final List<PhoneNumberWorkerBundle> bundles;
    private boolean started;
    private boolean closed;

    public ServerWorkerBundleManager(
            List<PhoneNumberWorkerBundle> bundles
    ) {
        Objects.requireNonNull(bundles, "bundles");
        this.bundles = List.copyOf(bundles);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Server Worker bundle manager is closed"
            );
        }
        if (started) {
            return;
        }

        List<PhoneNumberWorkerBundle> startedBundles =
                new ArrayList<>();
        PhoneNumberWorkerBundle starting = null;
        try {
            for (PhoneNumberWorkerBundle bundle : bundles) {
                starting = bundle;
                bundle.start();
                startedBundles.add(bundle);
            }
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            if (starting != null
                    && !startedBundles.contains(starting)) {
                closeAndSuppress(starting, failure);
            }
            Collections.reverse(startedBundles);
            for (PhoneNumberWorkerBundle bundle : startedBundles) {
                closeAndSuppress(bundle, failure);
            }
            throw failure;
        }
    }

    public List<String> bundleIds() {
        return bundles.stream()
                .map(PhoneNumberWorkerBundle::bundleId)
                .toList();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<PhoneNumberWorkerBundle> closing =
                new ArrayList<>(bundles);
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (PhoneNumberWorkerBundle bundle : closing) {
            try {
                bundle.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeAndSuppress(
            PhoneNumberWorkerBundle bundle,
            RuntimeException failure
    ) {
        try {
            bundle.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
