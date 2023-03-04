package io.snmp.sdk.core.support.exception;

/**
 * snmp请求发送异常.
 */
public class SnmpSendException extends Exception {
    public SnmpSendException(Throwable e, String format, Object... args) {
        super(String.format(format, args), e);
    }

}
