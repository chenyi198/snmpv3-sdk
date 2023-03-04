package io.snmp.sdk.core.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
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

import java.io.IOException;
import java.util.List;

/**
 * snmp会话上下文.
 * <li>session会话，点对点，一对一.
 *
 * @author ssp
 * @since 1.0
 */
public class SnmpSession extends SnmpV3Base {

    private static final Logger log = LoggerFactory.getLogger(SnmpSession.class);

    private Snmp snmp;

    private UdpAddress localAddress = (UdpAddress) GenericAddress.parse("udp:0.0.0.0/162");

    private final Address targetAddress;

    private final UsmUser usmUser;

    private UserTarget target;

    /**
     * 构造.
     *
     * @param targetAddress udp:ip/port
     * @param usmUser       usmUser
     */
    public SnmpSession(String targetAddress, UsmUser usmUser) {
        this.targetAddress = GenericAddress.parse(targetAddress);
        this.usmUser = usmUser;
        _init();
    }

    public SnmpSession(String targetAddress,
                       String securityName,
                       OID authProtocol, String authPass,
                       OID privacyProtocol, String privacyPass) {

        this(targetAddress, new UsmUser(new OctetString(securityName),
                authProtocol, new OctetString(authPass),
                privacyProtocol, new OctetString(privacyPass)
        ));
    }

    private void _init() {

        final OctetString securityName = usmUser.getSecurityName();

        target = new UserTarget();
        target.setAddress(targetAddress);
        target.setRetries(2);
        target.setTimeout(5000);
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
        target.setSecurityName(securityName);

        try {
            final DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(localAddress);

            transport.listen();

            snmp = new Snmp(transport);
            snmp.getUSM().addUser(securityName, usmUser);

        } catch (Exception e) {
            log.error("snmp init exception!", e);
        }
    }

    public PDU sendSync(PDU msg) throws IOException {
        final ResponseEvent responseEvent = snmp.send(msg, target);
        return responseEvent.getResponse();
    }

    public List<? extends VariableBinding> sendSync(int opType, String... oid) throws IOException {

        final PDU pdu = new ScopedPDU();
        pdu.setType(opType);
        pdu.setNonRepeaters(0);
        pdu.setMaxRepetitions(1000);

        if (oid != null) {
            for (String one : oid) {
                pdu.add(new VariableBinding(new OID(one)));
            }
        }

        final ResponseEvent responseEvent = snmp.send(pdu, target);
        return responseEvent.getResponse().getVariableBindings();
    }


}
