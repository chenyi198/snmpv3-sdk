package io.snmp.sdk.core.protocol;

import io.snmp.sdk.core.support.Assert;
import io.snmp.sdk.core.support.JdkThreadPool;
import io.snmp.sdk.core.support.NioUdpMultiTransportMapping;
import io.snmp.sdk.core.support.NioUdpTransportMapping;
import io.snmp.sdk.core.support.exception.SnmpRequestTimeoutException;
import io.snmp.sdk.core.support.exception.SnmpRuntimeException;
import io.snmp.sdk.core.support.exception.SnmpSendException;
import io.snmp.sdk.core.support.exception.UsmUserNotRegisterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.event.ResponseListener;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.transport.UdpTransportMapping;
import org.snmp4j.util.MultiThreadedMessageDispatcher;
import org.snmp4j.util.WorkerPool;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * snmp协议层发送器.
 * <li>1对多：1个本地listen端口可同时与外部多端设备进行通信.
 * <li>sync:同步发送直到对端响应数据才返回；async：同步发送动作完成即返回，异步处理对端响应数据.
 *
 * <p>
 * 在发送时指定target对端服务器设备地址来获取多端数据.
 *
 * @author ssp
 * @since 1.0
 */
public class SnmpSender extends SnmpV3Base {

    private static final Logger log = LoggerFactory.getLogger(SnmpSender.class);

    private Snmp snmp;

    /**
     * address-UsmUser的映射表.
     *
     * <p>{@code <address,UsmUser>: <"127.0.0.0:161",usmUser>}</p>
     */
    private static final Map<String, UsmUser> targetUsmUserTable = new ConcurrentHashMap<>();

    private final UdpAddress localAddress;

    private final String bindIp;

    private final int listenPort;

    private IoStrategy ioStrategy;
    private int multi;


    /*---udp请求控制---*/
    private int reqTimeoutMills;
    private int retry;

    /*---pdu请求属性---*/
    private int nonRepeaters;
    private int maxRepetitions;

    /**
     * snmp-recv数据后置解码及异步任务线程池.
     */
    private final WorkerPool workerPool;
    /**
     * address-target的映射表.
     *
     * <p>{@code <address,UserTarget>: <"127.0.0.0:161",userTarget>}</p>
     */
    private final Map<String, UserTarget> targetTable;

    public SnmpSender(SnmpSenderBuilder builder) {
        super();

        Assert.nonNull(builder.workerPoolConfig, "SnmpSender init failed! WorkerPool not config!");

        this.bindIp = builder.bindIp;
        this.listenPort = builder.listenPort;

        ioStrategy = builder.ioStrategy;
        multi = builder.multi;

        this.reqTimeoutMills = builder.reqTimeoutMills;
        this.retry = builder.retry;
        this.nonRepeaters = builder.nonRepeaters;
        this.maxRepetitions = builder.maxRepetitions;

        this.workerPool = new JdkThreadPool(builder.workerPoolConfig);


        this.localAddress = (UdpAddress) GenericAddress.parse("udp:" + bindIp + "/" + listenPort);
        this.targetTable = new ConcurrentHashMap<>();

        init0();
        registerUsmUser(builder.usmUser);
    }

    private UdpTransportMapping transport;
    ;

    private void init0() {

        try {
            switch (ioStrategy) {
                case SINGLE_LISTEN:
                    transport = new DefaultUdpTransportMapping(localAddress);
                    break;
                case NIO:
                    transport = new NioUdpTransportMapping(localAddress);
                    break;
                case NIO_MULTI:
                    transport = new NioUdpMultiTransportMapping(multi, localAddress);
                    break;
                default:
                    throw new SnmpRuntimeException("args error: 'ioStrategy'.");
            }

            transport.listen(); //拉起listener线程.

            snmp = new Snmp(transport);

            final MessageDispatcher origMessageDispatcher = snmp.getMessageDispatcher();

            snmp.setMessageDispatcher(new MultiThreadedMessageDispatcher(workerPool, origMessageDispatcher)); //workerPool-后置response-pdu包处理线程池，主要任务：snmp解码、执行ResponseListener回调

        } catch (Exception e) {
            log.error("snmp init exception!", e);
            throw new SnmpRuntimeException(e);
        }
    }


    /*---------register UmsUser：UsmUser将被注册到全局USMTable，一个用户在一个SnmpSender实例注册过即可.----------*/
    public synchronized SnmpSender registerUser(String targetAddress, UsmUser usmUser) {

        //获取真实引擎ID后注册,以保证多个同名用户的注册
        snmp.getUSM().addUser(usmUser);
        SnmpSender.targetUsmUserTable.put(targetAddress, usmUser);
        try {
            sendSync(targetAddress, PDU.GET);
        } catch (SnmpSendException | SnmpRequestTimeoutException e) {
            log.error("registerUser exception:{},{}", e.getMessage(), targetAddress);
        }
        return this;
    }

