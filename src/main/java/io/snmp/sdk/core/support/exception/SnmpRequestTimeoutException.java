package io.snmp.sdk.core.support.exception;

/**
 * snmp响应超时异常.
 */
public class SnmpRequestTimeoutException extends Exception {
    public SnmpRequestTimeoutException(String format, Object... args) {
        super(String.format(format, args));
    }

}
