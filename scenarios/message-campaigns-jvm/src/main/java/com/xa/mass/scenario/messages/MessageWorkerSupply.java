package com.xa.mass.scenario.messages;

/** Business identities shared by the scenario's startup declaration and Task/Item requests. */
final class MessageWorkerSupply {
    static final String PROJECT = "messages";
    static final String EVENT = "extension.worker.message.send";
    static final String POOL = "messaging";
    static final String POOL_FUNCTION = "worker.messaging.available";
    static final String PHONE_FUNCTION = "worker.messaging.phone";
    static final String REQUIRED_PROPERTY = "messaging.enabled";
    static final String REQUIRED_VALUE = "true";
    static final String COUNTRY_PROPERTY = "country";

    private MessageWorkerSupply() { }
}
