package com.xa.mass.integration.workercapability.process;

import java.util.Map;

@FunctionalInterface
public interface RpcPayloadParser {

    Map<String, Object> parseLine(String line);
}
