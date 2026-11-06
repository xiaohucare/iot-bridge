package com.iotbridge.bridge.core.config;

import com.iotbridge.access.netty.config.NettyProperties;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProtocolStartupValidator} 启动期协议校验测试。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
class ProtocolStartupValidatorTest {

    private ProtocolCodecRegistry registry(String defaultType, Set<String> supported) {
        ProtocolCodecRegistry registry = mock(ProtocolCodecRegistry.class);
        when(registry.getDefaultProtocolType()).thenReturn(defaultType);
        when(registry.supportedTypes()).thenReturn(supported);
        return registry;
    }

    private NettyProperties netty(String... protocolTypes) {
        NettyProperties properties = new NettyProperties();
        NettyProperties.Listener[] listeners = new NettyProperties.Listener[protocolTypes.length];
        for (int i = 0; i < protocolTypes.length; i++) {
            NettyProperties.Listener listener = new NettyProperties.Listener();
            listener.setName("l" + i);
            listener.setPort(9000 + i);
            listener.setProtocolType(protocolTypes[i]);
            listeners[i] = listener;
        }
        properties.setListeners(Arrays.asList(listeners));
        return properties;
    }

    @Test
    void passesWhenDefaultAndListenersAreRegistered() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text", "sos-c02")), netty("sos-c02", "demo-text"));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void failsWhenDefaultProtocolMissing() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("not-exists", Set.of("demo-text")), netty("demo-text"));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void failsWhenListenerProtocolMissing() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text")), netty("unknown"));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void failsWhenMqttProtocolMissingButEnabled() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text")), netty("demo-text"), true, "mqtt");

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void skipsMqttCheckWhenDisabled() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text")), netty("demo-text"), false, "mqtt");

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void passesWhenMqttProtocolRegistered() {
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text", "mqtt")), netty("demo-text"), true, "mqtt");

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void listenerWithoutProtocolTypeFallsBackToDefault() {
        NettyProperties properties = new NettyProperties();
        properties.setListeners(Collections.emptyList());
        ProtocolStartupValidator validator = new ProtocolStartupValidator(
                registry("demo-text", Set.of("demo-text")), properties);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }
}
