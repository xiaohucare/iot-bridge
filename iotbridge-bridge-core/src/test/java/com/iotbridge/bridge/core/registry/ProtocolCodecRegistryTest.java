package com.iotbridge.bridge.core.registry;

import com.iotbridge.spi.codec.ProtocolCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iotbridge.common.exception.BridgeException;

/**
 * {@link ProtocolCodecRegistry} 通过 ServiceLoader 加载示例插件的测试。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
class ProtocolCodecRegistryTest {

    @Test
    void shouldDiscoverDemoCodecViaServiceLoader() {
        ProtocolCodecRegistry registry = new ProtocolCodecRegistry(List.of(), "demo-text");

        assertTrue(registry.supportedTypes().contains("demo-text"),
                "ServiceLoader 应发现 demo-text 插件");
        ProtocolCodec codec = registry.getDefault();
        assertEquals("demo-text", codec.protocolType());
    }

    @Test
    void resolveShouldFallbackToDefaultWhenTypeBlank() {
        ProtocolCodecRegistry registry = new ProtocolCodecRegistry(List.of(), "demo-text");

        assertEquals("demo-text", registry.resolve(null).protocolType());
        assertEquals("demo-text", registry.resolve("").protocolType());
    }

    @Test
    void getShouldThrowWhenProtocolMissing() {
        ProtocolCodecRegistry registry = new ProtocolCodecRegistry(List.of(), "demo-text");

        assertThrows(BridgeException.class, () -> registry.get("not-exist"));
    }
}
