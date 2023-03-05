package io.snmp.sdk.core.sender;

import io.snmp.sdk.core.sender.transport.NioUdpMultiTransportMapping;
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
    SINGLE_LISTEN,
    /**
     * 单多路复用器NIO模式——单recv线程.
     * <li>transport对应{@link NioUdpTransportMapping}
     */
    NIO,
    /**
     * 多个多复用器NIO模式——recv线程组.
     * <li>适用于通信对端个数多，收发数据并发量高的情况.
     * <li>transport对应{@link NioUdpMultiTransportMapping}
     */
    NIO_MULTI
}