    public SnmpSender registerUser(String targetAddress,
                                   String securityName,
                                   OID authProtocol, String authPass,
                                   OID privacyProtocol, String privacyPass) {

        registerUser(targetAddress, new UsmUser(new OctetString(securityName),
                authProtocol, new OctetString(authPass),
                privacyProtocol, new OctetString(privacyPass)
        ));

        return this;
    }

    private void registerUsmUser(List<UsmUserEntry> usmUser) {
        CompletableFuture.runAsync(() -> {
            for (UsmUserEntry usmUserEntry : usmUser) {
                registerUser(usmUserEntry.getTargetAddress(),
                        usmUserEntry.getSecurityName(),
                        usmUserEntry.getAuthProtocol().protocol().getID(), usmUserEntry.getAuthPass(),
                        usmUserEntry.getPrivacyProtocol().protocol().getID(), usmUserEntry.getPrivacyPass()
                );
            }
        });
    }

    /*---------sendSync----------*/

    public List<? extends VariableBinding> sendSync(String targetAddress, PDU pdu) throws SnmpRequestTimeoutException, SnmpSendException {
        return sendSync(getOrBuildRemoteTarget(targetAddress), pdu);
    }

    public List<? extends VariableBinding> sendSync(String targetAddress, int opType, String... oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return sendSync(getOrBuildRemoteTarget(targetAddress), opType, oid);
    }

    /**
     * sendSync.
     *
     * @param targetAddress 目标地址.
     * @param opType        请求类型,PDU
     * @param respBatch     响应结果批次数量
     * @param oid           查询oid
     * @return VariableBindings
     */
    public List<? extends VariableBinding> sendSync(String targetAddress, int opType, int respBatch, String... oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return sendSync(getOrBuildRemoteTarget(targetAddress), opType, respBatch, oid);
    }

    public List<? extends VariableBinding> sendSync(UserTarget target, int opType, String... oid) throws SnmpRequestTimeoutException, SnmpSendException {
        final PDU pdu = buildPdu(opType, this.maxRepetitions, oid);
        return sendSync(target, pdu);
    }

    public List<? extends VariableBinding> sendSync(UserTarget target, int opType, int batch, String... oid) throws SnmpRequestTimeoutException, SnmpSendException {
        final PDU pdu = buildPdu(opType, batch, oid);
        return sendSync(target, pdu);
    }

    public List<? extends VariableBinding> sendSync(UserTarget target, PDU pdu) throws SnmpRequestTimeoutException, SnmpSendException {

        final Address targetAddress = target.getAddress();
        final Vector<? extends VariableBinding> queryOid = pdu.getVariableBindings();
        log.debug("---> snmp#sendSync: target={}, query-oid={}.", targetAddress, queryOid);

        ResponseEvent responseEvent;
        try {
            responseEvent = snmp.send(pdu, target);
        } catch (IOException e) {
            throw new SnmpSendException(e, e.getMessage());
        }

        if (responseEvent == null || responseEvent.getResponse() == null) {
            log.warn("<--- snmp#sendSync: targetAddress={}, oid={}, responseEvent = null!", targetAddress, queryOid);
            throw new SnmpRequestTimeoutException("Request timeout! targetAddress = %s, timeout = %sms, request-oid = {%s}", targetAddress, this.reqTimeoutMills, queryOid);
        }

        final PDU response = responseEvent.getResponse();
        final Vector<? extends VariableBinding> variableBindings = response.getVariableBindings();

        log.debug("<--- snmp#response: targetAddress={},response-bindings={}, response={}.",
                targetAddress, variableBindings, response);

        return variableBindings;
    }


    /*---------sendAsync----------*/

    public void sendAsync(UserTarget target, ResponseListener responseListener, int opType, String... oid) throws IOException {

        final PDU pdu = buildPdu(opType, this.maxRepetitions, oid);

        log.debug("---> snmp#sendAsync: target={}, oid={}.",
                target, oid);

        snmp.send(pdu, target, null, responseListener);
    }

    public void sendAsync(UserTarget target, ResponseListener responseListener,
                          int opType, int batch, String... oid) throws IOException {

        final PDU pdu = buildPdu(opType, batch, oid);

        log.debug("---> snmp#sendAsync: target={}, oid={}.",
                target, oid);

        snmp.send(pdu, target, null, responseListener);
    }

    public void sendAsync(String targetAddress, ResponseListener responseListener, int opType, String... oid) throws IOException {

        final UserTarget curTarget = getOrBuildRemoteTarget(targetAddress);
        sendAsync(curTarget, responseListener, opType, oid);
    }

    /**
     * sendAsync.
     *
     * @param targetAddress 目标地址
     * @param opType        请求类型,PDU
     * @param batch         响应结果批次数量
     * @param oid           查询oid
     * @throws IOException IOException
     */
    public void sendAsync(String targetAddress, ResponseListener responseListener, int opType, int batch, String... oid) throws IOException {

        final UserTarget curTarget = getOrBuildRemoteTarget(targetAddress);
        sendAsync(curTarget, responseListener, opType, batch, oid);
    }

