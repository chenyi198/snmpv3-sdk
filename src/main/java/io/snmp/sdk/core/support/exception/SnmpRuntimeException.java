package io.snmp.sdk.core.support.exception;

public class SnmpRuntimeException extends RuntimeException {

    public SnmpRuntimeException(Throwable cause) {
        super(cause);
    }

    public SnmpRuntimeException(String error) {
        super(error);
    }
}
