package io.snmp.sdk.core;

import io.snmp.sdk.core.sender.SnmpSender;
import io.snmp.sdk.core.sender.UsmUserEntry;
import io.snmp.sdk.core.support.exception.SnmpRequestTimeoutException;
import io.snmp.sdk.core.support.exception.SnmpResponseException;
import io.snmp.sdk.core.support.exception.SnmpSendException;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.PDU;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SnmpSupport.
 *
 * @author ssp
 * @since 1.0
 */
@Slf4j
public class SnmpSupport {

    private final SnmpSender snmpSender;

    public SnmpSupport(SnmpSender snmpSender) {
        this.snmpSender = snmpSender;
    }

    /**
     * 批量查询，查询结果顺序与被查询的参数oids列表顺序一致.
     *
     * @param targetAddress 目标设备snmp地址.
     * @param oids          oid列表
     * @return Variable-list
     */
    public List<Variable> batchGet(String targetAddress, String... oids) throws SnmpRequestTimeoutException, SnmpSendException {

        final List<? extends VariableBinding> variableBindings = snmpSender.sendSync(targetAddress, PDU.GET, oids);
        if (variableBindings != null && !variableBindings.isEmpty()) {
            return variableBindings.stream()
                    .map(VariableBinding::getVariable)
                    .collect(Collectors.toList());
        }

        return null;
    }

    public Variable get(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {

        final List<? extends VariableBinding> variableBindings = snmpSender.sendSync(targetAddress, PDU.GET, oid);
        if (variableBindings != null && !variableBindings.isEmpty()) {
            final VariableBinding variableBinding = variableBindings.get(0);
            if (variableBinding.isException()) {
                throw new SnmpResponseException("Snmp response: %s! targetAddress = %s, oid = %s",
                        variableBinding.getVariable().toString(),
                        targetAddress, oid);
            }
            return variableBinding.getVariable();
        }

        return null;
    }

    public Integer getInteger(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return get(targetAddress, oid).toInt();
    }

    public Integer getInteger(String targetAddress, String oid, Integer defaultVal) {
        try {
            return getInteger(targetAddress, oid);
        } catch (SnmpRequestTimeoutException | SnmpSendException e) {
            log.error("snmp request exception!", e);
        }

        return defaultVal;
    }

    public Long getLong(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return get(targetAddress, oid).toLong();
    }

    public Long getLong(String targetAddress, String oid, Long defaultVal) {
        try {
            return getLong(targetAddress, oid);
        } catch (SnmpRequestTimeoutException | SnmpSendException e) {
            log.error("snmp request exception!", e);
        }

        return defaultVal;
    }

    public String getString(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        final Variable variable = get(targetAddress, oid);
        if (variable != null) {
            return variable.toString();
        }
        return null;
    }

    public String getString(String targetAddress, String oid, String defaultVal) {
        try {
            return get(targetAddress, oid).toString();
        } catch (SnmpRequestTimeoutException | SnmpSendException e) {
            log.error("snmp request exception!", e);
        }
        return defaultVal;
    }


    public List<? extends VariableBinding> getBulk(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return snmpSender.sendSync(targetAddress, PDU.GETBULK, oid)
                .stream()
                .filter(var -> var.getOid().toString().startsWith(oid))
                .collect(Collectors.toList());

    }

    public List<? extends VariableBinding> getBulkNoFilter(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return snmpSender.sendSync(targetAddress, PDU.GETBULK, oid);
    }

    public List<? extends VariableBinding> getBulk(String targetAddress, String oid, List<? extends VariableBinding> defaultVal) {
        try {
            return snmpSender.sendSync(targetAddress, PDU.GETBULK, oid)
                    .stream()
                    .filter(var -> var.getOid().toString().startsWith(oid))
                    .collect(Collectors.toList());
        } catch (SnmpRequestTimeoutException | SnmpSendException e) {
            log.error("snmp request exception!", e);
        }
        return defaultVal;
    }

    public List<? extends VariableBinding> getBulk(String targetAddress, int batch, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        return snmpSender.sendSync(targetAddress, PDU.GETBULK, batch, oid)
                .stream()
                .filter(var -> var.getOid().toString().startsWith(oid))
                .collect(Collectors.toList());
    }

    public Set<String> getRegisterTargetAddress() {
        return snmpSender.getTargetAddressFormUsmTable();
    }

    public Variable getBulkFirst(String targetAddress, String oid) throws SnmpRequestTimeoutException, SnmpSendException {
        final List<? extends VariableBinding> variableBindings = getBulk(targetAddress, 1, oid);
        return variableBindings.get(0).getVariable();
    }

    public void registerUsmUser(String targetAddress, UsmUserEntry userEntry) {
        snmpSender.registerUser(targetAddress,
                userEntry.getSecurityName(),
                userEntry.getAuthProtocol().protocol().getID(), userEntry.getAuthPass(),
                userEntry.getPrivacyProtocol().protocol().getID(), userEntry.getPrivacyPass()
        );
    }
}
