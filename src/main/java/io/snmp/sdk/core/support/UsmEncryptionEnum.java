package io.snmp.sdk.core.support;

import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityProtocol;

public enum UsmEncryptionEnum {

    SHA("SHA", new AuthSHA()),
    MD5("MD5", new AuthMD5()),
    HMAC128SHA224("HMAC128SHA224", new AuthHMAC128SHA224()),
    HMAC192SHA256("HMAC192SHA256", new AuthHMAC192SHA256()),
    HMAC256SHA384("HMAC256SHA384", new AuthHMAC256SHA384()),
    HMAC384SHA512("HMAC384SHA512", new AuthHMAC384SHA512()),
    DES("DES", new PrivDES()),
    _3DES("3DES", new Priv3DES()),
    AES128("AES128", new PrivAES128()),
    AES192("AES192", new PrivAES192()),
    AES256("AES256", new PrivAES256());

    private String name;
    private SecurityProtocol protocol;

    UsmEncryptionEnum(String name, SecurityProtocol protocol) {
        this.name = name;
        this.protocol = protocol;
    }

    public static SecurityProtocol find(String source) {
        for (UsmEncryptionEnum value : UsmEncryptionEnum.values()) {
            if (value.getName().equalsIgnoreCase(source)) {
                return value.protocol;
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public SecurityProtocol protocol() {
        return protocol;
    }
}
