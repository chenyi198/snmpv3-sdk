package io.snmp.sdk.core.support.exception;

/**
 * UsmUser认证信息未注册异常.
 *
 * @author ssp
 * @since 1.0
 */
public class UsmUserNotRegisterException extends RuntimeException {

    public UsmUserNotRegisterException(String format, Object... args) {
        super(String.format(format, args));
    }


}
