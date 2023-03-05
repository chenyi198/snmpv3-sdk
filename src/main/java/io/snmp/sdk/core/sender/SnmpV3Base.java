package io.snmp.sdk.core.sender;

import org.snmp4j.mp.MPv3;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.smi.OctetString;

/**
 * snmp基类.
 * SNMPv3-usm-support注册.
 *
 * @author ssp
 * @since 1.0
 */
public abstract class SnmpV3Base {

    static {
        UsmSecurityRegister.register();
    }

    private static final class UsmSecurityRegister {

        static void register() {
            //SNMPv3-support注册.
            final USM usm = new USM(SecurityProtocols.getInstance(),
                    new OctetString(MPv3.createLocalEngineID()),
                    0);

            SecurityModels.getInstance().addSecurityModel(usm);
        }
    }

}
