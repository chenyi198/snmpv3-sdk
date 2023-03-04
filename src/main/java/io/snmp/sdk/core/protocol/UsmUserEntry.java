package io.snmp.sdk.core.protocol;

import io.snmp.sdk.core.support.UsmEncryptionEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * snmpV3 usm认证信息封装.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsmUserEntry {

    /**
     * 服务器snmp服务udp地址: upd:127.0.0.1/161.
     */
    private String targetAddress;
    /**
     * snmp服务账户名.
     */
    private String securityName;
    /**
     * snmp服务auth签名算法类型：SHA/HMAC128SHA224.
     */
    private UsmEncryptionEnum authProtocol;
    /**
     * snmp服务auth密钥.
     */
    private String authPass;
    /**
     * snmp服务privacy加密算法类型：AES128/.
     */
    private UsmEncryptionEnum privacyProtocol;
    /**
     * snmp服务privacy加密密钥.
     */
    private String privacyPass;



}