    public Set<String> getTargetAddressFormUsmTable() {
        return targetUsmUserTable.keySet();
    }


    /*---------builder----------*/

    private final Function<String, UserTarget> userTargetFunction = targetAddress -> {
        final OctetString securityName = getSecurityNameFromUsmTable(targetAddress);

        UserTarget userTarget = new UserTarget();
        userTarget.setSecurityName(securityName);
        userTarget.setAddress(GenericAddress.parse(targetAddress));
        userTarget.setRetries(SnmpSender.this.retry);
        userTarget.setTimeout(SnmpSender.this.reqTimeoutMills);
        userTarget.setVersion(SnmpConstants.version3);
        userTarget.setSecurityLevel(SecurityLevel.AUTH_PRIV);

        return userTarget;
    };

    private OctetString getSecurityNameFromUsmTable(String targetAddress) {
        final UsmUser usmUser = getUsmUserFromUsmTable(targetAddress);
        return usmUser.getSecurityName();
    }

    private UsmUser getUsmUserFromUsmTable(String targetAddress) {
        final UsmUser usmUser = SnmpSender.targetUsmUserTable.get(targetAddress);
        if (usmUser == null) {
            throw new UsmUserNotRegisterException(
                    "UsmUser-table not found target's UsmUser info! Please to register target's UsmUser info first! Target address: [%s].", targetAddress);
        }
        return usmUser;
    }

    /**
     * 构造对端封装对象.
     *
     * @param targetAddress 对端地址，对端地址对应USM认证信息必须已注册{@link SnmpSender#registerUser(String, UsmUser)}
     * @return userTarget
     */
    private UserTarget getOrBuildRemoteTarget(String targetAddress) {
        return targetTable.computeIfAbsent(targetAddress, userTargetFunction);
    }

    private PDU buildPdu(int opType, int batch, String[] oid) {
        final PDU pdu = new ScopedPDU();
        pdu.setType(opType);
        pdu.setNonRepeaters(this.nonRepeaters);
        pdu.setMaxRepetitions(batch);

        if (oid != null) {
            for (String one : oid) {
                pdu.add(new VariableBinding(new OID(one)));
            }
        }
        return pdu;
    }

    /*---------builder----------*/

    public static SnmpSenderBuilder builder() {
        return new SnmpSenderBuilder();
    }

    public static class SnmpSenderBuilder {

        private String bindIp = "0.0.0.0";
        private int listenPort = 0;

        /**
         * ---io模型---
         */
        private IoStrategy ioStrategy;
        /**
         * {@link IoStrategy#NIO_MULTI}下有效.
         */
        private int multi = 0;

        /*---udp请求控制---*/
        private int reqTimeoutMills = 3000;
        private int retry = 0;

        /*---pdu请求属性---*/
        private int nonRepeaters = 0;
        private int maxRepetitions = 1000;

        private JdkThreadPool.WorkerPoolConfig workerPoolConfig;

        private List<UsmUserEntry> usmUser;


        public static SnmpSenderBuilder newBuilder() {
            return new SnmpSenderBuilder();
        }

        public SnmpSenderBuilder bindIp(String bindIp) {
            this.bindIp = bindIp;
            return this;
        }

        public SnmpSenderBuilder listenPort(int listenPort) {
            this.listenPort = listenPort;
            return this;
        }

        public SnmpSenderBuilder ioStrategy(IoStrategy ioStrategy) {
            this.ioStrategy = ioStrategy;
            return this;
        }

        public SnmpSenderBuilder multi(int multi) {
            this.multi = multi;
            return this;
        }

        public SnmpSenderBuilder workerPool(String poolName,
                                            int corePoolSize, int maximumPoolSize,
                                            Duration keepAliveTime,
                                            int queueCapacity) {
            this.workerPoolConfig = new JdkThreadPool.WorkerPoolConfig(poolName,
                    corePoolSize, maximumPoolSize,
                    keepAliveTime,
                    queueCapacity);
            return this;
        }

        public SnmpSenderBuilder reqTimeoutMills(int reqTimeoutMills) {
            this.reqTimeoutMills = reqTimeoutMills;
            return this;
        }

        public SnmpSenderBuilder retry(int retry) {
            this.retry = retry;
            return this;
        }

        public SnmpSenderBuilder nonRepeaters(int nonRepeaters) {
            this.nonRepeaters = nonRepeaters;
            return this;
        }

        public SnmpSenderBuilder maxRepetitions(int maxRepetitions) {
            this.maxRepetitions = maxRepetitions;
            return this;
        }

        public SnmpSenderBuilder usmUser(List<UsmUserEntry> usmUser) {
            this.usmUser = usmUser;
            return this;
        }


        public SnmpSender build() {
            return new SnmpSender(this);
        }
    }

}
