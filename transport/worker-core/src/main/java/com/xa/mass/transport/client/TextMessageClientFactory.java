package com.xa.mass.transport.client;

import java.net.URI;

@FunctionalInterface
public interface TextMessageClientFactory {

    TextMessageClient create(URI endpointUri);
}
