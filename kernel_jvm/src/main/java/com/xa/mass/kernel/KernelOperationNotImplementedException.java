package com.xa.mass.kernel;

public final class KernelOperationNotImplementedException
        extends UnsupportedOperationException {

    private final String contractName;
    private final String operationName;

    public KernelOperationNotImplementedException(
            String contractName,
            String operationName
    ) {
        super(contractName + "." + operationName
                + " is not implemented in the JVM runtime");
        this.contractName = contractName;
        this.operationName = operationName;
    }

    public String contractName() {
        return contractName;
    }

    public String operationName() {
        return operationName;
    }
}
