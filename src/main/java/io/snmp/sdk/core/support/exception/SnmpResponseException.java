package io.snmp.sdk.core.support.exception;

/**
 * snmp响应异常.
 */
public class SnmpResponseException extends RuntimeException {
    public SnmpResponseException(String format, Object... args) {
        super(String.format(format, args));
    }

}
