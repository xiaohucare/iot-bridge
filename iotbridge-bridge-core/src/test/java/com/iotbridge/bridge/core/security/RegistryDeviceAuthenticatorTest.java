package com.iotbridge.bridge.core.security;

import com.iotbridge.spi.security.DeviceAuthResult;
import com.iotbridge.spi.security.DeviceCredential;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistryDeviceAuthenticator} 规则测试：设备在册、停用、IP 绑定、密码与证书指纹。
 *
 * @author iotbridge
 * @date 2026-11-14
 */
class RegistryDeviceAuthenticatorTest {

    private DeviceSecurityProperties properties(DeviceSecurityProperties.DeviceRule... rules) {
        DeviceSecurityProperties properties = new DeviceSecurityProperties();
        properties.setDevices(new ArrayList<>(List.of(rules)));
        return properties;
    }

    private DeviceSecurityProperties.DeviceRule rule(String protocol, String deviceId) {
        DeviceSecurityProperties.DeviceRule rule = new DeviceSecurityProperties.DeviceRule();
        rule.setProtocol(protocol);
        rule.setDeviceId(deviceId);
        return rule;
    }

    @Test
    void registeredDeviceAllowed() {
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(
                properties(rule("sos-c02", "861234567890123")));

        assertEquals(DeviceAuthResult.ALLOWED, authenticator.authenticate(
                DeviceCredential.addressOnly("sos-c02", "861234567890123", "/10.0.0.1:5000")));
    }

    @Test
    void unknownDeviceDeniedEvenWithValidPassword() {
        // 注册表只登记 dev1；dev2 即使密码正确也不在册（这是防冒名的关键）
        DeviceSecurityProperties.DeviceRule dev1 = rule("mqtt", "dev1");
        dev1.setPassword("s3cret");
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(properties(dev1));

        assertEquals(DeviceAuthResult.UNKNOWN_DEVICE, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev2", "s3cret", null, "/10.0.0.2:1")));
    }

    @Test
    void sameDeviceIdDifferentProtocolIsIndependent() {
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(
                properties(rule("mqtt", "dev1")));

        assertEquals(DeviceAuthResult.UNKNOWN_DEVICE, authenticator.authenticate(
                DeviceCredential.addressOnly("sos-c02", "dev1", "/10.0.0.1:1")));
    }

    @Test
    void disabledRuleDenied() {
        DeviceSecurityProperties.DeviceRule rule = rule("mqtt", "dev1");
        rule.setEnabled(false);
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(properties(rule));

        assertEquals(DeviceAuthResult.DISABLED, authenticator.authenticate(
                DeviceCredential.addressOnly("mqtt", "dev1", "/10.0.0.1:1")));
    }

    @Test
    void passwordIsCheckedWhenConfigured() {
        DeviceSecurityProperties.DeviceRule rule = rule("mqtt", "dev1");
        rule.setPassword("device-secret");
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(properties(rule));

        assertEquals(DeviceAuthResult.BAD_CREDENTIAL, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev1", "wrong", null, "/10.0.0.1:1")));
        assertEquals(DeviceAuthResult.BAD_CREDENTIAL, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev1", null, null, "/10.0.0.1:1")));
        assertEquals(DeviceAuthResult.ALLOWED, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev1", "device-secret", null, "/10.0.0.1:1")));
    }

    @Test
    void ipBindingSupportsExactAndWildcard() {
        DeviceSecurityProperties.DeviceRule exact = rule("sos-c02", "dev1");
        exact.setAllowedIps(List.of("10.20.0.15"));
        RegistryDeviceAuthenticator strict = new RegistryDeviceAuthenticator(properties(exact));
        assertEquals(DeviceAuthResult.NETWORK_DENIED, strict.authenticate(
                DeviceCredential.addressOnly("sos-c02", "dev1", "/10.20.0.99:1")));
        assertEquals(DeviceAuthResult.ALLOWED, strict.authenticate(
                DeviceCredential.addressOnly("sos-c02", "dev1", "/10.20.0.15:1")));

        DeviceSecurityProperties.DeviceRule wildcard = rule("sos-c02", "dev2");
        wildcard.setAllowedIps(List.of("10.30.*"));
        RegistryDeviceAuthenticator loose = new RegistryDeviceAuthenticator(properties(wildcard));
        assertEquals(DeviceAuthResult.ALLOWED, loose.authenticate(
                DeviceCredential.addressOnly("sos-c02", "dev2", "/10.30.1.7:1")));
    }

    @Test
    void certFingerprintComparedIgnoringColonAndCase() {
        DeviceSecurityProperties.DeviceRule rule = rule("mqtt", "dev1");
        rule.setCertFingerprint("AB:CD:EF:01");
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(properties(rule));

        assertEquals(DeviceAuthResult.BAD_CREDENTIAL, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev1", null, "abc", "/10.0.0.1:1")));
        assertEquals(DeviceAuthResult.ALLOWED, authenticator.authenticate(
                DeviceCredential.of("mqtt", "dev1", null, "ab:cd:ef:01", "/10.0.0.1:1")));
    }

    @Test
    void emptyRegistryDeniesEveryone() {
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(new DeviceSecurityProperties());

        assertEquals(DeviceAuthResult.UNKNOWN_DEVICE, authenticator.authenticate(
                DeviceCredential.addressOnly("mqtt", "any", "/10.0.0.1:1")));
    }

    @Test
    void disabledRegistrySkipsCheckAndEnforceFollowsConfig() {
        DeviceSecurityProperties properties = new DeviceSecurityProperties();
        properties.setEnabled(false);
        properties.setEnforce(true);
        RegistryDeviceAuthenticator authenticator = new RegistryDeviceAuthenticator(properties);

        assertEquals(DeviceAuthResult.NOT_CONFIGURED, authenticator.authenticate(
                DeviceCredential.addressOnly("mqtt", "any", "/10.0.0.1:1")));
        assertTrue(authenticator.isEnforcing());

        DeviceSecurityProperties observe = new DeviceSecurityProperties();
        assertFalse(new RegistryDeviceAuthenticator(observe).isEnforcing());
    }
}
