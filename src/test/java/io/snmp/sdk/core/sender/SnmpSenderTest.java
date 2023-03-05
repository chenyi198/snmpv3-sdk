package io.snmp.sdk.core.sender;

import io.snmp.sdk.core.support.UsmEncryptionEnum;
import io.snmp.sdk.core.support.exception.SnmpRequestTimeoutException;
import io.snmp.sdk.core.support.exception.SnmpSendException;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.snmp4j.PDU;
import org.snmp4j.smi.VariableBinding;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SnmpSenderTest {

    private SnmpSender snmpSender;

    private String remoteTargetAddress;

    @Before
    public void before() throws ClassNotFoundException, InterruptedException {

        snmpSender = SnmpSender.builder()
                .ioStrategy(IoStrategy.NIO_MULTI)
                .multi(3)
                .workerPool("snmp-msg-process-pool1",
                        1, 3,
                        Duration.ofSeconds(60),
                        1024
                )
                .retry(0)
                .reqTimeoutMills(200)
                .nonRepeaters(0)
                .maxRepetitions(24)
                .usmUser(Arrays.asList(
                        new UsmUserEntry("udp:192.168.1.1/161",
                                "11",
                                UsmEncryptionEnum.SHA, "12345678",
                                UsmEncryptionEnum.AES128, "12345678"
                        )
                ))
                .build();

        remoteTargetAddress = "udp:192.168.1.1/161";

        TimeUnit.MILLISECONDS.sleep(2000);
    }

    @Test
    public void testSend() throws SnmpRequestTimeoutException, SnmpSendException, InterruptedException {
        //设备信息OID
        final String oid = "1.3.6.1.2.1.47.1.1.1.1.7";
        final int type = PDU.GETBULK;

        final List<? extends VariableBinding> bindings = snmpSender.sendSync(remoteTargetAddress, type, oid);

        bindings.forEach(var -> {
            log.info("var: {}", var);
        });


    }


}
