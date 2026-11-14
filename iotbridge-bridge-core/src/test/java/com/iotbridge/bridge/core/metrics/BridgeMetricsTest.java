package com.iotbridge.bridge.core.metrics;

import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.spi.session.DeviceSessionQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BridgeMetrics} 指标注册与计数的冒烟测试。
 *
 * @author iotbridge
 * @date 2026-11-14
 */
class BridgeMetricsTest {

    @Test
    void nullRegistryDegradesToNoopWithoutFailing() {
        // 精简部署（无 actuator）不应因缺少 MeterRegistry 而启动失败
        BridgeMetrics metrics = new BridgeMetrics((io.micrometer.core.instrument.MeterRegistry) null, org.mockito.Mockito.mock(
                com.iotbridge.spi.session.DeviceSessionQuery.class),
                org.mockito.Mockito.mock(com.iotbridge.bridge.core.buffer.DownlinkBuffer.class));

        metrics.uplinkFrame("sos-c02", BridgeMetrics.UPLINK_OK);
        metrics.uplinkDropped();
        metrics.downlink(BridgeMetrics.DOWNLINK_DELIVERED);
        metrics.reflow(BridgeMetrics.REFLOW_SENT);
        metrics.securityAuth("sos-c02", "allowed");
    }

    @Test
    void countersAndGaugesAreRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeviceSessionQuery sessions = mock(DeviceSessionQuery.class);
        when(sessions.onlineCount()).thenReturn(3);
        DownlinkBufferProperties properties = new DownlinkBufferProperties();
        properties.setBufferEnabled(true);
        DownlinkBuffer buffer = new DownlinkBuffer(properties);

        BridgeMetrics metrics = new BridgeMetrics(registry, sessions, buffer);

        metrics.uplinkFrame("sos-c02", BridgeMetrics.UPLINK_OK);
        metrics.uplinkFrame("sos-c02", BridgeMetrics.UPLINK_OK);
        metrics.uplinkDropped();
        metrics.downlink(BridgeMetrics.DOWNLINK_DELIVERED);
        metrics.reflow(BridgeMetrics.REFLOW_SENT);

        assertEquals(2.0, registry.get("iotbridge.uplink.frames")
                .tags("protocol", "sos-c02", "result", "ok").counter().count());
        assertEquals(1.0, registry.get("iotbridge.uplink.dropped").counter().count());
        assertEquals(1.0, registry.get("iotbridge.downlink").tags("result", "delivered").counter().count());
        assertEquals(3.0, registry.get("iotbridge.sessions.active").gauge().value());
        assertEquals(0.0, registry.get("iotbridge.downlink.buffer.pending").gauge().value());
    }

    @Test
    void noopInstanceDoesNotThrow() {
        BridgeMetrics.NOOP.uplinkFrame("p", "ok");
        BridgeMetrics.NOOP.uplinkDropped();
        BridgeMetrics.NOOP.downlink("delivered");
        BridgeMetrics.NOOP.reflow("sent");
    }
}
