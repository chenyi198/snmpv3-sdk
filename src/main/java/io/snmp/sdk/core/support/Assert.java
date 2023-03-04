package io.snmp.sdk.core.support;

public class Assert {

    public static void isTrue(boolean exp, String error) {
        if (!exp) {
            throw new IllegalArgumentException(error);
        }
    }

    public static void nonNull(Object o, String error) {
        isTrue(o != null, error);
    }

}
