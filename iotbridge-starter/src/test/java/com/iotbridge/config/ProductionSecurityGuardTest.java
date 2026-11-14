package com.iotbridge.config;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.admin.config.AdminSecurityProperties;
import com.iotbridge.bridge.core.security.DeviceSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductionSecurityGuard} 行为测试：prod 下缺失安全配置必须启动失败，开发环境仅告警。
 *
 * @author iotbridge
 * @date 2026-11-14
 */
class ProductionSecurityGuardTest {

    private MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private DeviceSecurityProperties enforcingDevices() {
        DeviceSecurityProperties properties = new DeviceSecurityProperties();
        properties.setEnforce(true);
        return properties;
    }

    @Test
    void prodWithoutApiKeyFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, new MqttProperties(), enforcingDevices());

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("api-key"));
    }

    @Test
    void prodWithApiKeyPasses() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, new MqttProperties(), enforcingDevices());

        assertDoesNotThrow(guard::afterPropertiesSet);
    }

    @Test
    void prodWithMqttEnabledButAnonymousFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        MqttProperties mqtt = new MqttProperties();
        mqtt.setEnabled(Boolean.TRUE);
        mqtt.setUsername("");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, mqtt, enforcingDevices());

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("username"));
    }

    @Test
    void devWithoutApiKeyOnlyWarns() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        ProductionSecurityGuard guard = new ProductionSecurityGuard(new MockEnvironment(), admin, new MqttProperties(),
                enforcingDevices());

        assertDoesNotThrow(guard::afterPropertiesSet);
    }

    @Test
    void prodWithDeviceAuthObserveOnlyFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        DeviceSecurityProperties devices = new DeviceSecurityProperties();
        devices.setEnforce(false);
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin,
                new MqttProperties(), devices);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("enforce=false"));
    }

    @Test
    void prodWithDeviceAuthDisabledFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        DeviceSecurityProperties devices = new DeviceSecurityProperties();
        devices.setEnabled(false);
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin,
                new MqttProperties(), devices);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("enabled=false"));
    }
}
