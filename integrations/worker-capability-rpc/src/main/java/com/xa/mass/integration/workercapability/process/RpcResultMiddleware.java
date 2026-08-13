package com.xa.mass.integration.workercapability.process;

import java.util.List;

@FunctionalInterface
public interface RpcResultMiddleware {

    void process(List<RpcResult> results) throws Exception;
}
