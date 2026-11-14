package com.iotbridge.config;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.admin.config.AdminSecurityProperties;
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

    @Test
    void prodWithoutApiKeyFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, new MqttProperties());

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("api-key"));
    }

    @Test
    void prodWithApiKeyPasses() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, new MqttProperties());

        assertDoesNotThrow(guard::afterPropertiesSet);
    }

    @Test
    void prodWithMqttEnabledButAnonymousFailsFast() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        admin.setApiKey("secret-key");
        MqttProperties mqtt = new MqttProperties();
        mqtt.setEnabled(Boolean.TRUE);
        mqtt.setUsername("");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(prodEnvironment(), admin, mqtt);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("username"));
    }

    @Test
    void devWithoutApiKeyOnlyWarns() {
        AdminSecurityProperties admin = new AdminSecurityProperties();
        ProductionSecurityGuard guard = new ProductionSecurityGuard(new MockEnvironment(), admin, new MqttProperties());

        assertDoesNotThrow(guard::afterPropertiesSet);
    }
}
