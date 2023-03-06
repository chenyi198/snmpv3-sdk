package io.snmp.sdk.core.sender;

import io.snmp.sdk.core.sender.transport.NioUdpTransportMapping;

/**
 * SnmpSender器transport-IO模型.
 */
public enum IoStrategy {

    /**
     * 单个监听端口模式——单recv线程.
     * <li>snmp4j默认实现,.
     * <li>transport对应{@link org.snmp4j.transport.DefaultUdpTransportMapping}
     */
    DEFAULT_LISTEN,
    /**
     * 多路复用器NIO模式—
     * <li>transport对应{@link NioUdpTransportMapping}
     */
    NIO
}
