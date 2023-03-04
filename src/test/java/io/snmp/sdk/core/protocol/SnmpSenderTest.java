package io.snmp.sdk.core.protocol;

import io.snmp.sdk.core.support.exception.SnmpRequestTimeoutException;
import io.snmp.sdk.core.support.exception.SnmpSendException;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.snmp4j.PDU;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.smi.VariableBinding;

import java.time.Duration;
import java.util.List;

@Slf4j
public class SnmpSenderTest {

    private SnmpSender snmpSender;

    private String remoteTargetAddress;

    @Before
    public void before() throws ClassNotFoundException {

        snmpSender = SnmpSender.builder()
                .nio(true)
                .workerPool("snmp-msg-process-pool1",
                        1, 3,
                        Duration.ofSeconds(60),
                        1024
                )
                .retry(0)
                .reqTimeoutMills(3000)
                .nonRepeaters(0)
                .maxRepetitions(24)
                .build();

        remoteTargetAddress = "udp:192.168.1.1/161";

        snmpSender.registerUser("udp:192.168.1.1/161",
                "user0",
                AuthSHA.ID, "123456789",
                PrivAES128.ID, "123456789"
        );
    }

    @Test
    public void testSend() throws SnmpRequestTimeoutException, SnmpSendException, InterruptedException {
        //设备信息OID
        final String oid = "1.3.6.1.2.1.47.1.1.1.1.7";
        final int type = PDU.GETBULK;

        final List<? extends VariableBinding> bindings = snmpSender.sendSync(remoteTargetAddress, type, oid);

        bindings.forEach(var -> {
            log.info("var = {}", var);
        });
    }


}
