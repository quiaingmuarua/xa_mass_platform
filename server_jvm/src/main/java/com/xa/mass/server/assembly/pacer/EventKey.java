package com.xa.mass.server.assembly.pacer;

/** Exact event selection; Group is the registration scope. */
record EventKey(String messageEventName, String observationEventName) {}
